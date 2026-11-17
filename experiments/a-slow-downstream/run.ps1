<#
.SYNOPSIS
实验 A：下游变慢——超时、舱壁、熔断各挡住什么。方案见 experiments/README.md「实验 A」。

.EXAMPLE
.\experiments\a-slow-downstream\run.ps1 -Variant a0-no-protection -JavaHome C:\jdks\jdk-21
.\experiments\a-slow-downstream\run.ps1 -Variant a3-circuit-breaker -PrometheusUrl http://<监控机>:9091

.PARAMETER Variant
a0-no-protection | a1-timeout | a2-bulkhead | a3-circuit-breaker | a4-pool（对应同目录的 .properties）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.orderRps=60"、"mall-app:server.tomcat.threads.max=100"，可以给多个
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

# 压测期间：在注入故障后的几个时间点抓 mall-app 与库存服务的线程栈，看 Tomcat 线程到底卡在哪
$during = {
    param($ctx)
    $times = (Get-HaScriptValue $ctx.Props 'threadDumpAtSec' '90,150') -split ',' | ForEach-Object { [int]$_.Trim() }
    foreach ($t in $times) {
        Wait-HaUntil $ctx.StartedMs $t
        foreach ($svc in @($ctx.Topology.Apps[0], $ctx.Topology.Inventory)) {
            Save-HaThreadDump $svc (Join-Path $ctx.RunDir ("threads-{0}-t{1}s.txt" -f $svc.Name, $t)) $ctx.Java
        }
        Write-HaEvent $ctx.Events 'script' "第 $t 秒抓了线程栈"
    }
}

Invoke-HaExperiment -Experiment 'a-slow-downstream' -Variant $Variant -Simulation 'SlowDownstreamSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -During $during -Build:$Build -KeepServices:$KeepServices
