<#
.SYNOPSIS
实验 B：重试风暴（B1）与超时重试的幂等（B2）。方案见 experiments/README.md「实验 B」。

.EXAMPLE
.\experiments\b-retry-storm\run.ps1 -Variant b1-naive
.\experiments\b-retry-storm\run.ps1 -Variant b1-controlled -PrometheusUrl http://<监控机>:9091
.\experiments\b-retry-storm\run.ps1 -Variant b2-new-key

.PARAMETER Variant
b1-naive | b1-controlled | b1-client-only | b1-server-only（重试风暴）；b2-new-key | b2-same-key（幂等）
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

$simulation = if ($Variant.StartsWith('b2-')) { 'RetryIdempotencySimulation' } else { 'RetryStormSimulation' }

# B1：小故障快结束时、结束 30 秒后各抓一次 mall-app 与支付服务的线程栈，看积压在哪一层、有没有消化掉
$during = {
    param($ctx)
    if ($simulation -ne 'RetryStormSimulation') { return }
    $exp = Get-HaSection $ctx.Props 'experiment'
    $prepareSec = [math]::Ceiling([double]$exp['ha.prepareUsers'] / [double]$exp['ha.prepareRps']) + 5
    $at = [int]$exp['ha.hiccupAtSec']
    $len = [int]$exp['ha.hiccupSec']
    foreach ($t in @(($at + $len - 5), ($at + $len + 30))) {
        Wait-HaUntil $ctx.StartedMs ([int]($prepareSec + $t))
        foreach ($svc in @($ctx.Topology.Apps[0], $ctx.Topology.Payment)) {
            Save-HaThreadDump $svc (Join-Path $ctx.RunDir ("threads-{0}-poll{1}s.txt" -f $svc.Name, $t)) $ctx.Java
        }
        Write-HaEvent $ctx.Events 'script' "轮询开始后约第 $t 秒抓了线程栈"
    }
}

Invoke-HaExperiment -Experiment 'b-retry-storm' -Variant $Variant -Simulation $simulation `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -During $during -Build:$Build -KeepServices:$KeepServices
