<#
.SYNOPSIS
实验 L：微基准——用 JMH 量业务代码里几段纯 CPU 逻辑（bench 模块）。不起服务、不连数据库，只用本机的 CPU。方案见 experiments/README.md「实验 L」。

.DESCRIPTION
先打包 bench 模块，再用它的类路径直接跑 JMH（org.openjdk.jmh.Main），结果放在 experiments/out/l-jmh/<时间>/：
  jmh.json   JMH 的原始结果（每个基准每次迭代的数值；带 -prof gc，所以也有每次调用分配的字节数 gc.alloc.rate.norm）
  jmh.log    JMH 的控制台输出
  naive.csv  同一段代码不用 JMH、手写计时循环量出来的数（NaiveTimingDemo），和 jmh.json 对照着看
跑的时候机器上不要有别的重活（压测、构建），否则误差会变大。完整跑一遍约 15 分钟。

.EXAMPLE
.\experiments\l-jmh\run.ps1
# 只跑游标那一组，少跑几轮先看个大概
.\experiments\l-jmh\run.ps1 -Include OrderCursor -Quick

.PARAMETER Include
只跑类名或方法名匹配这个正则的基准，默认全部
.PARAMETER Quick
每个基准只预热 2 轮、测 2 轮、起 1 个子进程（默认是 5 轮、5 轮、2 个子进程），数不准，只用来确认能跑通
#>
param(
    [string]$Include = '.*',
    [string]$JavaHome,
    [switch]$Quick
)
Import-Module (Join-Path $PSScriptRoot '..\lib\HaExperiment.psm1') -Force

$repo = Get-HaRepoRoot
$java = Resolve-HaJava $JavaHome
$env:JAVA_HOME = $java.Home
$out = Join-Path $repo ("experiments\out\l-jmh\{0}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $out | Out-Null

Push-Location $repo
try {
    # 打包和取类路径放在同一次 Maven 调用里、并且带上依赖的模块（-am）：这样类路径里订单模块、公共模块指向的是刚打出来的包，
    # 而不是本机仓库里以前 install 过的旧版本
    Write-HaLog '打包 bench 模块及其依赖（跳过测试），取类路径……'
    $cpFile = Join-Path $repo 'bench\target\classpath.txt'
    & .\mvnw.cmd -B -q -DskipTests package dependency:build-classpath -pl bench -am "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw 'Maven 打包失败' }
    $cp = (Join-Path $repo 'bench\target\classes') + ';' + (Get-Content $cpFile -Raw).Trim()
    # 类路径有一万多个字符，放进参数文件再用 @文件 传给 java，不占命令行长度；参数文件里反斜杠是转义符，路径一律换成正斜杠
    $argFile = Join-Path $out 'java.args'
    [System.IO.File]::WriteAllText($argFile, ('-cp "' + $cp.Replace([string][char]92, '/') + '"'), (New-Object System.Text.UTF8Encoding($false)))

    Write-HaLog '手写计时的对照（NaiveTimingDemo）……'
    & $java.Java "@$argFile" io.github.clarence404.mall.order.query.NaiveTimingDemo (Join-Path $out 'naive.csv') 2>&1 |
        Out-File (Join-Path $out 'naive.log') -Encoding utf8

    $jmhArgs = @($Include, '-rf', 'json', '-rff', (Join-Path $out 'jmh.json'), '-prof', 'gc', '-jvmArgs', '-Xms1g -Xmx1g')
    if ($Quick) { $jmhArgs += @('-wi', '2', '-i', '2', '-f', '1') }
    Write-HaLog "开始跑 JMH（$Include），输出在 $out\jmh.log"
    & $java.Java "@$argFile" org.openjdk.jmh.Main @jmhArgs 2>&1 | Out-File (Join-Path $out 'jmh.log') -Encoding utf8
    if (-not (Test-Path (Join-Path $out 'jmh.json'))) { throw "JMH 没有产出结果，看 $out\jmh.log" }
    $commit = (& git -C $repo rev-parse --short HEAD) 2>$null
    $ver = ((& cmd /c "`"$($java.Java)`" -version 2>&1") | Select-Object -First 1)
    "commit=$commit`njava=$ver`ninclude=$Include`nquick=$Quick" | Out-File (Join-Path $out 'run-info.txt') -Encoding utf8
} finally {
    Pop-Location
}
Write-HaLog "完成。结果：$out"
