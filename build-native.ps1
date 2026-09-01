# ============================================================
# model2mc  GraalVM native-image 构建脚本 (Windows / PowerShell)
#
# 用法:
#   .\build-native.ps1                     # 默认优化级别 O1
#   .\build-native.ps1 -OptimizationLevel O2
#   .\build-native.ps1 -GraalVMHome "D:\...\graalvm-jdk-25.0.1+8.1"
#   .\build-native.ps1 -RegenerateConfig   # 重新用 tracing agent 采集 native 元数据（AWT/反射/资源）
#
# 产物 (dist 目录):
#   model2mc.exe        主程序（原生可执行文件）
#   *.dll               运行所需的 JDK 库（AWT/ImageIO 等），必须与 exe 同目录
#   启动.exe            启动器（自动切换到自身目录后启动主程序，透传命令行参数）
#   model2mc-palettes/  方块颜色映射文件（可编辑，程序运行时会读写）
#   README.txt          使用说明
# ============================================================
param(
    [string]$GraalVMHome = "",
    [ValidateSet("O0", "O1", "O2", "Os")] [string]$OptimizationLevel = "O1",
    [switch]$RegenerateConfig
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $ProjectRoot "target"
Set-Location $ProjectRoot

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

# 在 GraalVM 目录下定位 native-image（优先原生 exe，避免 .cmd 批处理对参数里的特殊字符二次解析）
function Find-NativeImage([string]$home) {
    foreach ($c in @(
        (Join-Path $home "lib\svm\bin\native-image.exe"),
        (Join-Path $home "bin\native-image.exe"),
        (Join-Path $home "bin\native-image.cmd")
    )) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

# ---------- 1. 定位 native-image ----------
Write-Step "定位 GraalVM native-image"
$nativeImage = $null
if ($GraalVMHome) {
    $nativeImage = Find-NativeImage $GraalVMHome
    if (-not $nativeImage) {
        throw "未在 -GraalVMHome 下找到 native-image: $GraalVMHome"
    }
} else {
    $cmd = Get-Command native-image -ErrorAction SilentlyContinue
    if ($cmd) {
        $nativeImage = $cmd.Source
    } else {
        foreach ($c in @("$env:JAVA_HOME", "D:\BaiduSyncdisk\Java\graalvm-jdk-25.0.1+8.1")) {
            if ($c) {
                $found = Find-NativeImage $c
                if ($found) { $nativeImage = $found; break }
            }
        }
    }
}
if (-not $nativeImage) {
    throw "未找到 native-image，请安装 GraalVM 或通过 -GraalVMHome 指定路径"
}
Write-Host "native-image: $nativeImage"
$versionOutput = & $nativeImage --version
if ($LASTEXITCODE -ne 0) { throw "native-image 不可用" }
Write-Host ($versionOutput | Select-Object -First 1)

# ---------- 2. Maven 编译 ----------
Write-Step "Maven 编译项目 (target/classes)"
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven 编译失败" }

# ---------- 3. 生成运行时 classpath ----------
Write-Step "生成运行时 classpath"
mvn -q dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt"
if ($LASTEXITCODE -ne 0) { throw "获取依赖 classpath 失败" }
$deps = (Get-Content "target\classpath.txt" -Raw).Trim()
$cp = "target\classes" + $(if ($deps) { ";" + $deps } else { "" })
Write-Host "classpath: $cp"

# ---------- 3.5 可选：用 tracing agent 重新采集 native 元数据 ----------
# 项目使用 java.awt/ImageIO（贴图预览），native 镜像需要 AWT 的 JNI/反射元数据，
# 由 native-image-config/reachability-metadata.json 提供；代码改动后可重新生成。
$configDir = Join-Path $ProjectRoot "native-image-config"
if ($RegenerateConfig) {
    Write-Step "用 tracing agent 重新采集 native 元数据 -> $configDir"
    if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Force -Path $configDir | Out-Null }
    $javaExe = $null
    foreach ($c in @(
        (Join-Path (Split-Path -Parent $nativeImage) "java.exe"),
        (Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $nativeImage))) "bin\java.exe")
    )) {
        if (Test-Path $c) { $javaExe = $c; break }
    }
    if (-not $javaExe) { $javaExe = "java" }
    $agentOpt = "-agentlib:native-image-agent=config-output-dir=$configDir,config-write-period-secs=5"
    $server = Start-Process -FilePath $javaExe -ArgumentList $agentOpt, "-cp", $cp, "org.example.Main" `
        -WorkingDirectory $ProjectRoot -PassThru
    try {
        Start-Sleep -Seconds 6
        foreach ($u in @(
            "http://127.0.0.1:8088/",
            "http://127.0.0.1:8088/api/block-texture?block=stone",
            "http://127.0.0.1:8088/api/block-texture?block=stone&style=flat",
            "http://127.0.0.1:8088/api/block-texture?block=grass_block",
            "http://127.0.0.1:8088/api/block-texture?block=oak_leaves",
            "http://127.0.0.1:8088/api/config",
            "http://127.0.0.1:8088/api/palette",
            "http://127.0.0.1:8088/api/status"
        )) {
            try { Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 30 | Out-Null; Write-Host "  OK $u" }
            catch { Write-Host "  SKIP $u : $($_.Exception.Message)" }
        }
        Start-Sleep -Seconds 8
    } finally {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path (Join-Path $configDir "reachability-metadata.json"))) {
        throw "tracing agent 未生成配置，请检查 native-image-agent 是否可用"
    }
}
if (-not (Test-Path (Join-Path $configDir "reachability-metadata.json"))) {
    throw "缺少 native 元数据配置: $configDir\reachability-metadata.json（可先运行 .\build-native.ps1 -RegenerateConfig）"
}

# ---------- 4. native-image AOT 编译 ----------
Write-Step "native-image AOT 编译 (优化级别 -$OptimizationLevel)"
$outDir = Join-Path $ProjectRoot "target\native"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$nativeArgs = @(
    "-cp", $cp,
    "-H:Name=model2mc",
    "-H:Path=$outDir",
    "-H:IncludeResources=web/.*",
    "-H:IncludeResources=textures/.*",
    "-H:ConfigurationFileDirectories=$configDir",
    "-H:+ReportExceptionStackTraces",
    "-H:+AddAllCharsets",
    "--no-fallback",
    "-$OptimizationLevel",
    "org.example.Main"
)
& $nativeImage @nativeArgs
if ($LASTEXITCODE -ne 0) { throw "native-image 编译失败" }
$nativeExe = Join-Path $outDir "model2mc.exe"
if (-not (Test-Path $nativeExe)) { throw "未生成主程序: $nativeExe" }

# ---------- 5. 组装发布目录 dist ----------
Write-Step "组装发布目录 dist"
$dist = Join-Path $ProjectRoot "dist"
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force -Path $dist | Out-Null
# 主程序 + 运行所需的 jdk 库 DLL（AWT/ImageIO 等），缺一不可
Copy-Item (Join-Path $outDir "*") $dist

$palettes = Join-Path $ProjectRoot "model2mc-palettes"
if (Test-Path $palettes) {
    Copy-Item -Recurse $palettes (Join-Path $dist "model2mc-palettes")
} else {
    New-Item -ItemType Directory -Force -Path (Join-Path $dist "model2mc-palettes") | Out-Null
}

# ---------- 6. 编译启动器 启动.exe ----------
Write-Step "编译启动器 启动.exe"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) {
    throw "未找到 .NET Framework 编译器 csc.exe，无法生成启动器"
}
$launcherDir = Join-Path $ProjectRoot "target\launcher"
New-Item -ItemType Directory -Force -Path $launcherDir | Out-Null
$launcherCs = Join-Path $launcherDir "Launcher.cs"
$launcherExe = Join-Path $dist "启动.exe"
$iconIco = Join-Path $ProjectRoot "icon.ico"
if (Test-Path $iconIco) {
    Write-Host "使用现成图标: $iconIco" -ForegroundColor DarkGray
} else {
    Write-Host "未找到根目录 icon.ico，跳过 exe 图标" -ForegroundColor Yellow
}

$launcherSource = @'
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

class Model2mcLauncher {
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetConsoleOutputCP(uint cp);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetConsoleCP(uint cp);

    static string QuoteArg(string s) {
        if (string.IsNullOrEmpty(s)) return "\"\"";
        bool needQuote = s.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) >= 0;
        if (!needQuote) return s;
        var sb = new StringBuilder();
        sb.Append('"');
        for (int i = 0; i < s.Length; i++) {
            int backslashes = 0;
            while (i < s.Length && s[i] == '\\') { backslashes++; i++; }
            if (i >= s.Length) {
                sb.Append('\\', backslashes * 2);
                break;
            }
            if (s[i] == '"') {
                sb.Append('\\', backslashes * 2 + 1);
                sb.Append('"');
            } else {
                sb.Append('\\', backslashes);
                sb.Append(s[i]);
            }
        }
        sb.Append('"');
        return sb.ToString();
    }

    static int Main(string[] args) {
        // 主程序输出 UTF-8 字节；把控制台代码页切到 UTF-8，避免中文日志在默认 GBK(936) 下乱码
        SetConsoleOutputCP(65001);
        SetConsoleCP(65001);
        string dir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string exe = Path.Combine(dir, "model2mc.exe");
        if (!File.Exists(exe)) {
            Console.Error.WriteLine("未找到主程序: " + exe);
            return 1;
        }
        var psi = new ProcessStartInfo();
        psi.FileName = exe;
        psi.UseShellExecute = false;
        psi.WorkingDirectory = dir;
        if (args.Length > 0) {
            psi.Arguments = string.Join(" ", Array.ConvertAll(args, QuoteArg));
        }
        try {
            using (var p = Process.Start(psi)) {
                p.WaitForExit();
                return p.ExitCode;
            }
        } catch (Exception e) {
            Console.Error.WriteLine("启动失败: " + e.Message);
            return 1;
        }
    }
}
'@
Set-Content -Path $launcherCs -Value $launcherSource -Encoding UTF8
$iconArg = @()
if (Test-Path $iconIco) { $iconArg = @("/win32icon:$iconIco") }
& $csc /nologo /target:exe "/out:$launcherExe" @iconArg $launcherCs
if ($LASTEXITCODE -ne 0) { throw "启动器编译失败" }

# ---------- 7. 生成 README ----------
$readme = @'
model2mc 原生版（GraalVM native-image 构建）
============================================

文件说明:
  model2mc.exe        主程序（原生可执行文件）
  *.dll               运行所需的 JDK 库（AWT/ImageIO 等），必须与 exe 放在同一目录
  启动.exe            启动器（自动切换到自身所在目录后启动主程序，并透传命令行参数）
  model2mc-palettes/  方块颜色映射文件（可编辑；程序运行时会自动读写该目录）

使用方法:
  1. 双击 启动.exe  -> 启动 Web 界面（默认 http://127.0.0.1:8088）
  2. 命令行方式（在 dist 目录下执行）:
     启动.exe --model-count 1 --model-0-obj xxx.obj --model-0-mtl xxx.mtl --model-0-x 0 --model-0-y 64 --model-0-z 0 --world <存档目录>
     也可以直接运行 model2mc.exe，但必须保持工作目录为 dist（程序依赖相对路径）。

乱码说明:
  - 程序日志按 UTF-8 输出。建议统一使用 启动.exe，它会自动把控制台代码页切换为 UTF-8。
  - 若直接运行 model2mc.exe，在中文 Windows 默认 GBK(936) 代码页下中文日志会乱码，
    可在运行前手动执行  chcp 65001  切换到 UTF-8 代码页。

图标:
  - 项目根目录的 icon.ico 直接作为 exe 图标写入 启动.exe（/win32icon），无需额外转换。
  - 网页 favicon 使用 src/main/resources/web/icon.png。

注意:
  - model2mc.exe 与 *.dll、model2mc-palettes 目录必须一起分发，不能单独拷走 exe。
  - 首次运行会自动在 model2mc-palettes 下生成默认映射文件。
'@
Set-Content -Path (Join-Path $dist "README.txt") -Value $readme -Encoding UTF8

Write-Host ""
Write-Host "构建完成: $dist" -ForegroundColor Green
Get-ChildItem $dist | Select-Object Name, Length
