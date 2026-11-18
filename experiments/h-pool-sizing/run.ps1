<#
.SYNOPSIS
实验 H：连接池与线程池、实例数——池子是不是越大越好，实例翻倍吞吐翻不翻倍。方案见 experiments/README.md「实验 H」。

.EXAMPLE
.\experiments\h-pool-sizing\run.ps1 -Variant h1-pool-20
.\experiments\h-pool-sizing\run.ps1 -Variant h3-app-2 -PrometheusUrl http://<监控机>:9091
# 先用 1 分钟试一遍
.\experiments\h-pool-sizing\run.ps1 -Variant h1-pool-5 -Set "experiment:ha.durationSec=60"

.PARAMETER Variant
h1-pool-5 | h1-pool-10 | h1-pool-20 | h1-pool-40 | h1-pool-80（连接池）；h2-tomcat-50 | h2-tomcat-400（Tomcat 线程数）；
h3-app-1 | h3-app-2 | h3-app-3（实例数）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.users=400"、"mall-app:spring.datasource.hikari.maximum-pool-size=30"，可以给多个
#>
param(
    [Parameter(Mandatory = $true)][string]$Variant,
    [string[]]$Set = @(),
    [string]$JavaHome,
    [string]$PrometheusUrl,
    [string]$NacosNamespace,
    [switch]$Build,
    [switch]$KeepServices
)
Import-Module (Join-Path $PSScriptRoot '..\lib\HaExperiment.psm1') -Force

$experiment = 'h-pool-sizing'
$skus = @('20001', '20002', '20003')
$props = Get-HaVariantProps $experiment $Variant $Set
$mallAppCount = [int](Get-HaScriptValue $props 'mallAppCount' '1')
$restockTarget = [int](Get-HaScriptValue $props 'restockTarget' '300000')
$drainWaitSec = [int](Get-HaScriptValue $props 'drainWaitSec' '900')
$drainIdleSec = [int](Get-HaScriptValue $props 'drainIdleSec' '120')
if ($mallAppCount -lt 1 -or $mallAppCount -gt 3) { throw "mallAppCount 只能是 1–3（端口与抓取目标只准备了 3 个实例），实际：$mallAppCount" }

<#
把线程栈里的 Tomcat 业务线程按「和数据库的关系」再分一次类，写成 *.db.csv。公共脚本的分类看不出这一项：
等连接池里的连接（HikariPool.getConnection）会被归到「其他」，等 MySQL 响应会和在调下游（HTTP：等连接或等响应）混在一起。
#>
function Save-DbThreadSummary([string]$DumpFile, [int]$Port) {
    $text = Get-Content -LiteralPath $DumpFile -Raw -Encoding UTF8
    $counts = [ordered]@{ '等数据库连接（HikariCP）' = 0; '在数据库上（执行 SQL、等 MySQL 响应）' = 0; '在调下游（HTTP：等连接或等响应）' = 0; '空闲' = 0; '其他' = 0 }
    foreach ($b in ($text -split '(?:\r?\n){2,}')) {
        if ($b -notmatch "`"http-nio-$Port-exec-\d+`"") { continue }
        if ($b -match 'HikariPool\.getConnection') { $counts['等数据库连接（HikariCP）']++ }
        elseif ($b -match 'com\.mysql\.cj\.') { $counts['在数据库上（执行 SQL、等 MySQL 响应）']++ }
        elseif ($b -match 'org\.apache\.hc\.') { $counts['在调下游（HTTP：等连接或等响应）']++ }
        elseif ($b -match 'TaskQueue\.(take|poll)') { $counts['空闲']++ }
        else { $counts['其他']++ }
    }
    $csv = [System.IO.Path]::ChangeExtension($DumpFile, '.db.csv')
    $lines = @('state,threads') + ($counts.Keys | ForEach-Object { "$_,$($counts[$_])" })
    [System.IO.File]::WriteAllLines($csv, $lines, (New-Object System.Text.UTF8Encoding($false)))
    return (($counts.Keys | ForEach-Object { "$_=$($counts[$_])" }) -join '，')
}

# 这一轮的状态：压测前三个 SKU 的库存之和（收尾时等它回来）
$state = @{ Baseline = $null }

# 压测开始之前：确认三个 SKU 都没有留着实验 E 的分桶，再补一次货，记下库存
$beforeLoad = {
    param($ctx)
    # 分桶的 SKU 补货接口不处理，下单走的也是分桶那条路径；实验 E 中途被打断时会留下分桶，这里先并回去（没分桶时接口直接返回）
    foreach ($sku in $skus) {
        if (-not (Wait-HaBucketMerge $sku 180)) {
            throw "SKU $sku 还是分桶状态而且并不回去（各桶还有未释放的预占）。先跑一轮实验 E 让它收尾，或让服务多跑一会儿再试"
        }
    }
    Invoke-HaRestock $skus $restockTarget
    $sum = [long]0
    foreach ($sku in $skus) { $sum += Get-HaStock $sku }
    $state.Baseline = $sum
    Write-HaEvent $ctx.Events 'script' "mall-app $mallAppCount 个实例；SKU $($skus -join '、') 的库存合计 $sum"
}

# 压测期间：到点抓每个 mall-app 实例的线程栈，看 Tomcat 线程是在等连接池、在数据库上，还是空闲
$during = {
    param($ctx)
    $spec = Get-HaScriptValue $ctx.Props 'threadDumpAtSec' ''
    $times = @($spec -split ',' | Where-Object { $_.Trim() } | ForEach-Object { [int]$_.Trim() } | Sort-Object)
    foreach ($t in $times) {
        while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt ($ctx.StartedMs + $t * 1000)) { Start-Sleep -Milliseconds 500 }
        if ($ctx.Gatling.HasExited) { return }   # 用 -Set 缩短了时长：到不了这个时刻，不抓
        foreach ($svc in $ctx.Topology.Apps) {
            $file = Join-Path $ctx.RunDir ("threads-{0}-t{1}s.txt" -f $svc.Name, $t)
            Save-HaThreadDump $svc $file $ctx.Java
            Write-HaLog ("$($svc.Name) 线程与数据库：" + (Save-DbThreadSummary $file $svc.Port))
        }
        Write-HaEvent $ctx.Events 'script' "第 $t 秒抓了线程栈"
    }
}

# 收尾（停服务之前）：释放卡住的预占，等实验订单关完、库存还回来，免得积压的超时关单带进下一轮（下一轮一启动就要消费它们，占着连接池）
$cleanup = {
    param($ctx)
    if ($drainWaitSec -le 0 -or $null -eq $state.Baseline) { return }
    if (-not $ctx.Topology.Inventory -or $ctx.Topology.Inventory.Process.HasExited) { return }
    # 下单失败后 release 也失败的预占要 16 分钟后才由过期回查释放：按 mall-app 日志提前释放掉，库存才回得到压测前的数。
    # 一个订单只有一个 SKU，日志里看不出是哪个，三个都带上（没预占过的只留一行已释放的占位）
    $n = Invoke-HaStuckRelease (Join-Path $ctx.RunDir 'logs') $skus
    if ($n -gt 0) {
        Write-HaEvent $ctx.Events 'script' "mall-app 日志里有 $n 个订单「释放预占失败，交给过期回查」（下单失败后 release 也失败），已逐个调 release"
    }
    Wait-HaStockRestored -Skus $skus -Baseline $state.Baseline -TimeoutSec $drainWaitSec -IdleSec $drainIdleSec -EventsFile $ctx.Events | Out-Null
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'PoolSizingSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -MallAppCount $mallAppCount -Skus $skus -RestockTarget $restockTarget `
    -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup -Build:$Build -KeepServices:$KeepServices
