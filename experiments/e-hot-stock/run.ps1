<#
.SYNOPSIS
实验 E：热点库存——所有人抢同一个 SKU 时，单行扣减与分桶各能撑到每秒多少单。方案见 experiments/README.md「实验 E」。

.DESCRIPTION
和别的实验相比多两步：压测开始前按变体把热点 SKU 拆成若干个桶（POST /internal/inventory/buckets/{skuId}/split?count=N），
压测结束后等实验订单关完、库存还回来，再并回单行（…/merge）。合并要求各桶没有未释放的预占，所以必须等关单；
上一轮没并回去的（中途被打断），这一轮开始时会先并回去再补货。

.EXAMPLE
.\experiments\e-hot-stock\run.ps1 -Variant e0-single-row
.\experiments\e-hot-stock\run.ps1 -Variant e2-buckets-8 -PrometheusUrl http://<监控机>:9091
# 先用短阶梯试一遍
.\experiments\e-hot-stock\run.ps1 -Variant e2-buckets-8 -Set "experiment:ha.stepSec=20","experiment:ha.maxRps=150"

.PARAMETER Variant
e0-single-row | e1-buckets-4 | e2-buckets-8 | e3-buckets-16（对应同目录的 .properties）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.maxRps=600"、"script:buckets=32"，可以给多个
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

$experiment = 'e-hot-stock'
$inventoryUrl = 'http://localhost:8082'
$props = Get-HaVariantProps $experiment $Variant $Set
$hotSku = Get-HaScriptValue $props 'hotSku' '20001'
$buckets = [int](Get-HaScriptValue $props 'buckets' '0')
$restockTarget = [int](Get-HaScriptValue $props 'restockTarget' '300000')
$drainWaitSec = [int](Get-HaScriptValue $props 'drainWaitSec' '1800')
$drainIdleSec = [int](Get-HaScriptValue $props 'drainIdleSec' '120')
$mergeWaitSec = [int](Get-HaScriptValue $props 'mergeWaitSec' '120')
# 拆桶接口要求桶数在 2–100 之间
if ($buckets -ne 0 -and ($buckets -lt 2 -or $buckets -gt 100)) { throw "buckets 只能是 0（不拆）或 2–100，实际：$buckets" }

# 这一轮计划发多少单（预热 + 阶梯）：和 HotStockSimulation / StairSchedule 的算法一致，用来核对库存够不够
function Get-PlannedOrders($Exp) {
    function Get-Num([string]$Key, [double]$Default) { if ($Exp.Contains($Key)) { [double]$Exp[$Key] } else { $Default } }
    $warmup = (Get-Num 'ha.warmupSec' 30) * (Get-Num 'ha.warmupRps' 20)
    $mode = if ($Exp.Contains('ha.mode')) { $Exp['ha.mode'] } else { 'stair' }
    if ($mode -eq 'steady') { return [long][math]::Ceiling($warmup + (Get-Num 'ha.orderRps' 200) * (Get-Num 'ha.durationSec' 300)) }
    $rps = Get-Num 'ha.startRps' 50; $step = Get-Num 'ha.stepRps' 50; $sec = Get-Num 'ha.stepSec' 120; $max = Get-Num 'ha.maxRps' 400
    if ($rps -le 0 -or $step -le 0 -or $sec -le 0) { throw 'ha.startRps、ha.stepRps、ha.stepSec 都必须大于 0' }
    $total = $warmup
    while ($true) {
        $total += $rps * $sec
        if ($rps -ge $max) { break }
        $rps = [math]::Min($rps + $step, $max)
    }
    return [long][math]::Ceiling($total)
}

# 这一轮的状态：压测前的库存（收尾时等它回来）、有没有拆过桶（拆过才需要并回去）
$state = @{ Baseline = $null; Split = $false }

# 服务就绪、公共脚本补完库存之后，压测开始之前：并回上一轮的桶 → 补货 → 核对库存够用 → 按变体拆桶
$beforeLoad = {
    param($ctx)
    # 上一轮中途被打断、没走到收尾时，SKU 可能还是分桶状态（这时公共脚本的补货不生效：补货接口不处理分桶的 SKU）。
    # 先直接并一次；并不回去就按最近几轮的 mall-app 日志把卡住的预占释放掉（上一轮收尾本该做的事），再反复尝试——
    # mall-app 起来后会把积压的超时关单消息消费掉，预占释放完才并得回去，所以可能要等
    if (-not (Invoke-HaBucketMerge $hotSku)) {
        $previous = @(Get-ChildItem (Split-Path $ctx.RunDir -Parent) -Directory | Where-Object { $_.FullName -ne $ctx.RunDir } |
            Sort-Object Name | Select-Object -Last 3)
        $n = 0
        foreach ($dir in $previous) { $n += Invoke-HaStuckRelease (Join-Path $dir.FullName 'logs') @($hotSku) }
        Write-HaEvent $ctx.Events 'script' "SKU $hotSku 还是之前留下的分桶状态；已按最近 $($previous.Count) 轮的 mall-app 日志对 $n 个订单调了 release，接着尝试合并"
        if (-not (Wait-HaBucketMerge $hotSku $drainWaitSec)) {
            throw "SKU $hotSku 还是之前留下的分桶状态，等了 $drainWaitSec 秒也并不回去（各桶还有未释放的预占）。让服务多跑一会儿把积压的关单处理完再试"
        }
    }
    Invoke-HaRestock @($hotSku) $restockTarget
    $stock = Get-HaStock $hotSku
    $planned = Get-PlannedOrders (Get-HaSection $ctx.Props 'experiment')
    if ($stock -lt $planned) {
        throw "SKU $hotSku 只有 $stock 件，这一轮计划下 $planned 单：把 [script] restockTarget 调到 $planned 以上（上限 100 万）"
    }
    $state.Baseline = $stock
    if ($buckets -eq 0) {
        Write-HaEvent $ctx.Events 'script' "SKU $hotSku 不分桶（单行扣减），库存 $stock，计划 $planned 单"
        return
    }
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$inventoryUrl/internal/inventory/buckets/$hotSku/split?count=$buckets" -TimeoutSec 15 | Out-Null
    $state.Split = $true
    # 核对真的拆了：库存服务拆桶成功会打一行日志（桶数、搬进桶里的库存）；拆完各桶之和应当等于拆之前的库存
    $logFile = Join-Path $ctx.RunDir 'logs\inventory-service.log'
    $pattern = "SKU $hotSku 拆成 $buckets 个桶"
    $line = $null
    for ($i = 0; $i -lt 20 -and -not $line; $i++) {
        $line = Select-String -LiteralPath $logFile -Pattern $pattern -SimpleMatch -Encoding UTF8 | Select-Object -Last 1
        if (-not $line) { Start-Sleep -Milliseconds 500 }
    }
    if (-not $line) { throw "拆桶接口返回成功，但库存服务日志里没有「$pattern」，没法确认拆桶生效" }
    $after = Get-HaStock $hotSku
    if ($after -ne $stock) { throw "拆桶前库存 $stock，拆完各桶之和是 $after，对不上" }
    $perBucket = [math]::Floor($stock / $buckets)
    $logged = $line.Line.Substring($line.Line.IndexOf($pattern)).Trim()   # 只留消息本身，不带时间、线程名
    Write-HaEvent $ctx.Events 'script' "SKU $hotSku 已拆成 $buckets 个桶（每桶约 $perBucket 件，合计 $after），计划 $planned 单；库存服务日志：$logged"
}

# 压测期间：到点抓 mall-app 与库存服务的线程栈，看 Tomcat 线程是在等数据库（行锁）、等下游响应还是空闲
$during = {
    param($ctx)
    $spec = Get-HaScriptValue $ctx.Props 'threadDumpAtSec' ''
    $times = @($spec -split ',' | Where-Object { $_.Trim() } | ForEach-Object { [int]$_.Trim() } | Sort-Object)
    foreach ($t in $times) {
        while (-not $ctx.Gatling.HasExited -and (Get-EpochMs) -lt ($ctx.StartedMs + $t * 1000)) { Start-Sleep -Milliseconds 500 }
        if ($ctx.Gatling.HasExited) { return }   # 用 -Set 缩短了阶梯：到不了这个时刻，不抓
        foreach ($svc in @($ctx.Topology.Apps[0], $ctx.Topology.Inventory)) {
            Save-HaThreadDump $svc (Join-Path $ctx.RunDir ("threads-{0}-t{1}s.txt" -f $svc.Name, $t)) $ctx.Java
        }
        Write-HaEvent $ctx.Events 'script' "第 $t 秒抓了线程栈"
    }
}

# 收尾（停服务之前；中途出错也会执行）：释放卡住的预占，等实验订单关完、库存还回来，再把桶并回去
$cleanup = {
    param($ctx)
    if (-not $ctx.Topology.Inventory -or $ctx.Topology.Inventory.Process.HasExited) { return }
    if ($null -ne $state.Baseline) {
        $n = Invoke-HaStuckRelease (Join-Path $ctx.RunDir 'logs') @($hotSku)
        if ($n -gt 0) {
            Write-HaEvent $ctx.Events 'script' "mall-app 日志里有 $n 个订单「释放预占失败，交给过期回查」（下单失败后 release 也失败），已逐个调 release"
        }
        Wait-HaStockRestored -Skus @($hotSku) -Baseline $state.Baseline -TimeoutSec $drainWaitSec -IdleSec $drainIdleSec -EventsFile $ctx.Events | Out-Null
    }
    if (-not $state.Split) { return }
    if (Wait-HaBucketMerge $hotSku $mergeWaitSec) {
        Write-HaEvent $ctx.Events 'script' "SKU $hotSku 的 $buckets 个桶已并回单行，库存 $(Get-HaStock $hotSku)"
    } else {
        Write-HaEvent $ctx.Events 'script' "SKU $hotSku 没能并回单行（各桶还有未释放的预占），仍是 $buckets 个桶；下一轮开始时会再试，跑别的实验之前要先确认它已经并回去"
    }
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'HotStockSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -Skus @($hotSku) -RestockTarget $restockTarget -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup `
    -Build:$Build -KeepServices:$KeepServices
