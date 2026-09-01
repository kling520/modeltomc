# ============================================================
# model2mc  jar 包打包脚本 (Windows / PowerShell)
#
# 产物目录 dist_jar（纯 jar 分发，不打包 JVM）：
#   model2mc.jar     主程序（可执行 jar，含 Main 清单与 web 资源）
#   lib\*.jar        依赖库（jmcx / jnbt）
#   start.bat        Windows 启动脚本（自动切到自身目录 + chcp 65001 + 启动）
#   start.sh         Linux/macOS 启动脚本
#   model2mc-palettes/  方块颜色映射文件
#   README.txt
#
# 用法:
#   .\build-jar.ps1
# 运行要求: 需已安装 Java 25 运行时（会优先用 $JAVA_HOME，否则用 PATH 上的 java）
# ============================================================
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $ProjectRoot "target"
Set-Location $ProjectRoot

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

# ---------- 1. Maven 编译 ----------
Write-Step "Maven 编译并打包 (target/model2mc-*.jar)"
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven 编译失败" }

# ---------- 2. 确定主 jar 与依赖 ----------
$appJar = Get-ChildItem "target\model2mc-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' -and $_.Name -ne 'model2mc.jar' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $appJar) { throw "未找到应用 jar: target\model2mc-*.jar" }

Write-Step "收集依赖 jar"
mvn -q dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt"
if ($LASTEXITCODE -ne 0) { throw "获取依赖 classpath 失败" }
$depJars = @()
if (Test-Path "target\classpath.txt") {
    $raw = (Get-Content "target\classpath.txt" -Raw).Trim()
    if ($raw) { $depJars = $raw -split ';' | Where-Object { $_ } }
}
Write-Host "主 jar: $($appJar.Name)"
Write-Host "依赖 jar 数量: $($depJars.Count)"

# ---------- 3. 组装 dist_jar ----------
Write-Step "组装 dist_jar 目录"
$distJar = Join-Path $ProjectRoot "dist_jar"
if (Test-Path $distJar) { Remove-Item -Recurse -Force $distJar }
New-Item -ItemType Directory -Force -Path $distJar | Out-Null
Copy-Item $appJar.FullName (Join-Path $distJar "model2mc.jar")

$libDir = Join-Path $distJar "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
foreach ($j in $depJars) {
    if (Test-Path $j) { Copy-Item $j $libDir }
}

$palettes = Join-Path $ProjectRoot "model2mc-palettes"
if (Test-Path $palettes) {
    Copy-Item -Recurse $palettes (Join-Path $distJar "model2mc-palettes")
} else {
    New-Item -ItemType Directory -Force -Path (Join-Path $distJar "model2mc-palettes") | Out-Null
}

# ---------- 4. 生成 start.bat ----------
Write-Step "生成启动脚本 start.bat / start.sh"
$bat = @'
@echo off
rem model2mc launcher (Windows)
rem cd to script dir to avoid relative-path issues
cd /d "%~dp0"
rem switch console to UTF-8 so Chinese logs display correctly
chcp 65001 >nul

rem prefer JAVA_HOME, else use java on PATH
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

"%JAVA_EXE%" -cp "model2mc.jar;lib\*" org.example.Main %*
exit /b %ERRORLEVEL%
'@
Set-Content -Path (Join-Path $distJar "start.bat") -Value $bat -Encoding ASCII

$sh = @'
#!/usr/bin/env bash
# model2mc launcher (Linux/macOS)
# cd to script dir to avoid relative-path issues
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# prefer JAVA_HOME, else use java on PATH
JAVA_EXE=java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
fi

exec "$JAVA_EXE" -cp "model2mc.jar:lib/*" org.example.Main "$@"
'@
Set-Content -Path (Join-Path $distJar "start.sh") -Value $sh -Encoding ASCII

# ---------- 5. 生成 README ----------
$readme = @'
model2mc jar 包版（需自带 Java 25 运行时）
==========================================

文件说明:
  model2mc.jar        主程序（含 Main 清单与 web 资源）
  lib/*.jar           依赖库（jmcx / jnbt）
  start.bat           Windows 启动脚本
  start.sh            Linux / macOS 启动脚本
  model2mc-palettes/  方块颜色映射文件（可编辑；程序运行时会自动读写该目录）

运行要求:
  - 需已安装 Java 25 运行时，且通过 JAVA_HOME 或 PATH 可找到 java。
  - Windows/Linux/macOS 均可运行（纯 jar，无需 native 编译）。

使用方法:
  1. Windows:  双击 start.bat（或命令行执行），启动 Web 界面（默认 http://127.0.0.1:8088）
  2. Linux/macOS:  chmod +x start.sh && ./start.sh
  3. 命令行传参（在 dist_jar 目录下执行）:
     start.bat --model-count 1 --model-0-obj xxx.obj --model-0-mtl xxx.mtl --model-0-x 0 --model-0-y 64 --model-0-z 0 --world <存档目录>
     或直接:  java -cp "model2mc.jar;lib\*" org.example.Main <参数>   （Windows）
              java -cp "model2mc.jar:lib/*" org.example.Main <参数>   （Linux/macOS）

乱码说明:
  - 程序日志按 UTF-8 输出。start.bat 会自动执行 chcp 65001 切到 UTF-8 代码页。
  - 若手动用 java 运行且在中文 Windows 默认 GBK(936) 下乱码，可先执行  chcp 65001。

注意:
  - model2mc.jar 与 lib\ 目录、model2mc-palettes 目录必须一起分发。
  - 首次运行会自动在 model2mc-palettes 下生成默认映射文件。
'@
Set-Content -Path (Join-Path $distJar "README.txt") -Value $readme -Encoding UTF8

Write-Host ""
Write-Host "jar 包构建完成: $distJar" -ForegroundColor Green
Get-ChildItem $distJar | Select-Object Name, Length