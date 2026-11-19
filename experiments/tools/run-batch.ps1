<#
.SYNOPSIS
按顺序跑一串实验，无人值守用。每轮一个子进程，失败不中断，跑完在日志末尾写「全部结束」。

.DESCRIPTION
每一项的格式是「实验目录|变体|可选的 -Set 项（分号分隔）」，例如：
  .\experiments\tools\run-batch.ps1 -Tag night1 -PrometheusUrl http://<监控机>:9091 -VmSsh root@<虚拟机> -Runs @(
      'e-hot-stock|e0-single-row',
      'd-overload|d2-limit|mall-gateway:mall.gateway.route-limits.anonymous.permits-per-second=300;mall-gateway:mall.gateway.route-limits.anonymous.burst=300')

中间件地址照常由 config/application-local.yml 或环境变量（MALL_DB_HOST 等）给，本脚本不管。

给了 -VmSsh（能免密 ssh 上去、跑着 MySQL 容器的那台 Linux 虚拟机）时，每轮额外做三件事，用来发现「环境自己卡了」：
  1. 把 ha-vm.sh 传过去，在虚拟机里起一个磁盘探针（每 0.2 秒一次同步写），结果存到本轮目录的 vm-probe.csv
  2. 实验前后各取一次 MySQL 的行锁等计数，存到 vm-mysql-before.txt / vm-mysql-after.txt
  3. 结束后取本轮 COMMIT 的最大耗时，超过 -CommitStallMs 就在本轮目录写 CONTAMINATED.txt，并重跑一次
给了 -PrometheusUrl 时还会检查网关指标的采样间隔，间隔明显变大说明 Prometheus 所在的机器整机停顿过。

几条教训（都是踩过的）：
  - 子进程用 -EncodedCommand 调，-Set 才能传成数组；用 -File 的话「a,b」会被当成一个字符串
  - 子进程的输出写文件，不接管道：服务进程会继承管道句柄，脚本出错退出后管道不关，这里会一直挂着
  - 每轮有超时；上一轮留下的进程在下一轮开始前清掉
#>
param(
    [Parameter(Mandatory = $true)][string[]]$Runs,
    [string]$Tag = 'batch',
    [string]$PrometheusUrl = '',
    [string]$VmSsh = '',
    [int]$TimeoutMin = 30,
    [int]$CommitStallMs = 2000,
    [int]$MaxAttempts = 2
)

$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$log = Join-Path $repo "experiments\out\driver-$Tag.log"
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
Set-Location $repo

function Log([string]$m) { $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; $line; Add-Content -Path $log -Value $line -Encoding utf8 }
function NowSec { [int][DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
function FreeMb { [int](Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue }
function Vm([string]$cmd) { if ($VmSsh) { & ssh -o BatchMode=yes -o ConnectTimeout=10 $VmSsh "sh /tmp/ha-vm.sh $cmd" 2>$null } }

# 这一轮里网关指标的最大采样间隔（秒）
function MaxScrapeGap([int]$fromSec, [int]$toSec) {
    if (-not $PrometheusUrl) { return -1 }
    try {
        $range = $toSec - $fromSec
        $q = [uri]::EscapeDataString("up{app=`"mall-gateway`"}[${range}s]")
        $r = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/query?query=$q&time=$toSec" -TimeoutSec 10
        $ts = @($r.data.result[0].values | ForEach-Object { [double]$_[0] })
        $max = 0; for ($i = 1; $i -lt $ts.Count; $i++) { $g = $ts[$i] - $ts[$i - 1]; if ($g -gt $max) { $max = $g } }
        return [math]::Round($max, 1)
    } catch { return -1 }
}

if ($VmSsh) {
    # 脚本内容走标准输入传过去，换行统一成 LF
    $sh = (Get-Content (Join-Path $PSScriptRoot 'ha-vm.sh') -Raw -Encoding utf8) -replace "`r`n", "`n"
    $sh | & ssh -o BatchMode=yes -o ConnectTimeout=10 $VmSsh 'cat > /tmp/ha-vm.sh' 2>$null
}

$ports = 8080, 8180, 8280, 8082, 8083, 9000, 18080, 18180, 18280, 18082, 18083, 19000   # 服务端口与管理端口：上一轮的进程没退干净时，占着的可能只剩管理端口
foreach ($r in $Runs) {
    $parts = $r -split '\|'
    $exp = $parts[0]; $variant = $parts[1]
    $sets = @(); if ($parts.Count -gt 2 -and $parts[2]) { $sets = $parts[2] -split ';' }
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $left = Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($p in $left) { Log "清理残留进程 $p"; Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
        if ($left) { Start-Sleep 5 }
        Vm 'reset' | Out-Null
        $before = Vm 'status'
        Vm 'probe-start' | Out-Null
        Log "开始 $exp / $variant $($sets -join ' ')（第 $attempt 次，本机可用内存 $(FreeMb)MB）"
        $t0 = NowSec
        $sw = [Diagnostics.Stopwatch]::StartNew()

        $call = "& '.\experiments\$exp\run.ps1' -Variant '$variant'"
        if ($PrometheusUrl) { $call += " -PrometheusUrl '$PrometheusUrl'" }
        if ($sets.Count) { $call += ' -Set ' + (($sets | ForEach-Object { "'$_'" }) -join ',') }
        $cmd = "try { $call; exit 0 } catch { `$_ | Out-String; exit 1 }"
        $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
        $tmpOut = Join-Path $env:TEMP "ha-run-$Tag.out.log"; $tmpErr = Join-Path $env:TEMP "ha-run-$Tag.err.log"
        $child = Start-Process powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $enc) `
            -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
        $null = $child.Handle
        if ($child.WaitForExit($TimeoutMin * 60 * 1000)) { $code = $child.ExitCode } else {
            Log "这一轮超过 $TimeoutMin 分钟没结束，强制结束"
            & taskkill /F /T /PID $child.Id 2>$null | Out-Null
            $code = -1
        }
        Get-Content $tmpOut, $tmpErr -Encoding Default -ErrorAction SilentlyContinue | Add-Content -Path $log -Encoding utf8   # 子进程按系统代码页写的
        $t1 = NowSec

        Vm 'probe-stop' | Out-Null
        $runDir = Get-ChildItem (Join-Path $repo "experiments\out\$exp") -Directory -Filter "*-$variant" -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
        $commit = 0
        if ($VmSsh) {
            $commit = [double](Vm 'max' | Select-Object -First 1)
            if ($runDir) {
                $before | Out-File (Join-Path $runDir.FullName 'vm-mysql-before.txt') -Encoding utf8
                Vm 'status' | Out-File (Join-Path $runDir.FullName 'vm-mysql-after.txt') -Encoding utf8
                @('epoch_s,fsync_ms,cpu_busy_pct,cpu_iowait_pct') + @(Vm 'probe-cat') | Out-File (Join-Path $runDir.FullName 'vm-probe.csv') -Encoding utf8
            }
        }
        # 压测真正开始之后才算采样间隔（服务启动阶段网关还没起来，采样本来就是空的）
        $from = $t0
        if ($runDir -and (Test-Path (Join-Path $runDir.FullName 'started.txt'))) { $from = [int]([long](Get-Content (Join-Path $runDir.FullName 'started.txt') -Raw).Trim() / 1000) }
        $gap = MaxScrapeGap $from ($t1 - 15)
        Log "结束 $exp / $variant：退出码 $code，用时 $([int]$sw.Elapsed.TotalSeconds) 秒；最大采样间隔 ${gap}s，COMMIT 最大 ${commit}ms，结束时本机可用内存 $(FreeMb)MB；目录 $($runDir.Name)"
        $stalled = ($gap -gt 9) -or ($commit -gt $CommitStallMs)
        if ($stalled -and $runDir) {
            "环境在这一轮里停顿过：最大采样间隔 ${gap}s，COMMIT 最大 ${commit}ms。停顿的时刻见 vm-probe.csv，分析时剔除或整轮不用。" | Out-File (Join-Path $runDir.FullName 'CONTAMINATED.txt') -Encoding utf8
            Log '这一轮里环境停顿过（已标记 CONTAMINATED.txt）'
        }
        Start-Sleep 20   # 让注册中心清掉上一轮的实例、关单任务把库存还回去
        if (-not $stalled -and $code -eq 0) { break }
        if ($attempt -lt $MaxAttempts) { Log '重跑一次' }
    }
}
Log '全部结束'
