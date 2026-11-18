<#
.SYNOPSIS
实验 F：多级缓存——没有缓存、只有 Redis、两级缓存各能撑到每秒多少次详情；热点 key 失效时的击穿；不存在的 SKU 的穿透。方案见 experiments/README.md「实验 F」。

.DESCRIPTION
只压匿名的商品详情（GET /api/products/{skuId}，网关的 anonymous 路由）。缓存哪一层开、单飞开不开、空值缓不缓存，
都是 mall-app 的启动参数（mall.demo.detail-cache.*，只在 demo profile 下生效），写在各变体的 .properties 里。
脚本自己只做三件事：到点抓 mall-app 的线程栈；让逐秒采样多留商品详情缓存的几个计数器；收尾时核对网关有没有对匿名路由限流
（限了的话这一轮的数据不能用）。

.EXAMPLE
.\experiments\f-cache-layers\run.ps1 -Variant f1-no-cache
.\experiments\f-cache-layers\run.ps1 -Variant f2-breakdown-no-single-flight -PrometheusUrl http://<监控机>:9091
# 先用短阶梯试一遍
.\experiments\f-cache-layers\run.ps1 -Variant f1-two-level -Set "experiment:ha.stepSec=15","experiment:ha.maxRps=600"

.PARAMETER Variant
f1-no-cache | f1-redis-only | f1-two-level（阶梯）；f2-breakdown-single-flight | f2-breakdown-no-single-flight（击穿）；
f3-penetration-repeat-on | f3-penetration-repeat-off | f3-penetration-random-on | f3-penetration-random-off（穿透）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.maxRps=2400"、"mall-app:mall.demo.detail-cache.load-delay-ms=100"，可以给多个
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

$experiment = 'f-cache-layers'
# 存在的 SKU：演示数据里上架的只有这 3 个（另有 20004 是下架的演示商品，不用）。第一个是热点
$skus = @('20001', '20002', '20003')
# 逐秒采样（metrics.csv）除默认那批指标外再留：回源次数与 Redis 命中情况、本机缓存的命中与未命中、数据库连接的取用耗时与超时
$samplerExtra = 'product_detail_|cache_gets_total|cache_size|hikaricp_connections_(acquire|usage)_seconds_(count|sum|max)|hikaricp_connections_timeout_total'

# 一个服务的管理端口上某个计数器的合计（所有标签加起来）；指标还不存在时返回 0
function Get-MetricSum([int]$MgmtPort, [string]$Name) {
    $text = (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$MgmtPort/actuator/prometheus" -TimeoutSec 5).Content
    $sum = [double]0
    foreach ($line in ($text -split "`n")) {
        if ($line.StartsWith($Name) -and $line -match '\s([0-9.eE+-]+)\s*$') { $sum += [double]$Matches[1] }
    }
    return $sum
}

# 压测开始之前：记下这一轮的缓存开关（从 mall-app 的启动参数里读），事后看 script-events.csv 就知道这一轮是哪种配置
$beforeLoad = {
    param($ctx)
    $app = Get-HaServiceArgs $ctx.Props 'mall-app'
    $flags = @('l1-enabled', 'l2-enabled', 'single-flight-enabled', 'null-cache-enabled', 'load-delay-ms') |
        ForEach-Object { "$_=$($app["mall.demo.detail-cache.$_"])" }
    $exp = Get-HaSection $ctx.Props 'experiment'
    Write-HaEvent $ctx.Events 'script' "模式 $($exp['ha.mode'])；商品详情缓存开关：$($flags -join '，')"
}

# 压测期间：到点抓 mall-app 的线程栈，看 Tomcat 线程是在等数据库连接、在数据库上、等本机缓存加载（单飞）还是空闲
$during = {
    param($ctx)
    $spec = Get-HaScriptValue $ctx.Props 'threadDumpAtSec' ''
    $times = @($spec -split ',' | Where-Object { $_.Trim() } | ForEach-Object { [int]$_.Trim() } | Sort-Object)
    foreach ($t in $times) {
        while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt ($ctx.StartedMs + $t * 1000)) { Start-Sleep -Milliseconds 500 }
        if ($ctx.Gatling.HasExited) { return }   # 用 -Set 缩短了阶梯：到不了这个时刻，不抓
        $svc = $ctx.Topology.Apps[0]
        Save-HaThreadDump $svc (Join-Path $ctx.RunDir ("threads-{0}-t{1}s.txt" -f $svc.Name, $t)) $ctx.Java
        Write-HaEvent $ctx.Events 'script' "第 $t 秒抓了线程栈"
    }
}

# 收尾（停服务之前）：记下这一轮一共回源多少次；核对网关有没有对匿名路由限流——限了的话压到 mall-app 的量比 Gatling 发出的少，数据不能用
$cleanup = {
    param($ctx)
    if ($ctx.Topology.Apps.Count -gt 0 -and -not $ctx.Topology.Apps[0].Process.HasExited) {
        $loads = Get-MetricSum $ctx.Topology.Apps[0].MgmtPort 'product_detail_db_load_total'
        Write-HaEvent $ctx.Events 'script' "mall-app 启动以来回源（查库）共 $loads 次（含启动预热与压测预热）"
    }
    if ($ctx.Topology.Gateway -and -not $ctx.Topology.Gateway.Process.HasExited) {
        $limited = Get-MetricSum $ctx.Topology.Gateway.MgmtPort 'gateway_route_limited_total'
        if ($limited -gt 0) {
            Write-HaEvent $ctx.Events 'script' "注意：网关按路由限流拒绝了 $limited 个请求（mall.gateway.route-limits 被配置了，多半来自共用的 Nacos），这一轮的数据不能用"
        } else {
            Write-HaEvent $ctx.Events 'script' '网关没有按路由限流（gateway_route_limited_total 为 0）'
        }
    }
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'CacheLayersSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -Skus $skus -SamplerExtraPattern $samplerExtra -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup `
    -Build:$Build -KeepServices:$KeepServices
