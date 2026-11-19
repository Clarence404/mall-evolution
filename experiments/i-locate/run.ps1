<#
.SYNOPSIS
实验 I：定位——一个固定的、远没压满的负载下，一次下单、一次查询的时间花在哪。方案见 experiments/README.md「实验 I」。

.EXAMPLE
.\experiments\i-locate\run.ps1 -Variant i1-baseline
.\experiments\i-locate\run.ps1 -Variant i1-baseline -PrometheusUrl http://<监控机>:9091
# 先用 1 分钟试一遍（不录 JFR）
.\experiments\i-locate\run.ps1 -Variant i1-baseline -Set "experiment:ha.warmupSec=20","experiment:ha.durationSec=40","script:jfrAtSec=0"

.PARAMETER Variant
i1-baseline（正式配置，录 JFR）；其余变体是在它的基础上改一处再量，见各 .properties 开头的说明
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.orderRps=200"、"script:jfrSec=60"，可以给多个
#>
param(
    [Parameter(Mandatory = $true)][string]$Variant,
    [string[]]$Set = @(),
    [string]$JavaHome,
    [string]$Java25Home,
    [string]$PrometheusUrl,
    [string]$NacosNamespace,
    [switch]$Build,
    [switch]$KeepServices
)
Import-Module (Join-Path $PSScriptRoot '..\lib\HaExperiment.psm1') -Force

$experiment = 'i-locate'
$skus = @('20001', '20002', '20003')
$props = Get-HaVariantProps $experiment $Variant $Set
$restockTarget = [int](Get-HaScriptValue $props 'restockTarget' '300000')
$drainWaitSec = [int](Get-HaScriptValue $props 'drainWaitSec' '600')
$drainIdleSec = [int](Get-HaScriptValue $props 'drainIdleSec' '120')
$jfrAtSec = [int](Get-HaScriptValue $props 'jfrAtSec' '0')
$jfrSec = [int](Get-HaScriptValue $props 'jfrSec' '120')
# [script] servicesJdk=25：四个服务都用 JDK 25 起（要设环境变量 MALL_JAVA25_HOME，或传 -Java25Home）
$servicesJavaHome = ''
if ((Get-HaScriptValue $props 'servicesJdk' '21') -eq '25') {
    $servicesJavaHome = if ($Java25Home) { $Java25Home } else { $env:MALL_JAVA25_HOME }
    if (-not $servicesJavaHome) { throw '这个变体要用 JDK 25 起服务：传 -Java25Home，或设置环境变量 MALL_JAVA25_HOME' }
}

# JFR 的 profile 模板之上再改几项：网络读写全部记下来（默认只记 20ms 以上的，一次 SQL 往返才一两毫秒），但不带调用栈，免得文件太大；
# 锁等待与线程挂起的门槛从 20ms 降到 1ms
$jfrSettings = @(
    'jdk.SocketRead#threshold=0ms', 'jdk.SocketRead#stackTrace=false',
    'jdk.SocketWrite#threshold=0ms', 'jdk.SocketWrite#stackTrace=false',
    'jdk.JavaMonitorEnter#threshold=1ms', 'jdk.ThreadPark#threshold=1ms', 'jdk.ExecutionSample#period=5ms')
if ((Get-HaScriptValue $props 'jfrSocketEvents' 'true') -ne 'true') {
    $jfrSettings = @('jdk.JavaMonitorEnter#threshold=1ms', 'jdk.ThreadPark#threshold=1ms', 'jdk.ExecutionSample#period=5ms')
}

$state = @{ Baseline = $null; JfrServices = @() }

# 压测开始之前：确认种子订单在、SKU 没留着分桶，补货并记下库存，开 JFR
$beforeLoad = {
    param($ctx)
    Confirm-HaSeedOrders
    foreach ($sku in $skus) {
        if (-not (Wait-HaBucketMerge $sku 180)) { throw "SKU $sku 还是分桶状态而且并不回去，先让服务多跑一会儿再试" }
    }
    Invoke-HaRestock $skus $restockTarget
    $sum = [long]0
    foreach ($sku in $skus) { $sum += Get-HaStock $sku }
    $state.Baseline = $sum
    # JFR 在流量进来之前就开好，一直录着，到点只取要看的那一段（为什么不能带着流量开，见 Start-HaJfr 的说明）
    if ($jfrAtSec -gt 0) {
        $state.JfrServices = @($ctx.Topology.Apps[0], $ctx.Topology.Inventory, $ctx.Topology.Gateway)
        foreach ($svc in $state.JfrServices) { Start-HaJfr $svc $jfrSettings }
    }
}

# 压测期间：等到要看的那一段开始时把 MySQL 的语句统计清零，这一段结束时取回语句统计、把各服务这一段的 JFR 写出来
$during = {
    param($ctx)
    if ($jfrAtSec -le 0) { return }
    while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt ($ctx.StartedMs + $jfrAtSec * 1000)) { Start-Sleep -Milliseconds 500 }
    if ($ctx.Gatling.HasExited) { return }
    Invoke-HaVm 'reset' | Out-Null
    Write-HaEvent $ctx.Events 'script' "第 $jfrAtSec 秒：MySQL 语句统计清零，从这里起的 $jfrSec 秒是要看的那一段"
    $until = $ctx.StartedMs + ($jfrAtSec + $jfrSec) * 1000
    while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt $until) { Start-Sleep -Milliseconds 500 }
    $digest = Invoke-HaVm 'digest'
    if ($digest.Count -gt 0) {
        @("schema`tcount`ttotal_ms`tavg_us`tmax_ms`trows_examined`trows_sent`trows_affected`tstatement") + $digest |
            Out-File (Join-Path $ctx.RunDir 'vm-mysql-digest.txt') -Encoding utf8
    }
    foreach ($svc in $state.JfrServices) {
        Save-HaJfr $svc (Join-Path $ctx.RunDir "jfr\$($svc.Name).jfr") $jfrSec
    }
    Write-HaEvent $ctx.Events 'script' "第 $($jfrAtSec + $jfrSec) 秒：这一段的 JFR 与 MySQL 语句统计已取回"
}

# 收尾（停服务之前）：等实验订单关完、库存还回来，免得积压的超时关单带进下一轮
$cleanup = {
    param($ctx)
    if ($drainWaitSec -le 0 -or $null -eq $state.Baseline) { return }
    if (-not $ctx.Topology.Inventory -or $ctx.Topology.Inventory.Process.HasExited) { return }
    $n = Invoke-HaStuckRelease (Join-Path $ctx.RunDir 'logs') $skus
    if ($n -gt 0) { Write-HaEvent $ctx.Events 'script' "mall-app 日志里有 $n 个订单释放预占失败，已逐个调 release" }
    Wait-HaStockRestored -Skus $skus -Baseline $state.Baseline -TimeoutSec $drainWaitSec -IdleSec $drainIdleSec -EventsFile $ctx.Events | Out-Null
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'LocateSimulation' `
    -Set $Set -JavaHome $JavaHome -ServicesJavaHome $servicesJavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -Skus $skus -RestockTarget $restockTarget -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup `
    -SamplerExtraPattern 'hikaricp_connections_(usage|acquire)_seconds_(sum|count|max)|jvm_gc_pause_seconds_(sum|count|max)|jvm_gc_memory_(allocated|promoted)_bytes_total|jvm_gc_live_data_size_bytes|process_cpu_time_ns_total' `
    -Build:$Build -KeepServices:$KeepServices
