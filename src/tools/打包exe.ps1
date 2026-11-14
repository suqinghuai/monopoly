# 电脑版（局域网 + 互联网二合一）打包：PyInstaller onefile
# 用法：powershell -ExecutionPolicy Bypass -File src\tools\打包exe.ps1
# 产物：dist\大富翁.exe（本地产物，不入库）
#
# 双击 exe 后先选模式：1=局域网（同一 WiFi）  2=互联网（serveo 固定地址，外地也能进）
# 两种模式跑同一个 src\server.py 和同一份 src\www\index.html；exe 里不带 cloudflared.exe，
# 想开 Cloudflare 备用隧道就把它放到 exe 同目录。

$ErrorActionPreference = "Stop"
$SRC = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)   # src\
$ROOT = Split-Path -Parent $SRC                                                # 项目根
$DIST = Join-Path $ROOT "dist"
$WORK = Join-Path $env:TEMP "mpz_pyi_dist"

Write-Host "[1/4] 检查 PyInstaller ..."
python -c "import PyInstaller" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "        未安装，尝试从清华镜像安装 ..."
    python -m pip install pyinstaller -i https://pypi.tuna.tsinghua.edu.cn/simple
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller 安装失败" }
}

Write-Host "[2/4] 检查源码与页面 ..."
foreach ($f in @("$SRC\server.py", "$SRC\launcher.py", "$SRC\www\index.html")) {
    if (-not (Test-Path -LiteralPath $f)) { throw "缺少 $f" }
}

Write-Host "[3/4] 打包中（约 1-2 分钟）..."
Push-Location $ROOT
python -m PyInstaller --noconfirm --clean --onefile `
    --name 大富翁 `
    --icon "$ROOT\assets\app.ico" `
    --add-data "$SRC\www;www" `
    --paths "$SRC" `
    --distpath $DIST `
    --workpath $WORK `
    --specpath $DIST `
    "$SRC\launcher.py"
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw "PyInstaller 打包失败（exit $code）" }

Write-Host "[4/4] 完成："
$exe = Join-Path $DIST "大富翁.exe"
if (-not (Test-Path -LiteralPath $exe)) { throw "没生成 $exe" }
$info = Get-Item -LiteralPath $exe
Write-Host ("        {0}  {1:N1} MB" -f $info.Name, ($info.Length / 1MB))
Write-Host "        spec 文件：$DIST\大富翁.spec"
