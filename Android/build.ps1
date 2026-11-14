# 安卓版打包：调本机已有的 Gradle + JDK17 + SDK，产出可安装的 APK。
# 用法：powershell -ExecutionPolicy Bypass -File Android\build.ps1
#       powershell -ExecutionPolicy Bypass -File Android\build.ps1 -Release

param(
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$HERE = Split-Path -Parent $MyInvocation.MyCommand.Path
$ROOT = Split-Path -Parent $HERE

# ---- 工具链位置：优先用本机 android-toolchain，其次看环境变量 ----
$TC = 'C:\Users\43061\android-toolchain'
$GRADLE = Join-Path $TC 'gradle\gradle-8.7\bin\gradle.bat'
$SDK = Join-Path $TC 'sdk'
$JDK = Get-ChildItem -LiteralPath (Join-Path $TC 'jdk') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
    Select-Object -First 1
if (-not $JDK) { $JDK = $null }

if (-not (Test-Path -LiteralPath $GRADLE)) { throw "找不到 Gradle：$GRADLE" }
if (-not (Test-Path -LiteralPath $SDK)) { throw "找不到 Android SDK：$SDK" }
if (-not $JDK) { throw "找不到 JDK17：$TC\jdk" }

$env:JAVA_HOME = $JDK.FullName
$env:ANDROID_HOME = $SDK
Write-Host "JDK   : $($JDK.FullName)"
Write-Host "SDK   : $SDK"
Write-Host "Gradle: $GRADLE"

if (-not (Test-Path -LiteralPath (Join-Path $HERE 'local.properties'))) {
    $esc = $SDK.Replace('\', '\\')
    Set-Content -LiteralPath (Join-Path $HERE 'local.properties') -Value "sdk.dir=$esc" -Encoding utf8
}

$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
$variant = if ($Release) { 'release' } else { 'debug' }

Write-Host "[1/3] 同步网页资源 + 编译（$task）..."
& $GRADLE -p $HERE $task --offline --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败，退出码 $LASTEXITCODE" }

$apk = Join-Path $HERE "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "没找到产物：$apk" }

Write-Host "[2/3] 复制到 release\ ..."
$outDir = Join-Path $HERE 'release'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$ver = (Select-String -LiteralPath (Join-Path $HERE 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$out = Join-Path $outDir "大富翁v$ver.apk"
Copy-Item -LiteralPath $apk -Destination $out -Force

Write-Host "[3/3] 完成："
Write-Host ("        {0}  {1:N0} 字节" -f (Split-Path $out -Leaf), (Get-Item -LiteralPath $out).Length)
