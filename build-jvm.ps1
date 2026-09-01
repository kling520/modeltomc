# ============================================================
# model2mc  JVM 版打包脚本 (Windows / PowerShell)
#
# 与 build-native.ps1（GraalVM native-image）不同，本脚本产出一个
# 自带 JVM 运行时的发布目录 dist_jvm：
#   - model2mc.jar + 依赖 jar  → lib\
#   - 精简 JRE（jlink 裁剪）    → jre\bin\java.exe
#   启动.exe      打包 JVM 的启动器（自动切到自身目录、透传参数、UTF-8 代码页）
#
# 用法:
#   .\build-jvm.ps1                        # 用 $env:JAVA_HOME 里的 JDK 做 jlink
#   .\build-jvm.ps1 -JDKHome "D:\...\jdk-25"
#
# 产物: dist_jvm\
# ============================================================
param(
    [string]$JDKHome = ""
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $ProjectRoot "target"
Set-Location $ProjectRoot

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}

if (-not $JDKHome) { $JDKHome = $env:JAVA_HOME }
if (-not $JDKHome -or -not (Test-Path $JDKHome)) {
    throw "未指定 JDK，请通过 -JDKHome 指定（需要 Java 25 运行时）"
}
Write-Host "使用 JDK: $JDKHome"

# ---------- 1. Maven 编译 ----------
Write-Step "Maven 编译并打包 (target/*.jar)"
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven 编译失败" }

# ---------- 2. 复制依赖 jar ----------
Write-Step "收集依赖 jar"
mvn -q dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt"
if ($LASTEXITCODE -ne 0) { throw "获取依赖 classpath 失败" }
$depJars = @()
if (Test-Path "target\classpath.txt") {
    $raw = (Get-Content "target\classpath.txt" -Raw).Trim()
    if ($raw) { $depJars = $raw -split ';' | Where-Object { $_ } }
}
$appJar = Get-ChildItem "target\model2mc-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*original*' } | Select-Object -First 1
if (-not $appJar) { throw "未找到应用 jar: target\model2mc-*.jar" }
Write-Host "应用 jar: $($appJar.Name)"
Write-Host "依赖 jar 数量: $($depJars.Count)"

# ---------- 3. jlink 精简运行时 ----------
Write-Step "jlink 生成精简 JRE"
$modules = "java.base,java.desktop,java.net.http,jdk.httpserver,java.management,java.logging"
$jlink = Join-Path $JDKHome "bin\jlink.exe"
if (-not (Test-Path $jlink)) { throw "在 $JDKHome 下未找到 jlink.exe" }
$distJvm = Join-Path $ProjectRoot "dist_jvm"
if (Test-Path $distJvm) { Remove-Item -Recurse -Force $distJvm }
New-Item -ItemType Directory -Force -Path $distJvm | Out-Null
$jreDir = Join-Path $distJvm "jre"
& $jlink --no-header-files --no-man-pages `
    --add-modules $modules `
    --output $jreDir
if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }
if (-not (Test-Path (Join-Path $jreDir "bin\java.exe"))) { throw "jlink 未生成 java.exe" }

# ---------- 4. 组装 lib / 资源 ----------
Write-Step "组装 dist_jvm 目录"
$libDir = Join-Path $distJvm "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
Copy-Item $appJar.FullName (Join-Path $libDir "model2mc.jar")
foreach ($j in $depJars) {
    if (Test-Path $j) { Copy-Item $j $libDir }
}

$palettes = Join-Path $ProjectRoot "model2mc-palettes"
if (Test-Path $palettes) {
    Copy-Item -Recurse $palettes (Join-Path $distJvm "model2mc-palettes")
} else {
    New-Item -ItemType Directory -Force -Path (Join-Path $distJvm "model2mc-palettes") | Out-Null
}

# ---------- 5. 图标 ----------
Write-Step "图标"
$iconIco = Join-Path $ProjectRoot "icon.ico"
if (Test-Path $iconIco) {
    Write-Host "使用现成图标: $iconIco" -ForegroundColor DarkGray
} else {
    Write-Host "未找到根目录 icon.ico，跳过 exe 图标" -ForegroundColor Yellow
}

# ---------- 6. 编译启动器 启动.exe ----------
Write-Step "编译启动器 启动.exe"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { throw "未找到 csc.exe，无法生成启动器" }
$launcherCs = Join-Path $target "LauncherJvm.cs"
$launcherExe = Join-Path $distJvm "启动.exe"

# 构建 classpath（相对路径，装入启动器源码，避免运行时依赖 *. 通配）
$cpParts = @("lib\model2mc.jar")
$libJars = Get-ChildItem $libDir -Filter '*.jar' | Where-Object { $_.Name -ne 'model2mc.jar' } | Select-Object -ExpandProperty Name
foreach ($n in $libJars) { $cpParts += "lib\$n" }
$classpath = $cpParts -join ';'

$launcherSource = @'
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

class Model2mcJvmLauncher {
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
            if (i >= s.Length) { sb.Append('\\', backslashes * 2); break; }
            if (s[i] == '"') { sb.Append('\\', backslashes * 2 + 1); sb.Append('"'); }
            else { sb.Append('\\', backslashes); sb.Append(s[i]); }
        }
        sb.Append('"');
        return sb.ToString();
    }

    static int Main(string[] args) {
        // 主程序输出 UTF-8 字节；把控制台代码页切到 UTF-8，避免中文日志在默认 GBK(936) 下乱码
        SetConsoleOutputCP(65001);
        SetConsoleCP(65001);
        string dir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string java = Path.Combine(dir, "jre", "bin", "java.exe");
        if (!File.Exists(java)) {
            Console.Error.WriteLine("未找到运行时: " + java);
            return 1;
        }
        var psi = new ProcessStartInfo();
        psi.FileName = java;
        psi.UseShellExecute = false;
        psi.WorkingDirectory = dir;
        var sb = new StringBuilder();
        sb.Append("-cp ").Append(QuoteArg("__CLASSPATH__")).Append(" org.example.Main");
        foreach (var a in args) { sb.Append(' ').Append(QuoteArg(a)); }
        psi.Arguments = sb.ToString();
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
$launcherSource = $launcherSource.Replace("__CLASSPATH__", $classpath.Replace('\', '\\'))
Set-Content -Path $launcherCs -Value $launcherSource -Encoding UTF8
$iconArg = @()
if (Test-Path $iconIco) { $iconArg = @("/win32icon:$iconIco") }
& $csc /nologo /target:exe "/out:$launcherExe" @iconArg $launcherCs
if ($LASTEXITCODE -ne 0) { throw "启动器编译失败" }

# ---------- 7. 生成 README ----------
$readme = @'
model2mc JVM 版（自带精简 JRE）
==============================

文件说明:
  jre/                精简 Java 运行时（jlink 裁剪），程序运行所需，必须随目录一起分发
  lib/model2mc.jar    主程序
  lib/*.jar           依赖库
  启动.exe            启动器（自动切换到自身目录、以 jre\bin\java.exe 启动主程序并透传参数）
  model2mc-palettes/  方块颜色映射文件（可编辑；程序运行时会自动读写该目录）

使用方法:
  1. 双击 启动.exe  -> 启动 Web 界面（默认 http://127.0.0.1:8088）
  2. 命令行方式（在 dist_jvm 目录下执行）:
     启动.exe --model-count 1 --model-0-obj xxx.obj --model-0-mtl xxx.mtl --model-0-x 0 --model-0-y 64 --model-0-z 0 --world <存档目录>
     也可直接运行 jre\bin\java.exe -cp "lib\model2mc.jar;lib\*" org.example.Main
       但必须保持工作目录为 dist_jvm（程序依赖相对路径）。

乱码说明:
  - 程序日志按 UTF-8 输出。启动器会自动把控制台代码页切换为 UTF-8。
  - 若直接用 java.exe 运行，在中文 Windows 默认 GBK(936) 代码页下中文日志会乱码，
    可在运行前先执行  chcp 65001  切换到 UTF-8 代码页。

说明:
  - 与 dist（native-image 原生版）不同，dist_jvm 需要 jre 目录，整体一起分发。
  - 依赖本机 JDK 兼容性；若更换机器，只要 jre 随目录走即可，无需安装 Java。
'@
Set-Content -Path (Join-Path $distJvm "README.txt") -Value $readme -Encoding UTF8

Write-Host ""
Write-Host "JVM 版构建完成: $distJvm" -ForegroundColor Green
Get-ChildItem $distJvm | Select-Object Name, Length