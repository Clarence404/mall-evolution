<#
.SYNOPSIS
每隔 IntervalMs 毫秒抓一次各服务管理端口的 /actuator/prometheus，只留实验关心的指标，追加写入 CSV。

.DESCRIPTION
输出是长表：epoch_ms,target,series,value。series 是 Prometheus 文本格式里的「指标名{标签}」原样，计数器（*_total、*_count）
是累计值，算每秒速率时取相邻两行相减。由 HaExperiment.psm1 的 Start-HaSampler 以独立进程启动，压测结束后被结束掉；
也可以手工跑：
  .\experiments\tools\Sample-Metrics.ps1 -Targets "mall-app-1=http://localhost:18080,inventory-service=http://localhost:18082" -OutFile metrics.csv

不保留直方图的 _bucket 行（量大，P99 用 Gatling 的数据或 Prometheus 算）。
#>
param(
    [Parameter(Mandatory = $true)][string]$Targets,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [int]$IntervalMs = 1000,
    [string]$Pattern = '^(http_server_requests_seconds_(count|sum|max)|http_client_requests_seconds_(count|sum|max)|tomcat_threads_|hikaricp_connections(_active|_pending|_idle|_max)?\{|resilience4j_circuitbreaker_(state|not_permitted_calls_total|failure_rate|slow_call_rate|calls_seconds_count)|resilience4j_bulkhead_(available|max_allowed)|demo_fault_|remote_retryable_|gateway_route_limited|spring_cloud_gateway_requests_seconds_count|process_cpu_usage|system_cpu_usage|jvm_threads_live_threads)'
)

$ErrorActionPreference = 'Continue'
$targetList = @()
foreach ($pair in ($Targets -split ',')) {
    $kv = $pair -split '=', 2
    $targetList += [pscustomobject]@{ Name = $kv[0].Trim(); Url = ($kv[1].Trim().TrimEnd('/') + '/actuator/prometheus') }
}
$regex = New-Object System.Text.RegularExpressions.Regex($Pattern, [System.Text.RegularExpressions.RegexOptions]::Compiled)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($OutFile, $true, $utf8)
$writer.AutoFlush = $true
if ((Get-Item $OutFile).Length -eq 0) { $writer.WriteLine('epoch_ms,target,series,value') }
$client = New-Object System.Net.WebClient
$client.Encoding = $utf8

try {
    while ($true) {
        $tick = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        foreach ($t in $targetList) {
            try {
                $text = $client.DownloadString($t.Url)
            } catch {
                $writer.WriteLine("$tick,$($t.Name),scrape_error,1") # 抓不到（实例正在重启、已退出）也记一笔，时间轴上看得出来
                continue
            }
            foreach ($line in ($text -split "`n")) {
                if ($line.Length -eq 0 -or $line[0] -eq '#' -or -not $regex.IsMatch($line)) { continue }
                $cut = $line.LastIndexOf(' ')
                if ($cut -lt 0) { continue }
                $series = $line.Substring(0, $cut).Replace('"', '""')
                $value = $line.Substring($cut + 1).Trim()
                $writer.WriteLine("$tick,$($t.Name),`"$series`",$value")
            }
        }
        $sleep = $IntervalMs - ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $tick)
        if ($sleep -gt 0) { Start-Sleep -Milliseconds $sleep }
    }
} finally {
    $writer.Dispose()
}
