<#
.SYNOPSIS
实验 K：JVM——同一组只读流量下，收集器、堆大小、JDK 版本、有没有预热，各差多少。方案见 experiments/README.md「实验 K」。

.EXAMPLE
.\experiments\k-jvm\run.ps1 -Variant k1-g1-1g
.\experiments\k-jvm\run.ps1 -Variant k1-zgc-1g-jdk25 -Java25Home C:\jdk-25
.\experiments\k-jvm\run.ps1 -Variant k2-cold -PrometheusUrl http://<监控机>:9091

.PARAMETER Variant
k1-<g1|zgc|parallel|serial>-<1g|512m>（收集器 × 堆大小）；k1-g1-1g-jdk25 | k1-zgc-1g-jdk25（mall-app 换 JDK 25）；
k2-cold（不预热直接全速）| k2-ramp（不预热、30 秒爬坡）| k2-c1-only（只用 C1 编译器）；
k1-g1-1g-jfr（同 k1-g1-1g，给 mall-app 录 JFR）| k1-g1-1g-unpinned（同 k1-g1-1g，不绑核）
.PARAMETER Java25Home
JDK 25 的目录，mallAppJdk=25 的变体要用；不传取环境变量 MALL_JAVA25_HOME
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.listRps=1000"、"script:mallAppCpus=0"（不绑核），可以给多个
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

$experiment = 'k-jvm'
$props = Get-HaVariantProps $experiment $Variant $Set
$appJavaHome = ''
if ((Get-HaScriptValue $props 'mallAppJdk' '21') -eq '25') {
    $appJavaHome = if ($Java25Home) { $Java25Home } else { $env:MALL_JAVA25_HOME }
    if (-not $appJavaHome) { throw '这个变体要用 JDK 25 起 mall-app：传 -Java25Home，或设置环境变量 MALL_JAVA25_HOME' }
}

# [script] jfrAtSec / jfrSec：给 mall-app 录一段 JFR（从第 jfrAtSec 秒起的 jfrSec 秒），用来查某一类耗时出在哪段代码上。
# 默认不录：录制本身有开销，比较各变体的耗时时不开，只在单独的「-jfr」变体里开
$jfrAtSec = [int](Get-HaScriptValue $props 'jfrAtSec' '0')
$jfrSec = [int](Get-HaScriptValue $props 'jfrSec' '120')
# profile 模板之上：锁等待与线程挂起的门槛从 20ms 降到 1ms（要找的是十几毫秒的挂起），CPU 采样间隔 5ms
$jfrSettings = @('jdk.JavaMonitorEnter#threshold=1ms', 'jdk.ThreadPark#threshold=1ms', 'jdk.ExecutionSample#period=5ms')

# 压测开始之前：确认种子订单在（查询查的是它们）；要录 JFR 的话在流量进来之前就开好（为什么不能带着流量开，见 Start-HaJfr 的说明）
$beforeLoad = {
    param($ctx)
    Confirm-HaSeedOrders
    if ($jfrAtSec -gt 0) { Start-HaJfr $ctx.Topology.Apps[0] $jfrSettings }
}

# 压测期间：到点把 mall-app 这一段的 JFR 写出来
$during = {
    param($ctx)
    if ($jfrAtSec -le 0) { return }
    $until = $ctx.StartedMs + ($jfrAtSec + $jfrSec) * 1000
    while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt $until) { Start-Sleep -Milliseconds 500 }
    if ($ctx.Gatling.HasExited) { return }
    $svc = $ctx.Topology.Apps[0]
    Save-HaJfr $svc (Join-Path $ctx.RunDir "jfr\$($svc.Name).jfr") $jfrSec
    Write-HaEvent $ctx.Events 'script' "第 $($jfrAtSec + $jfrSec) 秒：mall-app 最近 $jfrSec 秒的 JFR 已取回"
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'LocateSimulation' `
    -Set $Set -JavaHome $JavaHome -MallAppJavaHome $appJavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -BeforeLoad $beforeLoad -During $during `
    -SamplerExtraPattern 'hikaricp_connections_(usage|acquire)_seconds_(sum|count|max)|jvm_gc_pause_seconds_(sum|count|max)|jvm_gc_memory_(allocated|promoted)_bytes_total|jvm_gc_live_data_size_bytes|jvm_memory_used_bytes|jvm_compilation_time_ms_total|process_cpu_time_ns_total' `
    -Build:$Build -KeepServices:$KeepServices
