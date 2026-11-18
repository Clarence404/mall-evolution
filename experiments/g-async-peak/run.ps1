<#
.SYNOPSIS
实验 G：异步削峰——下单量突然翻三倍时，下单接口照常返回，多出来的后续处理在消息队列里排队，由消费者按自己的速度消化。方案见 experiments/README.md「实验 G」。

.DESCRIPTION
压的只有下单。看的是下单之后那条异步链路：订单 60 秒未支付 → mall-app 关单 → order-cancelled → 库存服务释放预占。
对照组与实验组的差别在库存服务的启动参数里：释放预占的消费组每条消息多停留多久（mall.demo.consume-delay-ms，只在 demo profile 下生效）、
用几个消费线程（mall.rocketmq.consumption-threads）。
脚本比别的实验多做的：压测前先等上一轮留在 Broker 里的消息消费完；让逐秒采样多留消费延迟与本地消息表的指标；
到点抓库存服务的线程栈并数出消费线程在干什么；压测后等实验订单关完、库存还回来。

.EXAMPLE
.\experiments\g-async-peak\run.ps1 -Variant g0-baseline
.\experiments\g-async-peak\run.ps1 -Variant g2-slow-4 -PrometheusUrl http://<监控机>:9091
# 先用缩短的参数试一遍
.\experiments\g-async-peak\run.ps1 -Variant g2-slow-4 -Set "experiment:ha.burstAtSec=20","experiment:ha.burstSec=20","experiment:ha.durationSec=60","experiment:ha.drainSec=90"

.PARAMETER Variant
g0-baseline | g1-slow-2 | g2-slow-4 | g3-slow-8（对应同目录的 .properties）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.burstRps=100"、"inventory-service:mall.demo.consume-delay-ms=50"，可以给多个
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

$experiment = 'g-async-peak'
$skus = @('20001', '20002', '20003')
$props = Get-HaVariantProps $experiment $Variant $Set
$restockTarget = [int](Get-HaScriptValue $props 'restockTarget' '100000')
$settleWaitSec = [int](Get-HaScriptValue $props 'settleWaitSec' '180')
$drainWaitSec = [int](Get-HaScriptValue $props 'drainWaitSec' '900')
$drainIdleSec = [int](Get-HaScriptValue $props 'drainIdleSec' '120')
# 逐秒采样（metrics.csv）除默认那批指标外再留：各消费组消费延迟的次数、总和、最大值；关单与释放预占两个消费组处理成功的直方图桶
# （全留的话每秒 400 行，一轮下来几十兆）；本地消息表的两个指标。正则里用 . 代替双引号：它要原样放进命令行参数
$samplerExtra = 'mq_consume_delay_seconds_(count|sum|max)|mq_consume_delay_seconds_bucket\{.*group=.(inventory-release|order-timeout)-consumer.*result=.success|outbox_pending_age_seconds|outbox_failed_count'

# 各服务已经消费了多少条消息（所有消费组、成功与失败合计）：读管理端口上 mq_consume_delay_seconds_count 的各行相加
function Get-ConsumedTotal($Topology) {
    $sum = [double]0
    foreach ($svc in (@($Topology.Apps) + @($Topology.Inventory, $Topology.Payment))) {
        if (-not $svc -or $svc.Process.HasExited) { continue }
        $text = (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$($svc.MgmtPort)/actuator/prometheus" -TimeoutSec 5).Content
        foreach ($line in ($text -split "`n")) {
            if ($line.StartsWith('mq_consume_delay_seconds_count') -and $line -match '\s([0-9.eE+-]+)\s*$') { $sum += [double]$Matches[1] }
        }
    }
    return $sum
}

<#
把线程栈里 RocketMQ 的消费线程（线程名含 MessageConsumption）按「在干什么」数一遍，写成 *.consume.csv：
停在演示的消费延迟上（DemoConsumeDelay 的 sleep）、在数据库上（执行 SQL、等连接）、空闲（等下一条消息）。
公共脚本的分类只看 Tomcat 业务线程，看不到这些。
#>
function Save-ConsumeThreadSummary([string]$DumpFile) {
    $text = Get-Content -LiteralPath $DumpFile -Raw -Encoding UTF8
    $counts = [ordered]@{ '停在消费延迟上（演示开关的 sleep）' = 0; '在数据库上（执行 SQL 或等连接）' = 0; '空闲（等下一条消息）' = 0; '其他' = 0 }
    foreach ($b in ($text -split '(?:\r?\n){2,}')) {
        if ($b -notmatch '^"[^"]*MessageConsumption[^"]*"') { continue }
        if ($b -match 'DemoConsumeDelay') { $counts['停在消费延迟上（演示开关的 sleep）']++ }
        elseif ($b -match 'com\.mysql\.cj\.|HikariPool\.getConnection') { $counts['在数据库上（执行 SQL 或等连接）']++ }
        elseif ($b -match 'ThreadPoolExecutor\.getTask') { $counts['空闲（等下一条消息）']++ }
        else { $counts['其他']++ }
    }
    $csv = [System.IO.Path]::ChangeExtension($DumpFile, '.consume.csv')
    $lines = @('state,threads') + ($counts.Keys | ForEach-Object { "$_,$($counts[$_])" })
    [System.IO.File]::WriteAllLines($csv, $lines, (New-Object System.Text.UTF8Encoding($false)))
    return (($counts.Keys | ForEach-Object { "$_=$($counts[$_])" }) -join '，')
}

# 这一轮的状态：压测前三个 SKU 的库存之和（收尾时等它回来）
$state = @{ Baseline = $null }

# 压测开始之前：确认 SKU 不在分桶状态 → 等上一轮留下的消息消费完 → 补货、记下库存与这一轮的消费参数
$beforeLoad = {
    param($ctx)
    # 分桶的 SKU 补货接口不处理；实验 E 中途被打断时会留下分桶，这里先并回去（没分桶时接口直接返回）
    foreach ($sku in $skus) {
        if (-not (Wait-HaBucketMerge $sku 180)) {
            throw "SKU $sku 还是分桶状态而且并不回去（各桶还有未释放的预占）。先跑一轮实验 E 让它收尾，或让服务多跑一会儿再试"
        }
    }
    # 上一轮没消费完的消息（服务被停掉时还在 Broker 里的关单、释放）会在这一轮服务一启动就涌进来，它们的消费延迟是「两轮之间隔了多久」，
    # 混进来会把这一轮的数据搞乱。等消费计数连续 10 秒不变再开始
    $first = Get-ConsumedTotal $ctx.Topology
    $last = $first; $lastChange = Get-Date; $start = Get-Date
    while (((Get-Date) - $lastChange).TotalSeconds -lt 10 -and ((Get-Date) - $start).TotalSeconds -lt $settleWaitSec) {
        Start-Sleep -Seconds 2
        $now = Get-ConsumedTotal $ctx.Topology
        if ($now -ne $last) { $last = $now; $lastChange = Get-Date }
    }
    $waited = [int]((Get-Date) - $start).TotalSeconds
    $settled = ((Get-Date) - $lastChange).TotalSeconds -ge 10
    if ($last -gt 0 -or -not $settled) {
        $tail = if ($settled) { '已经消费完' } else { "等了 $settleWaitSec 秒还在消费，这一轮开头的消费延迟会被它们抬高" }
        Write-HaEvent $ctx.Events 'script' "压测开始前已消费 $last 条上一轮留下的消息（等了 $waited 秒）：$tail"
    }
    Invoke-HaRestock $skus $restockTarget
    $sum = [long]0
    foreach ($sku in $skus) { $sum += Get-HaStock $sku }
    $state.Baseline = $sum
    $inv = Get-HaServiceArgs $ctx.Props 'inventory-service'
    Write-HaEvent $ctx.Events 'script' ("库存服务：消费线程 $($inv['mall.rocketmq.consumption-threads']) 个/组，消费延迟 $($inv['mall.demo.consume-delay-ms'])ms" +
        "（消费组 $($inv['mall.demo.consume-delay-groups'])）；SKU $($skus -join '、') 的库存合计 $sum")
}

# 压测期间：到点抓库存服务的线程栈，数出消费线程有几个停在消费延迟上、几个在数据库上、几个空闲
$during = {
    param($ctx)
    $spec = Get-HaScriptValue $ctx.Props 'threadDumpAtSec' ''
    $times = @($spec -split ',' | Where-Object { $_.Trim() } | ForEach-Object { [int]$_.Trim() } | Sort-Object)
    foreach ($t in $times) {
        while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt ($ctx.StartedMs + $t * 1000)) { Start-Sleep -Milliseconds 500 }
        if ($ctx.Gatling.HasExited) { return }   # 用 -Set 缩短了时长：到不了这个时刻，不抓
        $svc = $ctx.Topology.Inventory
        $file = Join-Path $ctx.RunDir ("threads-{0}-t{1}s.txt" -f $svc.Name, $t)
        Save-HaThreadDump $svc $file $ctx.Java
        Write-HaEvent $ctx.Events 'script' ("第 $t 秒抓了线程栈；库存服务的消费线程：" + (Save-ConsumeThreadSummary $file))
    }
}

# 收尾（停服务之前）：释放卡住的预占，等实验订单关完、库存还回来——没消费完的消息留在 Broker 里，会算进下一轮
$cleanup = {
    param($ctx)
    if ($drainWaitSec -le 0 -or $null -eq $state.Baseline) { return }
    if (-not $ctx.Topology.Inventory -or $ctx.Topology.Inventory.Process.HasExited) { return }
    $n = Invoke-HaStuckRelease (Join-Path $ctx.RunDir 'logs') $skus
    if ($n -gt 0) {
        Write-HaEvent $ctx.Events 'script' "mall-app 日志里有 $n 个订单「释放预占失败，交给过期回查」（下单失败后 release 也失败），已逐个调 release"
    }
    Wait-HaStockRestored -Skus $skus -Baseline $state.Baseline -TimeoutSec $drainWaitSec -IdleSec $drainIdleSec -EventsFile $ctx.Events | Out-Null
    Write-HaEvent $ctx.Events 'script' "各服务启动以来共消费 $(Get-ConsumedTotal $ctx.Topology) 条消息（所有消费组合计，含失败重投）"
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'AsyncPeakSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -Skus $skus -RestockTarget $restockTarget -SamplerExtraPattern $samplerExtra `
    -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup -Build:$Build -KeepServices:$KeepServices
