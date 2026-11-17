<#
.SYNOPSIS
实验 C：滚动发布丢不丢请求。网关后面 3 个 mall-app 实例，压测期间按变体规定的方式逐个重启。方案见 experiments/README.md「实验 C」。

.EXAMPLE
.\experiments\c-rolling-restart\run.ps1 -Variant c0-hard
.\experiments\c-rolling-restart\run.ps1 -Variant c2-offline-first -PrometheusUrl http://<监控机>:9091

.PARAMETER Variant
c0-hard | c1-graceful | c2-offline-first | c3-short-lb-cache | c3b-longer-wait | c4-crash
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

# 逐个重启。每一步都写进 script-events.csv（epoch 毫秒），和 timeline.csv（Gatling 逐秒结果）、metrics.csv（每实例请求计数）对齐着看
$during = {
    param($ctx)
    $mode = Get-HaScriptValue $ctx.Props 'restartMode' 'sigterm'
    $waitFor = Get-HaScriptValue $ctx.Props 'waitFor' 'readiness'
    $offlineWait = [int](Get-HaScriptValue $ctx.Props 'offlineWaitSec' '15')
    $crashRestartAfter = [int](Get-HaScriptValue $ctx.Props 'crashRestartAfterSec' '5')
    $gap = [int](Get-HaScriptValue $ctx.Props 'gapSec' '60')
    Wait-HaUntil $ctx.StartedMs ([int](Get-HaScriptValue $ctx.Props 'firstRestartAtSec' '60'))

    for ($i = 0; $i -lt $ctx.Topology.Apps.Count; $i++) {
        $old = $ctx.Topology.Apps[$i]
        $name = $old.Name
        switch ($mode) {
            'offline-first' {
                Write-HaEvent $ctx.Events $name '从 Nacos 注销（/internal/offline）'
                Invoke-HaOffline $old
                Start-Sleep -Seconds $offlineWait
                Write-HaEvent $ctx.Events $name "等了 $offlineWait 秒，发 SIGTERM"
                Stop-HaService $old 'graceful'
            }
            'sigterm' {
                Write-HaEvent $ctx.Events $name '发 SIGTERM（不先注销）'
                Stop-HaService $old 'graceful'
            }
            'crash' {
                Write-HaEvent $ctx.Events $name 'kill -9'
                Stop-HaService $old 'hard'
            }
            default { throw "不认识的 restartMode：$mode" }
        }
        Write-HaEvent $ctx.Events $name '旧进程已退出'
        if ($mode -eq 'crash') { Start-Sleep -Seconds $crashRestartAfter }

        $new = Restart-HaServiceProcess $old $ctx.Props "-r$($i + 1)"
        Write-HaEvent $ctx.Events $name '新进程已启动'
        if ($waitFor -eq 'port') {
            Wait-HaPort $new.Port
            Write-HaEvent $ctx.Events $name '端口已监听（不等就绪，接着重启下一个）'
        } else {
            Wait-HaReady $new
            Write-HaEvent $ctx.Events $name '已就绪（随后注册到 Nacos）'
        }
        $ctx.Topology.Apps[$i] = $new
        if ($i -lt $ctx.Topology.Apps.Count - 1) { Start-Sleep -Seconds $gap }
    }
    Write-HaEvent $ctx.Events 'script' '三个实例都已重启'
}

Invoke-HaExperiment -Experiment 'c-rolling-restart' -Variant $Variant -Simulation 'RollingRestartSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -MallAppCount 3 -During $during -Build:$Build -KeepServices:$KeepServices
