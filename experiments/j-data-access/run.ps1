<#
.SYNOPSIS
实验 J：数据访问——偏移量分页与游标、N+1 与一次查完、逐条插入与批量，在同一份数据上各差多少。方案见 experiments/README.md「实验 J」。

.EXAMPLE
.\experiments\j-data-access\run.ps1 -Variant j2-deep-paging
.\experiments\j-data-access\run.ps1 -Variant j3-n-plus-one -PrometheusUrl http://<监控机>:9091
# 批量写先用小数据量试一遍
.\experiments\j-data-access\run.ps1 -Variant j1-batch-write -Set "experiment:ha.batchRows=2000","experiment:ha.batchRepeat=1"

.PARAMETER Variant
j1-batch-write | j1-batch-write-rewrite（批量写，驱动不改写 / 改写批量语句）；j2-deep-paging（深分页）；j3-n-plus-one（带明细的列表）
.PARAMETER Set
临时覆盖参数，形如 "experiment:ha.depths=0,1000"、"experiment:ha.users=8"，可以给多个
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

$experiment = 'j-data-access'

# 压测开始之前：确认种子订单在（第一次要灌 100 万单，几分钟）
$beforeLoad = {
    param($ctx)
    Confirm-HaSeedOrders
}

# 压测一开始把 MySQL 的语句统计清零（灌数据、启动时的语句不算），收尾时取回：每种语句执行了多少次、平均多久、扫描了多少行
$during = {
    param($ctx)
    Invoke-HaVm 'reset' | Out-Null
}

$cleanup = {
    param($ctx)
    $digest = Invoke-HaVm 'digest'
    if ($digest.Count -gt 0) {
        @("schema`tcount`ttotal_ms`tavg_us`tmax_ms`trows_examined`trows_sent`trows_affected`tstatement") + $digest |
            Out-File (Join-Path $ctx.RunDir 'vm-mysql-digest.txt') -Encoding utf8
    }
}

Invoke-HaExperiment -Experiment $experiment -Variant $Variant -Simulation 'DataAccessSimulation' `
    -Set $Set -JavaHome $JavaHome -PrometheusUrl $PrometheusUrl -NacosNamespace $NacosNamespace `
    -BeforeLoad $beforeLoad -During $during -Cleanup $cleanup `
    -SamplerExtraPattern 'hikaricp_connections_(usage|acquire)_seconds_(sum|count|max)|jvm_gc_pause_seconds_(sum|count|max)|jvm_gc_memory_allocated_bytes_total' `
    -Build:$Build -KeepServices:$KeepServices
