# 高可用实验的公共函数（Windows PowerShell 5.1 / PowerShell 7 都能跑）。各实验目录下的 run.ps1 导入它。
# 约定：
#   - 四个服务用已经打好的 jar（*/target/*.jar），profile 固定 dev,local,demo，工作目录是仓库根目录（这样 config/application-local.yml 才读得到）
#   - 实验参数写在 .properties 里：[all] / [mall-app] / [inventory-service] / [payment-service] / [mall-gateway] 是服务的启动参数，
#     [experiment] 是传给 Gatling 的 -D 参数，[script] 是脚本自己用的开关；全部以命令行参数传入，优先级最高，Nacos 里同名配置也盖不住它
#     [jvm] 是各服务的 JVM 参数（key 是服务名，如 mall-app=-Xms1g -Xmx1g -XX:+UseZGC），不写就是 -Xms512m -Xmx1g；
#     值里的 {run} 换成本轮结果目录（相对仓库根目录，不带盘符——-Xlog 的 file= 遇到冒号要另外加引号）、{name} 换成实例名
#   - 每一轮的产物都放在 experiments/out/<实验>/<时间>-<变体>/（已在 .gitignore 里）

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:ExperimentsRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# 服务名 → jar 相对路径
$script:Jars = @{
    'mall-app'          = 'mall-app\target\mall-app.jar'
    'inventory-service' = 'inventory-service\target\inventory-service.jar'
    'payment-service'   = 'payment-service\target\payment-service.jar'
    'mall-gateway'      = 'mall-gateway\target\mall-gateway.jar'
}

function Get-HaRepoRoot { $script:RepoRoot }

function Write-HaLog([string]$Message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message)
}

function Get-EpochMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }

# ---------------------------------------------------------------- 参数文件

<#
读一组 .properties（后面的覆盖前面的），返回 节名 → 有序字典。
格式：# 开头是注释；[节名]；key=value（值里的「 #」及其后面视为注释）。Overrides 形如 "mall-app:key=value"。
#>
function Read-HaProperties {
    param([string[]]$Files, [string[]]$Overrides = @())
    $result = @{}
    foreach ($f in $Files) {
        if (-not (Test-Path $f)) { continue }
        $section = 'all'
        foreach ($raw in (Get-Content -LiteralPath $f -Encoding UTF8)) {
            $line = $raw.Trim()
            if ($line -eq '' -or $line.StartsWith('#')) { continue }
            if ($line -match '^\[(.+)\]$') { $section = $Matches[1].Trim(); continue }
            $idx = $line.IndexOf('=')
            if ($idx -lt 1) { throw "参数文件 $f 里这一行看不懂：$raw" }
            $key = $line.Substring(0, $idx).Trim()
            $value = $line.Substring($idx + 1)
            $hash = $value.IndexOf(' #')
            if ($hash -ge 0) { $value = $value.Substring(0, $hash) }
            Set-HaProp $result $section $key $value.Trim()
        }
    }
    foreach ($o in $Overrides) {
        if ($o -notmatch '^([^:]+):([^=]+)=(.*)$') { throw "-Set 的格式是 节名:key=value，实际：$o" }
        Set-HaProp $result $Matches[1].Trim() $Matches[2].Trim() $Matches[3].Trim()
    }
    return $result
}

function Set-HaProp($Props, [string]$Section, [string]$Key, [string]$Value) {
    if (-not $Props.ContainsKey($Section)) { $Props[$Section] = [ordered]@{} }
    $Props[$Section][$Key] = $Value
}

function Get-HaSection($Props, [string]$Section) {
    if ($Props.ContainsKey($Section)) { return $Props[$Section] }
    return [ordered]@{}
}

function Get-HaScriptValue($Props, [string]$Key, $Default) {
    $s = Get-HaSection $Props 'script'
    if ($s.Contains($Key)) { return $s[$Key] }
    return $Default
}

# 某个服务最终的启动参数：[all] 打底，[服务名] 覆盖
function Get-HaServiceArgs($Props, [string]$Module) {
    $merged = [ordered]@{}
    foreach ($sec in @('all', $Module)) {
        $s = Get-HaSection $Props $sec
        foreach ($k in $s.Keys) { $merged[$k] = $s[$k] }
    }
    return $merged
}

# 某个服务实例的 JVM 参数：[jvm] 节里按服务名取，没有就用默认的堆大小
function Get-HaJvmOpts($Props, [string]$Module, [string]$Name, [string]$RunDir) {
    $s = Get-HaSection $Props 'jvm'
    $opts = '-Xms512m -Xmx1g'
    if ($s.Contains($Module)) { $opts = $s[$Module] }
    $rel = $RunDir
    if ($RunDir -and $RunDir.StartsWith($script:RepoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        $rel = $RunDir.Substring($script:RepoRoot.Length).TrimStart([char]92)
    }
    return $opts.Replace('{run}', $rel).Replace('{name}', $Name)
}

# 把整套参数写进结果目录，方便事后核对这一轮到底是什么配置
function Save-HaProperties($Props, [string]$File) {
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($sec in ($Props.Keys | Sort-Object)) {
        $lines.Add("[$sec]")
        foreach ($k in $Props[$sec].Keys) { $lines.Add("$k=$($Props[$sec][$k])") }
        $lines.Add('')
    }
    [System.IO.File]::WriteAllLines($File, $lines, (New-Object System.Text.UTF8Encoding($false)))
}

<#
读某个实验某个变体的整套参数：公共的 common.properties → 实验目录的 common.properties → 变体文件 → -Set。
Invoke-HaExperiment 用它；run.ps1 要在起服务之前就用到参数时（起几个实例、补多少库存）也调它。
#>
function Get-HaVariantProps([string]$Experiment, [string]$Variant, [string[]]$Set = @()) {
    $expDir = Join-Path $script:ExperimentsRoot $Experiment
    $variantFile = Join-Path $expDir "$Variant.properties"
    if (-not (Test-Path $variantFile)) {
        $known = (Get-ChildItem $expDir -Filter '*.properties' | Where-Object { $_.Name -ne 'common.properties' } | ForEach-Object { $_.BaseName }) -join ', '
        throw "没有变体 $Variant，可选：$known"
    }
    return Read-HaProperties -Files @((Join-Path $script:ExperimentsRoot 'common.properties'), (Join-Path $expDir 'common.properties'), $variantFile) -Overrides $Set
}

# ---------------------------------------------------------------- 环境

function Resolve-HaJava([string]$JavaHome) {
    if (-not $JavaHome) { $JavaHome = $env:MALL_JAVA_HOME }
    if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (-not $JavaHome) { throw '找不到 JDK：用 -JavaHome 指定 JDK 21 的目录，或设置环境变量 MALL_JAVA_HOME / JAVA_HOME' }
    $java = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path $java)) { throw "$java 不存在" }
    $ver = (& cmd /c "`"$java`" -version 2>&1") -join ' '
    if ($ver -notmatch 'version "(\d+)') { throw "看不出 JDK 版本：$ver" }
    if ([int]$Matches[1] -lt 21) { throw "需要 JDK 21+，$JavaHome 是 $($Matches[1])" }
    return [pscustomobject]@{ Home = $JavaHome; Java = $java; Jcmd = (Join-Path $JavaHome 'bin\jcmd.exe') }
}

# dev 环境的本地占位 JWT 密钥：config/application-local.yml 里改过就用它，否则用 mall-app 的 application-dev.yml 里那串
function Get-HaJwtSecret {
    $candidates = @(
        (Join-Path $script:RepoRoot 'config\application-local.yml'),
        (Join-Path $script:RepoRoot 'mall-app\src\main\resources\application-dev.yml'))
    foreach ($f in $candidates) {
        if (-not (Test-Path $f)) { continue }
        $inJwt = $false
        foreach ($line in (Get-Content -LiteralPath $f -Encoding UTF8)) {
            if ($line -match '^\s*jwt:\s*$') { $inJwt = $true; continue }
            if ($inJwt -and $line -match '^\s*secret:\s*([^\s#]+)') { return $Matches[1] }
        }
    }
    throw '找不到 JWT 密钥（mall.security.jwt.secret）'
}

function Test-HaPortListening([int]$Port) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $c.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(300)) { return $false }
        $c.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $c.Close()
    }
}

function Assert-HaPortsFree([int[]]$Ports) {
    $busy = @($Ports | Where-Object { Test-HaPortListening $_ })
    if ($busy.Count -gt 0) {
        throw "这些端口已经有进程在监听：$($busy -join ', ')。实验要自己起服务，先停掉手工起的那些（IDEA 里的运行配置、java -jar）"
    }
}

function Assert-HaJars([switch]$Build, $Java) {
    $missing = @($script:Jars.Values | Where-Object { -not (Test-Path (Join-Path $script:RepoRoot $_)) })
    if ($Build -or $missing.Count -gt 0) {
        Write-HaLog '打包四个服务（跳过测试）……'
        $env:JAVA_HOME = $Java.Home
        Push-Location $script:RepoRoot
        try {
            & .\mvnw.cmd -B -q -DskipTests package -pl mall-app,inventory-service,payment-service,mall-gateway -am
            if ($LASTEXITCODE -ne 0) { throw 'Maven 打包失败' }
        } finally {
            Pop-Location
        }
    }
}

# ---------------------------------------------------------------- 服务

<#
起一个服务进程。返回对象带 Name、Module、Port、MgmtPort、Process、Args。
#>
function Start-HaService {
    param(
        [string]$Name, [string]$Module, [int]$Port, [int]$MgmtPort,
        $Props, [hashtable]$Extra = @{}, [string]$LogDir, $Java, [string]$JvmOpts = '-Xms512m -Xmx1g')
    $jar = Join-Path $script:RepoRoot $script:Jars[$Module]
    # [script] jarDir=<相对仓库根目录的目录>：改用那个目录下的 <服务名>.jar（对比某次修改前后时，把修改前打的包留一份在那里）
    $jarDir = Get-HaScriptValue $Props 'jarDir' ''
    if ($jarDir) {
        $jar = Join-Path (Join-Path $script:RepoRoot $jarDir) "$Module.jar"
        if (-not (Test-Path $jar)) { throw "jarDir 指的目录里没有 $Module.jar：$jar" }
    }
    $argList = New-Object System.Collections.Generic.List[string]
    foreach ($o in ($JvmOpts -split '\s+' | Where-Object { $_ })) { $argList.Add($o) }
    $argList.Add('--enable-native-access=ALL-UNNAMED')
    $argList.Add('-jar'); $argList.Add("`"$jar`"")
    $argList.Add('--spring.profiles.active=dev,local,demo')
    $argList.Add("--server.port=$Port")
    $argList.Add("--management.server.port=$MgmtPort")
    $all = Get-HaServiceArgs $Props $Module
    foreach ($k in $Extra.Keys) { $all[$k] = $Extra[$k] }
    foreach ($k in $all.Keys) { $argList.Add("--$k=$($all[$k])") }
    $out = Join-Path $LogDir "$Name.log"
    $err = Join-Path $LogDir "$Name.err.log"
    $p = Start-Process -FilePath $Java.Java -ArgumentList $argList -WorkingDirectory $script:RepoRoot `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow
    Write-HaLog "启动 $Name（pid $($p.Id)，端口 $Port / 管理端口 $MgmtPort）"
    return [pscustomobject]@{
        Name = $Name; Module = $Module; Port = $Port; MgmtPort = $MgmtPort; Process = $p
        Args = $all; Extra = $Extra; LogDir = $LogDir; Java = $Java; JvmOpts = $JvmOpts
    }
}

# 用同样的参数再起一次（滚动发布时用）
function Restart-HaServiceProcess($Svc, $Props, [string]$Suffix) {
    $name = "$($Svc.Name)$Suffix"
    $new = Start-HaService -Name $name -Module $Svc.Module -Port $Svc.Port -MgmtPort $Svc.MgmtPort -Props $Props `
        -Extra $Svc.Extra -LogDir $Svc.LogDir -Java $Svc.Java -JvmOpts $Svc.JvmOpts
    $new.Name = $Svc.Name
    return $new
}

function Wait-HaReady($Svc, [int]$TimeoutSec = 240) {
    $url = "http://localhost:$($Svc.MgmtPort)/actuator/health/readiness"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($Svc.Process.HasExited) { throw "$($Svc.Name) 启动失败，进程已退出，看日志 $($Svc.LogDir)\$($Svc.Name).log" }
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2
            if ($r.StatusCode -eq 200) { Write-HaLog "$($Svc.Name) 已就绪"; return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$($Svc.Name) 在 $TimeoutSec 秒内没有就绪"
}

function Wait-HaPort([int]$Port, [int]$TimeoutSec = 240) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-HaPortListening $Port) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "端口 $Port 在 $TimeoutSec 秒内没有开始监听"
}

<#
停服务。graceful：调 demo 的「模拟 SIGTERM」接口（JVM 正常退出、执行关闭钩子，等同 Linux 上的 kill <pid>）；
hard：Stop-Process -Force，等同 kill -9，进程当场消失。
#>
function Stop-HaService($Svc, [ValidateSet('graceful', 'hard')][string]$Mode = 'graceful', [int]$TimeoutSec = 90) {
    if ($Svc.Process.HasExited) { return }
    if ($Mode -eq 'graceful') {
        try {
            Invoke-RestMethod -Method Post -Uri "http://localhost:$($Svc.Port)/internal/demo/lifecycle/exit" -TimeoutSec 5 | Out-Null
            if ($Svc.Process.WaitForExit($TimeoutSec * 1000)) { return }
            Write-HaLog "$($Svc.Name) $TimeoutSec 秒内没有退出，强制结束"
        } catch {
            Write-HaLog "$($Svc.Name) 的退出接口调不通（$($_.Exception.Message)），强制结束"
        }
    }
    Stop-Process -Id $Svc.Process.Id -Force -ErrorAction SilentlyContinue
    $Svc.Process.WaitForExit(10000) | Out-Null
}

# 停机第一步：主动从 Nacos 注销（Kubernetes preStop 调的就是它）
function Invoke-HaOffline($Svc) {
    Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://localhost:$($Svc.Port)/internal/offline" -TimeoutSec 5 | Out-Null
}

<#
标准拓扑：库存、支付各 1 个，mall-app MallAppCount 个（端口 8080、8180、8280……，管理端口 +10000，雪花机器号 1、2、3……），网关 1 个。
按依赖顺序起：先库存与支付，再 mall-app，最后网关；都就绪才返回。
各服务的 JVM 参数取参数文件的 [jvm] 节（见 Get-HaJvmOpts）；AppJava 不为空时 mall-app 用它指的 JDK（对比 JDK 版本用），其余服务仍用 Java。
#>
function Start-HaTopology($Props, [string]$LogDir, $Java, [int]$MallAppCount = 1, $AppJava) {
    if (-not $AppJava) { $AppJava = $Java }
    $runDir = Split-Path $LogDir -Parent
    $jvm = { param($module, $name) Get-HaJvmOpts $Props $module $name $runDir }
    $ports = @(8082, 18082, 8083, 18083, 9000, 19000)
    for ($i = 0; $i -lt $MallAppCount; $i++) { $ports += @((8080 + 100 * $i), (18080 + 100 * $i)) }   # 逗号的优先级比 + 和 * 高，不加括号会被解析成「100 * 数组」
    Assert-HaPortsFree $ports
    $t = [ordered]@{ Apps = @(); Inventory = $null; Payment = $null; Gateway = $null }   # 先占好位：严格模式下读不存在的属性会报错，中途失败收尾时要用
    try {
        $t.Inventory = Start-HaService -Name 'inventory-service' -Module 'inventory-service' -Port 8082 -MgmtPort 18082 -Props $Props -LogDir $LogDir -Java $Java `
            -JvmOpts (& $jvm 'inventory-service' 'inventory-service')
        $t.Payment = Start-HaService -Name 'payment-service' -Module 'payment-service' -Port 8083 -MgmtPort 18083 -Props $Props -LogDir $LogDir -Java $Java `
            -JvmOpts (& $jvm 'payment-service' 'payment-service')
        Wait-HaReady $t.Inventory
        Wait-HaReady $t.Payment
        for ($i = 0; $i -lt $MallAppCount; $i++) {
            $extra = @{ 'mall.id.worker-id' = "$($i + 1)" }
            $t.Apps += Start-HaService -Name "mall-app-$($i + 1)" -Module 'mall-app' -Port (8080 + 100 * $i) -MgmtPort (18080 + 100 * $i) `
                -Props $Props -Extra $extra -LogDir $LogDir -Java $AppJava -JvmOpts (& $jvm 'mall-app' "mall-app-$($i + 1)")
            # [script] mallAppCpus=N：把 mall-app 绑在 N 个逻辑 CPU 上（0、2、4……号，隔一个取一个，避开同一个物理核的两个超线程），
            # 模拟「N 核的容器」。JVM 启动时就要按 N 核定 GC 线程数，所以 [jvm] 里要同时写 -XX:ActiveProcessorCount=N（绑核在进程起来之后才生效）
            $cpus = [int](Get-HaScriptValue $Props 'mallAppCpus' '0')
            if ($cpus -gt 0) {
                $mask = [long]0
                for ($c = 0; $c -lt $cpus; $c++) { $mask = $mask -bor ([long]1 -shl (2 * $c)) }
                $t.Apps[$i].Process.ProcessorAffinity = [IntPtr]$mask
                Write-HaLog ("mall-app-$($i + 1) 绑在 $cpus 个逻辑 CPU 上（掩码 0x{0:X}）" -f $mask)
            }
        }
        foreach ($a in $t.Apps) { Wait-HaReady $a }
        $t.Gateway = Start-HaService -Name 'mall-gateway' -Module 'mall-gateway' -Port 9000 -MgmtPort 19000 -Props $Props -LogDir $LogDir -Java $Java `
            -JvmOpts (& $jvm 'mall-gateway' 'mall-gateway')
        Wait-HaReady $t.Gateway
    } catch {
        # 中途有服务起不来：调用方还没拿到拓扑对象，没法收尾，这里把已经起来的先停掉，否则它们会一直占着端口
        Write-HaLog "启动失败，停掉已经起来的服务：$($_.Exception.Message)"
        Stop-HaTopology ([pscustomobject]$t)
        throw
    }
    Write-HaLog '等 10 秒，让网关从 Nacos 拿到全部实例'
    Start-Sleep -Seconds 10
    return [pscustomobject]$t
}

function Get-HaAllServices($Topology) {
    $list = @($Topology.Inventory, $Topology.Payment) + @($Topology.Apps) + @($Topology.Gateway)
    return @($list | Where-Object { $_ })
}

function Stop-HaTopology($Topology) {
    if (-not $Topology) { return }
    $all = Get-HaAllServices $Topology
    [array]::Reverse($all)
    foreach ($s in $all) {
        # 收尾直接结束进程：数据已经采完；优雅停机要逐个关 RocketMQ 消费者（每个约 30 秒，mall-app 实测 80–110 秒），每轮白等 3 分钟
        try { Stop-HaService $s 'hard' } catch { Write-HaLog "停 $($s.Name) 出错：$($_.Exception.Message)" }
    }
}

# 撤销所有服务上的故障规则（收尾用，出错时也要调）
function Clear-HaFaults($Topology) {
    if (-not $Topology) { return }
    foreach ($s in (Get-HaAllServices $Topology)) {
        if ($s.Module -eq 'mall-gateway' -or $s.Process.HasExited) { continue }
        try { Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "http://localhost:$($s.Port)/internal/demo/faults" -TimeoutSec 5 | Out-Null } catch { }
    }
}

# 补库存：每个 SKU 补到 Target（只补不减）
function Invoke-HaRestock([string[]]$Skus, [int]$Target = 100000) {
    foreach ($sku in $Skus) {
        Invoke-RestMethod -Method Put -Uri "http://localhost:8082/internal/demo/stocks/$sku/restock?target=$Target" -TimeoutSec 10 | Out-Null
    }
    Write-HaLog "已把 SKU $($Skus -join ',') 的库存补到 $Target"
}

# 查一个 SKU 的可售库存（分桶的 SKU 是各桶之和）
function Get-HaStock([string]$Sku) {
    $r = Invoke-RestMethod -Uri "http://localhost:8082/internal/demo/stocks?skuIds=$Sku" -TimeoutSec 10
    return [int]@($r)[0].stock
}

<#
把分桶的 SKU 并回单行（POST /internal/inventory/buckets/{skuId}/merge）。接口是幂等的：没分桶时直接返回成功。
各桶还有未释放的预占时库存服务拒绝合并（5xx），这里返回 $false。
#>
function Invoke-HaBucketMerge([string]$Sku) {
    try {
        Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://localhost:8082/internal/inventory/buckets/$Sku/merge" -TimeoutSec 15 | Out-Null
        return $true
    } catch {
        return $false
    }
}

# 反复尝试并回单行（每 10 秒一次），直到成功或等满 TimeoutSec 秒；返回是否并回去了
function Wait-HaBucketMerge([string]$Sku, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ($true) {
        if (Invoke-HaBucketMerge $Sku) { return $true }
        if ((Get-Date) -ge $deadline) { return $false }
        Write-HaLog "SKU $Sku 的分桶还有未释放的预占，暂时并不回去，10 秒后再试"
        Start-Sleep -Seconds 10
    }
}

<#
确认高性能实验的种子订单灌过了，没灌或没灌完就接着灌（mall-app 的 /internal/demo/perf/seed，幂等）。
默认 4000 个普通用户各 200 单、1 个大客户 20 万单，共 100 万单、200 万条明细；一轮 5000 单，开发机上约 1–2 秒。
数据说明见 mall-order 的 DemoPerfService。灌完的数据留在库里，后面各轮直接用；不想要了清空 t_order、t_order_item 即可。
#>
function Confirm-HaSeedOrders([int]$Users = 4000, [int]$Rounds = 200, [int]$HotPerRound = 1000, [int]$Port = 8080) {
    $base = "http://localhost:$Port/internal/demo/perf"
    $st = Invoke-RestMethod -Uri "$base/seed-status?users=$Users" -TimeoutSec 60
    if ($st.firstUserOrders -ge $Rounds -and $st.lastUserOrders -ge $Rounds -and $st.hotUserOrders -ge ($Rounds * $HotPerRound)) {
        Write-HaLog "种子订单已就绪（普通用户每人 $($st.firstUserOrders) 单，大客户 $($st.hotUserOrders) 单）"
        return
    }
    Write-HaLog "种子订单不全（第一个用户 $($st.firstUserOrders) 单、最后一个用户 $($st.lastUserOrders) 单、大客户 $($st.hotUserOrders) 单），开始灌 $Rounds 轮"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    for ($r = 0; $r -lt $Rounds; $r += 5) {
        $to = [math]::Min($Rounds, $r + 5)
        for ($try = 1; ; $try++) {
            try {
                Invoke-RestMethod -Method Post -Uri "$base/seed?fromRound=$r&toRound=$to&users=$Users&hotPerRound=$HotPerRound" -TimeoutSec 600 | Out-Null
                break
            } catch {
                # 开发库写得猛的时候偶尔会卡几秒，语句超时；接口是幂等的，歇一下重试
                if ($try -ge 5) { throw }
                Write-HaLog "种子订单第 $r–$($to - 1) 轮失败（$($_.Exception.Message)），10 秒后重试"
                Start-Sleep -Seconds 10
            }
        }
        if ($to % 50 -eq 0) { Write-HaLog "种子订单：已灌 $to / $Rounds 轮（$([int]$sw.Elapsed.TotalSeconds) 秒）" }
    }
    $st = Invoke-RestMethod -Uri "$base/seed-status?users=$Users" -TimeoutSec 60
    if ($st.hotUserOrders -lt ($Rounds * $HotPerRound)) { throw "种子订单没灌全：大客户只有 $($st.hotUserOrders) 单" }
    Write-HaLog "种子订单灌完，用时 $([int]$sw.Elapsed.TotalSeconds) 秒"
}

<#
释放「卡住」的预占。下单时预占的结果不明（读超时、5xx）mall-app 会立刻调 release；release 也失败的话只记一行日志
「释放预占失败，交给过期回查，orderNo=…」，这条预占要等库存服务的过期回查才会释放——预占 16 分钟后才过期，回查每 5 分钟一轮、
一轮 200 条。在那之前库存回不到压测前的数，分桶的 SKU 各桶 reserved 不为 0、也合并不了。
这里替过期回查提前做同一件事：从 LogDir 下各 mall-app 实例的日志里找出这些订单号，逐个调库存服务的 release（Skus 里每个 SKU 一行、数量 1）。
release 按 (订单号, SKU) 幂等：已释放的不会重复归还，没预占过的 SKU 只留下一行已释放的占位。返回处理了多少个订单号。
#>
function Invoke-HaStuckRelease([string]$LogDir, [string[]]$Skus) {
    if (-not (Test-Path $LogDir)) { return 0 }
    $orderNos = @(Get-ChildItem $LogDir -Filter 'mall-app-*.log' | Where-Object { $_.Name -notlike '*.err.log' } |
        Select-String -Pattern 'PlaceOrderService\s*:.*orderNo=(\d+)' -Encoding UTF8 |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique)
    $lines = ($Skus | ForEach-Object { '{"skuId":' + $_ + ',"quantity":1}' }) -join ','
    $failed = 0
    foreach ($orderNo in $orderNos) {
        $body = '{"orderNo":"' + $orderNo + '","lines":[' + $lines + ']}'
        try {
            Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://localhost:8082/internal/inventory/release' -ContentType 'application/json' -Body $body -TimeoutSec 10 | Out-Null
        } catch {
            $failed++
        }
    }
    if ($failed -gt 0) { Write-HaLog "释放卡住的预占：$failed 个订单号调 release 失败" }
    return $orderNos.Count
}

<#
等实验订单关完、库存还回来：每 5 秒查一次各 SKU 的可售库存之和，回到 Baseline 就返回 0；
连续 IdleSec 秒没有变化（说明关单已经停了，剩下的是卡住的预占）或等满 TimeoutSec 秒就放弃，返回还差多少件。
高并发实验一轮下十几万单，demo 下 1 分钟后陆续关单；不等它关完，积压的关单会带进下一轮，分桶的 SKU 也合并不回去。
#>
function Wait-HaStockRestored([string[]]$Skus, [long]$Baseline, [int]$TimeoutSec = 1800, [int]$IdleSec = 120, [string]$EventsFile) {
    $start = Get-Date
    $last = [long]-1; $lastChange = Get-Date; $lastLog = (Get-Date).AddSeconds(-60); $first = $null
    while ($true) {
        $now = [long]0
        foreach ($sku in $Skus) { $now += Get-HaStock $sku }
        if ($null -eq $first) { $first = $now }
        $waited = [int]((Get-Date) - $start).TotalSeconds
        $missing = $Baseline - $now
        if ($missing -le 0) {
            $msg = "库存已回到压测前的 $Baseline（等了 $waited 秒，开始等时还差 $($Baseline - $first) 件）"
            if ($EventsFile) { Write-HaEvent $EventsFile 'script' $msg } else { Write-HaLog $msg }
            return 0
        }
        if ($now -ne $last) { $last = $now; $lastChange = Get-Date }
        $idle = [int]((Get-Date) - $lastChange).TotalSeconds
        if ($waited -ge $TimeoutSec -or $idle -ge $IdleSec) {
            $why = if ($idle -ge $IdleSec) { "连续 $idle 秒没有变化" } else { "等满了 $TimeoutSec 秒" }
            $msg = "库存没有回到压测前的 $Baseline：还差 $missing 件，$why（等了 $waited 秒，开始等时还差 $($Baseline - $first) 件）"
            if ($EventsFile) { Write-HaEvent $EventsFile 'script' $msg } else { Write-HaLog $msg }
            return $missing
        }
        if (((Get-Date) - $lastLog).TotalSeconds -ge 30) {
            Write-HaLog "等实验订单关单、库存归还：还差 $missing 件（已等 $waited 秒）"
            $lastLog = Get-Date
        }
        Start-Sleep -Seconds 5
    }
}

# ---------------------------------------------------------------- 采样、压测、导出

<#
每秒抓一次各服务管理端口的 /actuator/prometheus，只留实验关心的指标，写成长表 CSV（epoch_ms,target,series,value）。
独立进程，压测结束后 Stop-HaSampler。Prometheus 只有 15 秒一个点，看「第几秒开始出错」要靠这份。
#>
function Start-HaSampler([System.Collections.IDictionary]$Targets, [string]$OutFile, [int]$IntervalMs = 1000, [string]$ExtraPattern) {
    $spec = ($Targets.Keys | ForEach-Object { "$_=$($Targets[$_])" }) -join ','
    $sampler = Join-Path $script:ExperimentsRoot 'tools\Sample-Metrics.ps1'
    $ps = (Get-Process -Id $PID).Path
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$sampler`"",
        '-Targets', "`"$spec`"", '-OutFile', "`"$OutFile`"", '-IntervalMs', "$IntervalMs")
    if ($ExtraPattern) { $argList += @('-Pattern', "`"$(Get-HaSamplerPattern $ExtraPattern)`"") }
    $p = Start-Process -FilePath $ps -ArgumentList $argList -PassThru -NoNewWindow
    Write-HaLog "指标采样已启动（pid $($p.Id)）→ $OutFile"
    return $p
}

<#
采样脚本默认只留各实验都要看的那批指标（Sample-Metrics.ps1 的 -Pattern 默认值）。个别实验要多留几个（如商品详情缓存的回源次数、
消息的消费延迟）时，在默认值后面接上自己的：默认的正则从采样脚本的参数定义里读出来，不在这里再抄一份。
Extra 是不带 ^ 的正则片段（多个用 | 隔开），匹配的是「指标名{标签}」的开头；不能含双引号（要原样放进命令行参数）。
#>
function Get-HaSamplerPattern([string]$Extra) {
    if ($Extra.Contains('"')) { throw "采样的附加正则不能含双引号：$Extra" }
    $sampler = Join-Path $script:ExperimentsRoot 'tools\Sample-Metrics.ps1'
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($sampler, [ref]$tokens, [ref]$errors)
    $param = $ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Pattern' }
    if (-not $param -or -not $param.DefaultValue) { throw "读不出 $sampler 的 -Pattern 默认值" }
    return "$($param.DefaultValue.Value)|^(?:$Extra)"
}

function Stop-HaSampler($Process) {
    Stop-HaProcessTree $Process
}

# 连同子进程一起结束（mvnw.cmd 底下还有 Maven 和 Gatling 的 JVM，只停 cmd 会留下孤儿进程）
function Stop-HaProcessTree($Process) {
    if ($Process -and -not $Process.HasExited) {
        & taskkill.exe /T /F /PID $Process.Id 2>&1 | Out-Null
    }
}

<#
在后台跑一个 Gatling 场景（perf 模块），返回进程。[experiment] 节的参数原样作为 -D 传进去。
结果：Gatling 的 HTML 报告在 <RunDir>\gatling\，逐秒数据 timeline.csv、events.csv、params.csv 在 <RunDir>\ 下。
#>
function Start-HaGatling([string]$Simulation, $Props, [hashtable]$SysProps, [string]$RunDir, $Java) {
    $argList = New-Object System.Collections.Generic.List[string]
    $argList.Add('-B'); $argList.Add('-pl'); $argList.Add('perf'); $argList.Add('gatling:test')
    $argList.Add("-Dgatling.simulationClass=io.github.clarence404.mall.perf.ha.$Simulation")
    $argList.Add("`"-Dgatling.resultsFolder=$RunDir\gatling`"")
    $argList.Add("`"-Dha.out=$RunDir`"")
    $exp = Get-HaSection $Props 'experiment'
    foreach ($k in $exp.Keys) { $argList.Add("`"-D$k=$($exp[$k])`"") }
    foreach ($k in $SysProps.Keys) { $argList.Add("`"-D$k=$($SysProps[$k])`"") }
    $env:JAVA_HOME = $Java.Home
    $mvnw = Join-Path $script:RepoRoot 'mvnw.cmd'
    $p = Start-Process -FilePath $mvnw -ArgumentList $argList -WorkingDirectory $script:RepoRoot `
        -RedirectStandardOutput (Join-Path $RunDir 'gatling.log') -RedirectStandardError (Join-Path $RunDir 'gatling.err.log') `
        -PassThru -NoNewWindow
    $null = $p.Handle   # 先取一次句柄，否则进程退出后 ExitCode 是空的（Start-Process -PassThru 的已知问题）
    Write-HaLog "压测已启动：$Simulation（pid $($p.Id)），日志 $RunDir\gatling.log"
    return $p
}

# 等压测真正开始注入流量（场景的 before() 写出 started.txt），返回开始时刻（epoch 毫秒）
function Wait-HaGatlingStarted([string]$RunDir, $GatlingProcess, [int]$TimeoutSec = 300) {
    $marker = Join-Path $RunDir 'started.txt'
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $marker) { return [long](Get-Content $marker -Raw).Trim() }
        if ($GatlingProcess.HasExited) { throw "压测进程提前退出，看 $RunDir\gatling.log" }
        Start-Sleep -Milliseconds 500
    }
    throw "压测在 $TimeoutSec 秒内没有开始"
}

# 等到压测开始后的第 Seconds 秒
function Wait-HaUntil([long]$StartedMs, [int]$Seconds) {
    $wait = $StartedMs + $Seconds * 1000 - (Get-EpochMs)
    if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
}

function Wait-HaGatling($GatlingProcess, [string]$RunDir) {
    $GatlingProcess.WaitForExit()
    $code = $GatlingProcess.ExitCode
    Write-HaLog "压测结束（退出码 $code）"
    if ($code -ne 0) { Write-HaLog "压测进程退出码非 0，看 $RunDir\gatling.log" }
    # gatling-maven-plugin 没有按 -Dgatling.resultsFolder 换目录，报告仍在 perf\target\gatling 下：把最新的一份搬进结果目录
    $dst = Join-Path $RunDir 'gatling'
    if (-not (Test-Path $dst)) {
        $src = Get-ChildItem (Join-Path $script:RepoRoot 'perf\target\gatling') -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -Last 1
        if ($src) { Move-Item $src.FullName $dst }
    }
}

# 实验过程中的事件（重启、注销……）：epoch_ms,source,event
function Write-HaEvent([string]$File, [string]$Source, [string]$Event) {
    $line = "{0},{1},{2}" -f (Get-EpochMs), $Source, $Event
    if (-not (Test-Path $File)) {
        [System.IO.File]::WriteAllLines($File, @('epoch_ms,source,event'), (New-Object System.Text.UTF8Encoding($false)))
    }
    [System.IO.File]::AppendAllLines($File, [string[]]@($line), (New-Object System.Text.UTF8Encoding($false)))
    Write-HaLog "$Source：$Event"
}

<#
线程栈：jcmd <pid> Thread.print，再把 Tomcat 业务线程（http-nio-<端口>-exec-*）按「卡在哪」分类计数，写成 *.summary.csv。
分类看的是栈顶附近的帧：空闲、等 HTTP 连接池、等下游响应、等本机缓存的单飞加载、等故障注入的许可、在 sleep（注入的延迟）。
#>
function Save-HaThreadDump($Svc, [string]$OutFile, $Java) {
    & $Java.Jcmd $Svc.Process.Id Thread.print 2>&1 | Out-File -FilePath $OutFile -Encoding utf8
    $summary = Get-HaThreadSummary $OutFile $Svc.Port
    $csv = [System.IO.Path]::ChangeExtension($OutFile, '.summary.csv')
    $lines = @('state,threads') + ($summary.Keys | ForEach-Object { "$_,$($summary[$_])" })
    [System.IO.File]::WriteAllLines($csv, $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-HaLog ("$($Svc.Name) 线程栈：" + (($summary.Keys | ForEach-Object { "$_=$($summary[$_])" }) -join '，'))
}

function Get-HaThreadSummary([string]$DumpFile, [int]$Port) {
    $text = Get-Content -LiteralPath $DumpFile -Raw -Encoding UTF8
    $blocks = $text -split '(?:\r?\n){2,}'
    $counts = [ordered]@{
        '等连接池' = 0; '等下游响应' = 0; '等本机缓存加载（单飞）' = 0; '等许可（舱壁 / 注入的并发上限）' = 0
        'sleep（注入的延迟）' = 0; '空闲' = 0; '其他' = 0
    }
    foreach ($b in $blocks) {
        if ($b -notmatch "`"http-nio-$Port-exec-\d+`"") { continue }
        $top = (($b -split '\r?\n') | Select-Object -Skip 1 -First 25) -join "`n"
        if ($top -match 'TaskQueue\.(take|poll)') { $counts['空闲']++ }
        elseif ($top -match 'PoolingHttpClientConnectionManager|StrictConnPool|LaxConnPool|leaseConnection') { $counts['等连接池']++ }
        elseif ($top -match 'NioSocketImpl\.(timedRead|read)|SocketInputStream|socketRead|SocketDispatcher\.read') { $counts['等下游响应']++ }
        elseif ($top -match 'BoundedLocalCache|ConcurrentHashMap\.compute') { $counts['等本机缓存加载（单飞）']++ }
        elseif ($top -match 'Semaphore') { $counts['等许可（舱壁 / 注入的并发上限）']++ }
        elseif ($top -match 'Thread\.sleep') { $counts['sleep（注入的延迟）']++ }
        else { $counts['其他']++ }
    }
    return $counts
}

<#
用 jcmd 在一个服务上开 JFR 录制（profile 模板：方法采样、分配采样、锁与 IO 事件），一直录到 Save-HaJfr 来取。
Settings 是逐项覆盖模板的事件设置，形如 'jdk.SocketRead#threshold=0ms'（JDK 17 起 JFR.start 直接认这种写法）。

要在压测开始之前开：JDK 21 的网络读写事件靠改写字节码实现，开录制的那一刻要重新转换一批类、已经编译好的代码跟着作废重编。
服务空闲时这没有影响；带着流量开，实测下单的中位耗时从 30ms 涨到 4 秒、请求堆积起来之后全场 504，过了 50 秒才恢复。
录制本身也有开销，所以开了录制的轮次只用来定位，不和没开的轮次比耗时。
#>
function Start-HaJfr($Svc, [string[]]$Settings = @()) {
    $jfrArgs = @("$($Svc.Process.Id)", 'JFR.start', 'name=experiment', 'settings=profile', 'maxage=15m') + $Settings
    $r = (& $Svc.Java.Jcmd @jfrArgs 2>&1 | Out-String).Trim()
    if ($r -notmatch 'Started recording') { throw "$($Svc.Name) 的 JFR 没有开起来：$r" }
    Write-HaLog "$($Svc.Name) 开始录 JFR"
}

# 把最近 LastSec 秒的录制写到 OutFile，然后停掉录制
function Save-HaJfr($Svc, [string]$OutFile, [int]$LastSec) {
    New-Item -ItemType Directory -Force -Path (Split-Path $OutFile -Parent) | Out-Null
    $r = (& $Svc.Java.Jcmd "$($Svc.Process.Id)" 'JFR.dump' 'name=experiment' "maxage=${LastSec}s" "filename=$OutFile" 2>&1 | Out-String).Trim()
    if (-not (Test-Path $OutFile)) { throw "$($Svc.Name) 的 JFR 没有写出来：$r" }
    & $Svc.Java.Jcmd "$($Svc.Process.Id)" 'JFR.stop' 'name=experiment' 2>&1 | Out-Null
    Write-HaLog "$($Svc.Name) 最近 $LastSec 秒的 JFR → $OutFile"
}

<#
在放 MySQL 的那台虚拟机上跑 ha-vm.sh 的一个子命令（reset、status、digest……），返回它的输出。
地址取环境变量 HA_VM_SSH（run-batch.ps1 带 -VmSsh 时会设好，脚本也是它传过去的）；没设就什么都不做、返回空——
单独跑一轮实验时没有这份数据，其余照常。
#>
function Invoke-HaVm([string]$Command) {
    if (-not $env:HA_VM_SSH) { return @() }
    $ErrorActionPreference = 'Continue'   # ssh 往标准错误写一行字（警告之类）不算失败；模块默认的 Stop 会把它当成异常抛出去
    return @(& ssh -o BatchMode=yes -o ConnectTimeout=10 $env:HA_VM_SSH "sh /tmp/ha-vm.sh $Command" 2>$null)
}

<#
从 Prometheus 导出一段时间的曲线（query_range），每条查询一个 CSV：time,series,value。
QueriesFile 每行「名字 | PromQL」，# 开头是注释。不传 PrometheusUrl 就跳过（只有本地采样的数据）。
#>
function Export-HaPrometheus([string]$PrometheusUrl, [string]$QueriesFile, [long]$StartMs, [long]$EndMs, [string]$OutDir, [int]$StepSec = 5) {
    if (-not $PrometheusUrl) { Write-HaLog '没有传 -PrometheusUrl，跳过 Prometheus 导出'; return }
    $dir = Join-Path $OutDir 'prometheus'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $start = [math]::Floor($StartMs / 1000) - 60
    $end = [math]::Ceiling($EndMs / 1000) + 60
    foreach ($raw in (Get-Content -LiteralPath $QueriesFile -Encoding UTF8)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\s*\|\s*', 2
        $name = $parts[0]; $query = $parts[1]
        $uri = "$PrometheusUrl/api/v1/query_range?query=$([uri]::EscapeDataString($query))&start=$start&end=$end&step=$StepSec"
        try {
            $r = Invoke-RestMethod -Uri $uri -TimeoutSec 30
            $out = New-Object System.Collections.Generic.List[string]
            $out.Add('epoch_s,series,value')
            foreach ($series in $r.data.result) {
                $labels = ($series.metric.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ';'
                foreach ($v in $series.values) { $out.Add(('{0},"{1}",{2}' -f $v[0], $labels, $v[1])) }
            }
            [System.IO.File]::WriteAllLines((Join-Path $dir "$name.csv"), $out, (New-Object System.Text.UTF8Encoding($false)))
        } catch {
            Write-HaLog "导出 $name 失败：$($_.Exception.Message)"
        }
    }
    Write-HaLog "Prometheus 曲线已导出到 $dir"
}

# ---------------------------------------------------------------- 一轮实验的骨架

function New-HaRunDir([string]$Experiment, [string]$Variant) {
    $dir = Join-Path $script:ExperimentsRoot ("out\{0}\{1}-{2}" -f $Experiment, (Get-Date -Format 'yyyyMMdd-HHmmss'), $Variant)
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'logs') | Out-Null
    return $dir
}

<#
跑一轮：读参数 → 起服务 → 补库存 → 执行 BeforeLoad → 开采样 → 后台起压测 → 执行 During（实验自己的动作，如抓线程栈、逐个重启）
→ 等压测结束 → 导出 → 执行 Cleanup → 收尾。
During 是 scriptblock，参数是一个上下文对象（Topology、Props、RunDir、StartedMs、Java、Gatling、Events）。
SamplerExtraPattern 可选：逐秒采样（metrics.csv）除默认那批指标外还要留哪些，见 Get-HaSamplerPattern。
ServicesJavaHome 可选：四个服务用另一个 JDK 起（压测仍用 JavaHome——Maven 的构建规则只认 JDK 21）；MallAppJavaHome 可选：只把 mall-app 换成另一个 JDK。
BeforeLoad、Cleanup 可选，参数是同一个上下文对象：BeforeLoad 在服务就绪、库存补完之后、压测开始之前执行（这时 StartedMs 是 0、Gatling 是空），
出错则这一轮不跑；Cleanup 在停服务之前执行，中途出错或 Ctrl+C 也会执行（服务还活着时才有意义，自己要判断），它出错只记日志。
#>
function Invoke-HaExperiment {
    param(
        [string]$Experiment, [string]$Variant, [string]$Simulation,
        [string[]]$Set = @(), [string]$JavaHome, [string]$PrometheusUrl, [string]$NacosNamespace,
        [int]$MallAppCount = 1, [scriptblock]$During, [switch]$Build, [switch]$KeepServices,
        [string[]]$Skus = @('20001', '20002', '20003'),
        [scriptblock]$BeforeLoad, [scriptblock]$Cleanup, [int]$RestockTarget = 100000,
        [string]$SamplerExtraPattern, [string]$MallAppJavaHome, [string]$ServicesJavaHome)

    $expDir = Join-Path $script:ExperimentsRoot $Experiment
    $props = Get-HaVariantProps $Experiment $Variant $Set
    $java = Resolve-HaJava $JavaHome
    $svcJava = if ($ServicesJavaHome) { Resolve-HaJava $ServicesJavaHome } else { $java }   # 四个服务可以整体换一个 JDK，压测（Maven）仍用 JavaHome
    $appJava = if ($MallAppJavaHome) { Resolve-HaJava $MallAppJavaHome } else { $svcJava }   # mall-app 可以再单独换一个（对比 JDK 版本的实验用）
    Assert-HaJars -Build:$Build -Java $java
    $runDir = New-HaRunDir $Experiment $Variant
    Save-HaProperties $props (Join-Path $runDir 'variant.properties')
    $commit = (& git -C $script:RepoRoot rev-parse --short HEAD) 2>$null
    $appVer = ((& cmd /c "`"$($appJava.Java)`" -version 2>&1") | Select-Object -First 1)
    "experiment=$Experiment`nvariant=$Variant`ncommit=$commit`nmallAppJava=$appVer`nstarted=$(Get-Date -Format o)" | Out-File (Join-Path $runDir 'run-info.txt') -Encoding utf8
    if ($NacosNamespace) { $env:NACOS_NAMESPACE = $NacosNamespace }
    Write-HaLog "实验 $Experiment / $Variant，结果目录 $runDir"

    $topology = $null; $sampler = $null; $gatling = $null; $ctx = $null
    try {
        $topology = Start-HaTopology -Props $props -LogDir (Join-Path $runDir 'logs') -Java $svcJava -MallAppCount $MallAppCount -AppJava $appJava
        Invoke-HaRestock $Skus $RestockTarget
        $ctx = [pscustomobject]@{
            Topology = $topology; Props = $props; RunDir = $runDir; StartedMs = [long]0; Java = $svcJava
            Gatling = $null; Events = (Join-Path $runDir 'script-events.csv')
        }
        if ($BeforeLoad) { & $BeforeLoad $ctx }

        $targets = [ordered]@{}
        foreach ($s in (Get-HaAllServices $topology)) { $targets[$s.Name] = "http://localhost:$($s.MgmtPort)" }
        $sampler = Start-HaSampler $targets (Join-Path $runDir 'metrics.csv') -ExtraPattern $SamplerExtraPattern

        $sys = @{ 'baseUrl' = 'http://localhost:9000'; 'ha.jwtSecret' = (Get-HaJwtSecret); 'ha.skus' = ($Skus -join ',') }
        $gatling = Start-HaGatling -Simulation $Simulation -Props $props -SysProps $sys -RunDir $runDir -Java $java
        $startedMs = Wait-HaGatlingStarted $runDir $gatling
        Write-HaLog '压测已开始注入流量'

        $ctx.StartedMs = $startedMs; $ctx.Gatling = $gatling
        if ($During) { & $During $ctx }

        Wait-HaGatling $gatling $runDir
        $endMs = Get-EpochMs
        Stop-HaSampler $sampler; $sampler = $null
        Export-HaPrometheus $PrometheusUrl (Join-Path $expDir 'queries.txt') $startedMs $endMs $runDir
        "ended=$(Get-Date -Format o)" | Out-File (Join-Path $runDir 'run-info.txt') -Append -Encoding utf8
    } finally {
        Stop-HaSampler $sampler
        Stop-HaProcessTree $gatling
        if ($Cleanup -and $ctx) {
            try { & $Cleanup $ctx } catch { Write-HaLog "收尾动作出错：$($_.Exception.Message)" }
        }
        Clear-HaFaults $topology
        if (-not $KeepServices) { Stop-HaTopology $topology }
    }
    Write-HaLog "完成。结果：$runDir（Gatling 报告在 gatling\ 下，逐秒数据 timeline.csv，事件 events.csv / script-events.csv，指标采样 metrics.csv）"
    return $runDir
}

Export-ModuleMember -Function *-Ha*, Get-EpochMs
