<#
.SYNOPSIS
实验 K：JVM——同一组只读流量下，收集器、堆大小、JDK 版本、有没有预热，各差多少。方案见 experiments/README.md「实验 K」。

.EXAMPLE
.\experiments\k-jvm\run.ps1 -Variant k1-g1-1g
.\experiments\k-jvm\run.ps1 -Variant k1-zgc-1g-jdk25 -Java25Home C:\jdk-25
.\experiments\k-jvm\run.ps1 -Variant k2-cold -PrometheusUrl http://<监控机>:9091

.PARAMETER Variant
k1-<g1|zgc|parallel|serial>-<1g|512m>（收集器 × 堆大小）；k1-g1-1g-jdk25 | k1-zgc-1g-jdk25（mall-app 换 JDK 25）；
k2-cold（不预热直接全速）| k2-ramp（不预热、30 秒爬坡）| k2-c1-only（只用 C1 编译器）
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

# 压测开始之前：确认种子订单在（查询查的是它们）
$beforeLoad = {
    param($ctx)
    Confirm-HaSeedOrders
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'LocateSimulation' `
    -Set $Set -JavaHome $JavaHome -MallAppJavaHome $appJavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -BeforeLoad $beforeLoad `
    -SamplerExtraPattern 'hikaricp_connections_(usage|acquire)_seconds_(sum|count|max)|jvm_gc_pause_seconds_(sum|count|max)|jvm_gc_memory_(allocated|promoted)_bytes_total|jvm_gc_live_data_size_bytes|jvm_memory_used_bytes|jvm_compilation_time_ms_total|process_cpu_time_ns_total' `
    -Build:$Build -KeepServices:$KeepServices
