<#
  MinewaysMobile · 一键构建（debug + release，release 自动对齐并签名）
  ==========================================================================
  为什么需要它：
    1. 本工程路径含中文与空格，AGP/NDK 在非 ASCII 路径下曾出现构建异常，
       因此脚本先把工程复制到纯 ASCII 沙箱（默认 C:\mmbuild）再构建；
    2. 工程里没有配置 signingConfig，正式包一直是手工用 apksigner 签的，
       这里把「zipalign → apksigner → 校验 → 分发」固化成一条命令。

  用法：
    powershell -ExecutionPolicy Bypass -File neteasemc\tools\build_release.ps1
    # 口令未提供时读环境变量 MINEWAYS_STOREPASS；仍为空则交互输入
    #   -StorePass <口令>   -Alias mineways   -Sandbox C:\mmbuild
    #   -SkipSign          只构建不签名
    #   -Offline           强制离线（依赖已全部缓存时更快）

  注意：本文件是 UTF-8 with BOM（Windows PowerShell 5.1 读中文必需），
        编辑保存时请保留 BOM，否则中文会被按 ANSI 解析而报语法错。
#>
[CmdletBinding()]
param(
    [string]$StorePass = 'Mineways2026',
    [string]$Alias = 'mineways',
    [string]$Sandbox = 'C:\mmbuild',
    [switch]$SkipSign,
    [switch]$Offline
)

$ErrorActionPreference = 'Continue'
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$ws = Split-Path -Parent (Split-Path -Parent $scriptDir)     # ...\<工作区>\neteasemc 的上层
$project = Join-Path $ws 'neteasemc'
$jks = Join-Path $ws '产物\mineways-release2.jks'
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\android-sdk' }
$bt = (Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1).FullName

if (-not (Test-Path $project)) { Write-Host "找不到工程目录：$project" -ForegroundColor Red; exit 1 }
Write-Host ("工程：{0}" -f $project)
Write-Host ("沙箱：{0}" -f $Sandbox)

# ---------------------------------------------------------------- 0/6. 内存守卫
# 撞过：连续构建多次后，残留的 Gradle 守护进程一直占内存 + 页面文件耗尽，
# 编译 JVM 连 64MB 都申请不下来：
#   os::commit_memory(...) failed; error='页面文件太小，无法完成操作。' (DOS error/errno=1455)
#   insufficient memory for the Java Runtime Environment to continue
# 这里在构建前先回收上一次的守护进程，并在虚拟内存不足时明确提示。
Write-Host '== 0/6 内存守卫 ==' -ForegroundColor Cyan
$osInfo = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
$freeMb = if ($osInfo) { [int]($osInfo.FreePhysicalMemory / 1024) } else { 0 }
$pageMb = if ($osInfo) { [int]($osInfo.FreeVirtualMemory / 1024) } else { 0 }
$stale = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
         Where-Object { $_.CommandLine -and ($_.CommandLine -match 'GradleDaemon' -or $_.CommandLine -match 'org\.gradle') }
if ($stale) {
    foreach ($p in $stale) {
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Write-Host ('  回收残留构建进程 pid=' + $p.ProcessId) -ForegroundColor Green
        } catch { }
    }
    Start-Sleep -Seconds 2
    $osInfo = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
    if ($osInfo) {
        $freeMb = [int]($osInfo.FreePhysicalMemory / 1024)
        $pageMb = [int]($osInfo.FreeVirtualMemory / 1024)
    }
}
Write-Host ("  物理内存剩余 {0} MB · 页面文件剩余 {1} MB" -f $freeMb, $pageMb)
if ($pageMb -lt 512) {
    Write-Host '  [警告] 虚拟内存（页面文件）快满了，构建很可能因内存不足失败。' -ForegroundColor Yellow
    Write-Host '         处理：关掉占内存的程序，或调大页面文件（系统属性 → 高级 → 性能 → 虚拟内存）。' -ForegroundColor Yellow
}
Write-Host ("build-tools：{0}" -f $bt)

# ---------------------------------------------------------------- 1. 沙箱
Write-Host '== 1/6 复制到 ASCII 沙箱 ==' -ForegroundColor Cyan
if (Test-Path $Sandbox) { cmd /c "rmdir /s /q `"$Sandbox`"" | Out-Null }
robocopy $project $Sandbox /E /XD build .gradle /NFL /NDL /NJH /NJS /NP | Out-Null
$files = Get-ChildItem $Sandbox -Recurse -File
Write-Host ("  {0} 个文件, {1:N1} MB" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB))

# ---------------------------------------------------------------- 2. 构建
Write-Host '== 2/6 构建 debug + release ==' -ForegroundColor Cyan
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$log = Join-Path $Sandbox 'build.log'
$offlineArg = if ($Offline) { '--offline' } else { '' }
Push-Location $Sandbox
cmd /c "gradle $offlineArg :app:assembleDebug :app:assembleRelease > `"$log`" 2>&1"
$code = $LASTEXITCODE
Pop-Location
Get-Content $log -Encoding Default | Where-Object { $_ -match 'BUILD SUCCESSFUL|BUILD FAILED|error:|^e: ' } | ForEach-Object { '  ' + $_ }
if ($code -ne 0) {
    Write-Host '  构建失败，日志尾部：' -ForegroundColor Red
    Get-Content $log -Encoding Default | Select-Object -Last 30
    exit 1
}

$unsigned = Join-Path $Sandbox 'app\build\outputs\apk\release\app-release-unsigned.apk'
$debugApk = Join-Path $Sandbox 'app\build\outputs\apk\debug\app-debug.apk'

# ---------------------------------------------------------------- 3. 对齐 + 签名
$release = $null
if ($SkipSign) {
    Write-Host '== 3/6 跳过签名（-SkipSign） ==' -ForegroundColor Yellow
    $release = $unsigned
} else {
    Write-Host '== 3/6 zipalign + 签名 ==' -ForegroundColor Cyan
    if (-not $StorePass) { $StorePass = Read-Host '请输入密钥库口令 (store/key 同一口令)' }
    if (-not (Test-Path $jks)) { Write-Host "  找不到密钥库：$jks" -ForegroundColor Red; exit 1 }
    $aligned = Join-Path $Sandbox 'app-aligned.apk'
    $release = Join-Path $Sandbox 'MinewaysMobile-release.apk'
    & "$bt\zipalign.exe" -f -P 16 4 $unsigned $aligned 2>&1 | ForEach-Object { '  ' + $_ }
    if ($LASTEXITCODE -ne 0) { & "$bt\zipalign.exe" -f 4 $unsigned $aligned 2>&1 | ForEach-Object { '  ' + $_ } }
    & "$bt\apksigner.bat" sign --ks $jks --ks-key-alias $Alias `
        --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
        --out $release $aligned 2>&1 | ForEach-Object { '  ' + $_ }
    if (-not (Test-Path $release)) { Write-Host '  签名失败' -ForegroundColor Red; exit 1 }
}

# ---------------------------------------------------------------- 4. 核验
Write-Host '== 4/6 核验 ==' -ForegroundColor Cyan
if (-not $SkipSign) {
    & "$bt\apksigner.bat" verify --print-certs $release 2>&1 |
        Where-Object { $_ -match 'SHA-256 digest|DN:|Verified using v2|Verified using v3' } | ForEach-Object { '  ' + $_ }
}
$aapt = (Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter aapt2.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if ($aapt) {
    & $aapt dump badging $release 2>&1 | Where-Object { $_ -match "^package:|application-label:'" } |
        Select-Object -First 2 | ForEach-Object { '  ' + $_ }
    $tree = & $aapt dump xmltree --file AndroidManifest.xml $release 2>&1 | Out-String
    foreach ($act in @('MiniGameActivity', 'BlockbenchActivity')) {
        Write-Host ('  ' + $act + ' 已注册: ' + [bool]($tree -match $act))
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($release)
$mg = $zip.Entries | Where-Object { $_.FullName -like 'assets/minigame/*' }
$bb = $zip.GetEntry('assets/blockbench/dist/bundle.js')
$saver = $zip.GetEntry('assets/blockbench/plugins/mineways_saver/mineways_saver.js')
Write-Host ("  小游戏资产 {0} 个, {1:N1} MB；保存助手 {2}；Blockbench 内核 {3}" -f `
    $mg.Count, (($mg | Measure-Object Length -Sum).Sum / 1MB), [bool]$saver, [bool]$bb)
$zip.Dispose()

# ---------------------------------------------------------------- 5/6. 兼容性自检
# 旧 Android 上不存在的 Java 9+ 类型。本项目踩过一次：
# caffeine 3.x 引用 java.lang.System$Logger → 转换时报 NoSuchMethodError(getLogger)
# 紧接着 NoClassDefFoundError: Caffeine。此后构建时统一自检，避免同类问题再溜进包里。
Write-Host '== 5/6 Java 9+ 类型自检 ==' -ForegroundColor Cyan
$risky = @('java/lang/System$Logger', 'java/lang/Module', 'java/util/HexFormat', 'java/lang/StackWalker', ')Ljava/nio/MappedByteBuffer;')  # 最后一项：Java 9 的 MappedByteBuffer 协变签名，Android API<34 没有（见 chunker-core/libs 的补丁 jar）
$hits = @()
$zip2 = [System.IO.Compression.ZipFile]::OpenRead($release)
foreach ($e in $zip2.Entries) {
    if ($e.FullName -notlike '*.dex') { continue }
    $ms = New-Object System.IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Dispose()
    $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
    foreach ($r in $risky) { if ($txt.Contains($r)) { $hits += ("{0}  in  {1}" -f $r, $e.FullName) } }
}
$zip2.Dispose()
if ($hits.Count) {
    Write-Host '  [警告] 包内引用了旧 Android 上可能不存在的类型：' -ForegroundColor Yellow
    $hits | Select-Object -Unique | ForEach-Object { '    ' + $_ }
} else {
    Write-Host '  通过：未引用 System$Logger / Module / HexFormat / StackWalker' -ForegroundColor Green
}

# ---------------------------------------------------------------- 5b/6. 源码级 API 兼容自检
# 又踩过一次：StringBuilder.isEmpty() 是 Java 15 的 CharSequence.isEmpty 默认方法，
# Android 要到 API 34+ 才有，API 33 及以下直接抛
#   NoSuchMethodError: No virtual method isEmpty()Z in class Ljava/lang/StringBuilder;
# 同理 String.repeat() / isBlank() / strip() / lines() 是 Java 11 新增（Android 需 API 33+）。
# 这些调用在 dex 里看不出来（类名与方法名在字符串池里是分开存的），所以改在源码层拦截。
Write-Host '== 5b/6 源码级 API 兼容自检 ==' -ForegroundColor Cyan
$apiHits = @()
$srcFiles = @()
foreach ($r in @((Join-Path $Sandbox 'app\src\main\java'), (Join-Path $Sandbox 'chunker-core\src\main\java'))) {
    if (Test-Path $r) { $srcFiles += Get-ChildItem $r -Recurse -Filter '*.java' -File }
}
foreach ($f in $srcFiles) {
    $txt = Get-Content $f.FullName -Raw -Encoding UTF8
    if (-not $txt) { continue }
    # 先去掉注释，避免注释里提到 API 名字被误报
    $txt = [regex]::Replace($txt, '(?s)/\*.*?\*/', ' ')
    $txt = [regex]::Replace($txt, '(?m)//.*$', ' ')
    $rel = $f.FullName.Replace($Sandbox, '')
    $names = @()
    foreach ($m in [regex]::Matches($txt, '(?:StringBuilder|StringBuffer)\s+(\w+)\s*[=;]')) { $names += $m.Groups[1].Value }
    foreach ($n in ($names | Select-Object -Unique)) {
        if ($txt -match ('(?<![\w.])' + [regex]::Escape($n) + '\.isEmpty\(\)')) {
            $apiHits += ('{0}  ->  {1}.isEmpty()   [StringBuilder.isEmpty 需 API 34+]' -f $rel, $n)
        }
    }
    foreach ($p in @('\.isBlank\(\)', '\.strip\(\)', '\.stripLeading\(\)', '\.stripTrailing\(\)', '\.lines\(\)', '\.repeat\(')) {
        if ($txt -match $p) { $apiHits += ('{0}  ->  {1}   [Java 11 字符串 API，需 API 33+]' -f $rel, $p) }
    }
}
if ($apiHits.Count) {
    Write-Host '  [警告] 源码里仍有旧 Android 上不存在的 API 调用：' -ForegroundColor Yellow
    $apiHits | Select-Object -Unique | ForEach-Object { '    ' + $_ }
} else {
    Write-Host '  通过：无 StringBuilder.isEmpty / String.repeat / isBlank / strip / lines' -ForegroundColor Green
}

# ---------------------------------------------------------------- 5c/6. JNI 符号自检
# 撞过：native 声明在 com.mineways.ExportActivity，C 侧却写成 Java_com_mineways_MainActivity_xxx
#   → 运行时 UnsatisfiedLinkError（存档导出整个不可用），只有真机点「开始导出」才暴露。
# 这里在打包前把 Java 的 native 声明与 .so 里的符号名对一遍（两个 .so 一起查）。
Write-Host '== 5c/6 JNI 符号自检 ==' -ForegroundColor Cyan
$jniBad = @()
$soText = ''
foreach ($rel in @('app\build\intermediates\stripped_native_libs\release', 'app\build\intermediates\merged_native_libs\release')) {
    $root = Join-Path $Sandbox $rel
    if (-not (Test-Path $root)) { continue }
    foreach ($so in (Get-ChildItem $root -Recurse -Filter '*.so' -ErrorAction SilentlyContinue)) {
        $txt = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($so.FullName))
        $soText += $txt
    }
}
if ($soText.Length -eq 0) {
    Write-Host '  [跳过] 没找到 release 的 .so' -ForegroundColor Yellow
} else {
    $javaDir = Join-Path $Sandbox 'app\src\main\java\com\mineways'
    $checked = 0
    foreach ($jf in (Get-ChildItem $javaDir -Filter '*.java' -ErrorAction SilentlyContinue)) {
        $cls = $jf.BaseName
        $txt = Get-Content $jf.FullName -Raw -Encoding UTF8
        foreach ($m in [regex]::Matches($txt, '(?m)^\s*(?:public\s+|private\s+|protected\s+|static\s+|final\s+)*native\s+[A-Za-z0-9_\[\]<>\.]+\s+(\w+)\s*\(')) {
            $name = $m.Groups[1].Value
            $sym = 'Java_com_mineways_' + $cls + '_' + $name
            $checked++
            if ($soText.Contains($sym)) {
                Write-Host ('  通过：' + $sym) -ForegroundColor Green
            } else {
                $jniBad += $sym
                Write-Host ('  [错误] .so 里找不到符号 ' + $sym + '（声明在 ' + $jf.Name + '）') -ForegroundColor Red
            }
        }
    }
    Write-Host ('  共检查 ' + $checked + ' 个 native 声明')
}
if ($jniBad.Count) {
    Write-Host '  JNI 符号缺失 → 运行时 UnsatisfiedLinkError。C 侧函数名必须写成 Java_<包名>_<声明类>_<方法名>。' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 6/6. 分发
Write-Host '== 5/6 分发到工作区 ==' -ForegroundColor Cyan
Copy-Item $release (Join-Path $ws 'MinewaysMobile-release.apk') -Force
Copy-Item $debugApk (Join-Path $ws 'MinewaysMobile-debug.apk') -Force
$prodDir = Join-Path $ws '产物'
if (Test-Path $prodDir) { Copy-Item $release (Join-Path $prodDir 'MinewaysMobile-release.apk') -Force }
Get-ChildItem $ws -Filter '*.apk' | ForEach-Object { Write-Host ("  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) -ForegroundColor Green }

Write-Host '== 完成 ==' -ForegroundColor Cyan
Write-Host ("  沙箱保留在 {0}（含未签名包与日志；不需要可删）" -f $Sandbox)
