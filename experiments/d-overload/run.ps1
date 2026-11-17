<#
.SYNOPSIS
实验 D：突发流量超过容量——入口限流与降级能不能保住下单。方案见 experiments/README.md「实验 D」。

.EXAMPLE
.\experiments\d-overload\run.ps1 -Variant d0-probe
.\experiments\d-overload\run.ps1 -Variant d2-limit -Set "mall-gateway:mall.gateway.route-limits.anonymous.permits-per-second=360"

.PARAMETER Variant
d0-probe | d0-probe-real | d1-none | d2-limit | d3-degrade | d4-limit-degrade
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

# 高峰中段抓一次 mall-app 的线程栈：看 Tomcat 线程是在排队等容量（注入的并发上限），还是在干活
$during = {
    param($ctx)
    $exp = Get-HaSection $ctx.Props 'experiment'
    if ($exp['ha.mode'] -ne 'burst') { return }
    $t = [int]$exp['ha.burstAtSec'] + [int]([int]$exp['ha.burstSec'] / 2)
    Wait-HaUntil $ctx.StartedMs $t
    Save-HaThreadDump $ctx.Topology.Apps[0] (Join-Path $ctx.RunDir ("threads-mall-app-1-t{0}s.txt" -f $t)) $ctx.Java
    Write-HaEvent $ctx.Events 'script' "第 $t 秒抓了线程栈"
}

Invoke-HaExperiment -Experiment 'd-overload' -Variant $Variant -Simulation 'OverloadSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -During $during -Build:$Build -KeepServices:$KeepServices
