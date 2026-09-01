package org.example;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class WebUiServer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8088;

    private final Object stateLock = new Object();
    private final PrintStream consoleOut = System.out;
    /** 任务互斥：预览与写入都会体素化大模型并占用大量堆内存，禁止两者并发执行（叠加会 OOM）。 */
    private final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "model2mc-webui-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> logLines = new ArrayList<>();

    private HttpServer server;
    private boolean running;
    private int currentJobId;
    private int nextJobId;
    private String lastStatus = "空闲";
    private String lastError = "";

    /** 最近一次预览任务的状态（前端轮询 /api/preview-status）。 */
    private volatile TaskProgress previewProgress;
    private volatile Map<String, Object> previewResult;
    private volatile String previewError;
    private volatile int previewJobSeq;
    /** 预览结果的序列化缓存：完成后只序列化一次，之后轮询/重取直接复用，避免反复序列化上亿字节导致 OOM。 */
    private volatile byte[] previewResultJson;
    /** 最近一次写入任务的进度（随 /api/status 轮询）。 */
    private volatile TaskProgress runProgress;

    void start() throws IOException {
        // 重启旧进程换新堆内存时端口可能仍被占用：短时重试，避免「Address already in use」
        IOException bindFailure = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
                bindFailure = null;
                break;
            } catch (java.net.BindException e) {
                bindFailure = e;
                appendLog("端口 " + PORT + " 被占用，等待旧进程退出后重试...");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (bindFailure != null) {
            throw bindFailure;
        }
        server.createContext("/", this::handleRoot);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/app-config", this::handleAppConfig);
        server.createContext("/api/restart", this::handleRestart);
        server.createContext("/api/palette", this::handlePalette);
        server.createContext("/api/block-texture", this::handleBlockTexture);
        server.createContext("/api/open-directory", this::handleOpenDirectory);
        server.createContext("/api/list-dir", this::handleListDir);
        server.createContext("/api/preview", this::handlePreview);
        server.createContext("/api/preview-status", this::handlePreviewStatus);
        server.createContext("/api/preview-result", this::handlePreviewResult);
        server.createContext("/api/run", this::handleRun);
        server.createContext("/api/export", this::handleExport);
        server.createContext("/api/status", this::handleStatus);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "model2mc-webui-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        String url = "http://" + HOST + ":" + PORT + "/";
        appendLog("Web UI 已启动: " + url);
        appendLog("参数缓存: 浏览器本地缓存");
        openBrowser(url);
    }

    /** 在系统的文件管理器里打开指定目录（仅限真实存在的目录，映射目录/导出目录等本机路径）。 */
    private void handleOpenDirectory(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, POST, OPTIONS")) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            Map<String, String> query = new LinkedHashMap<>();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq >= 0) {
                        query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
                    }
                }
            }
            String target = query.getOrDefault("path", "");
            if (target.isBlank()) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "缺少 path 参数"));
                return;
            }
            Path dir = Paths.get(target).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(dir)) {
                sendJson(exchange, 404, Map.of("ok", false, "error", "目录不存在: " + dir));
                return;
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir.toFile());
                    sendJson(exchange, 200, Map.of("ok", true, "path", dir.toString()));
                } else {
                    sendJson(exchange, 500, Map.of("ok", false, "error", "当前环境不支持打开文件管理器"));
                }
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("ok", false,
                        "error", "打开失败: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
            }
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void handleListDir(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            Map<String, String> query = new LinkedHashMap<>();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq >= 0) {
                        query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
                    }
                }
            }
            String target = query.getOrDefault("path", "").trim();
            List<Map<String, Object>> entries = new ArrayList<>();
            String current = "";
            String parent = "";
            if (target.isEmpty()) {
                // 空路径：返回磁盘根（Windows 盘符），进入后先选盘再进文件夹
                for (java.io.File root : java.io.File.listRoots()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", root.getPath());
                    entry.put("path", root.getPath());
                    entry.put("isDir", true);
                    entries.add(entry);
                }
            } else {
                Path dir = Paths.get(target).toAbsolutePath().normalize();
                if (!java.nio.file.Files.isDirectory(dir)) {
                    sendJson(exchange, 404, Map.of("ok", false, "error", "目录不存在: " + dir));
                    return;
                }
                current = dir.toString();
                Path parentPath = dir.getParent();
                parent = parentPath != null ? parentPath.toString() : "";
                try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(dir)) {
                    stream.limit(20000).forEach(p -> {
                        try {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", p.getFileName() != null ? p.getFileName().toString() : p.toString());
                            entry.put("path", p.toString());
                            entry.put("isDir", java.nio.file.Files.isDirectory(p));
                            entries.add(entry);
                        } catch (Exception ignored) {
                            // 单个条目读取失败（如权限受限）直接跳过
                        }
                    });
                }
            }
            // 目录在前，其次按名称排序
            entries.sort((a, b) -> {
                boolean da = Boolean.TRUE.equals(a.get("isDir"));
                boolean db = Boolean.TRUE.equals(b.get("isDir"));
                if (da != db) {
                    return da ? -1 : 1;
                }
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            });
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("path", current);
            payload.put("parent", parent);
            payload.put("entries", entries);
            sendJson(exchange, 200, payload);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, HEAD, OPTIONS")) {
                return;
            }
            if (!isGetLike(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if ("/favicon.ico".equals(path) || "/icon.png".equals(path)) {
                byte[] icon = readResource("/web/icon.png");
                if (icon == null) {
                    sendBytes(exchange, 404, "image/png", new byte[0]);
                } else {
                    sendBytes(exchange, 200, "image/png", icon);
                }
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] content = readResource("/web/index.html");
                sendBytes(exchange, 200, "text/html; charset=UTF-8", content);
                return;
            }
            if ("/palette.html".equals(path)) {
                byte[] content = readResource("/web/palette.html");
                sendBytes(exchange, 200, "text/html; charset=UTF-8", content);
                return;
            }
            if ("/config.html".equals(path)) {
                byte[] content = readResource("/web/config.html");
                sendBytes(exchange, 200, "text/html; charset=UTF-8", content);
                return;
            }

            sendJson(exchange, 404, Map.of("error", "未找到资源"));
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, POST, HEAD, OPTIONS")) {
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("values", UiSettingsStore.defaults());
                payload.put("settingsPath", "浏览器本地缓存");
                payload.put("paletteProfiles", summarizeProfiles());
                payload.put("defaultProfileId", PaletteStore.DEFAULT_PROFILE_ID);
                payload.put("paletteStorePath", PaletteStore.storePath().toString());
                // 导出目录留空时的实际落盘位置（软件运行目录下的 exports），前端用作 placeholder
                payload.put("defaultExportDir", Paths.get("exports").toAbsolutePath().normalize().toString());
                payload.put("running", isRunning());
                sendJson(exchange, 200, payload);
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                sendJson(exchange, 200, Map.of(
                        "ok", true,
                        "settingsPath", "浏览器本地缓存"
                ));
                return;
            }

            sendMethodNotAllowed(exchange);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    /** 应用级配置（config.json）：读当前堆内存配置与运行时实况，写 heapMB。 */
    private void handleAppConfig(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, POST, HEAD, OPTIONS")) {
                return;
            }
            String method = exchange.getRequestMethod();
            if (isGetLike(exchange)) {
                Map<String, String> fileValues = AppConfig.load();
                long physicalMB = AppConfig.physicalMemoryBytes() / (1024 * 1024);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("heapMB", AppConfig.heapMB());
                payload.put("defaultHeapMB", AppConfig.defaultHeapMB());
                payload.put("currentHeapMB", AppConfig.currentHeapMB());
                payload.put("physicalMB", physicalMB);
                payload.put("configPath", AppConfig.CONFIG_PATH.toAbsolutePath().normalize().toString());
                payload.put("source", fileValues.containsKey("heapMB") ? "file" : "default");
                sendJson(exchange, 200, payload);
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                Map<String, String> form = readForm(exchange);
                String raw = form.getOrDefault("heapMB", "").trim();
                if (raw.isEmpty()) {
                    sendJson(exchange, 400, Map.of("error", "缺少 heapMB 参数"));
                    return;
                }
                int heapMB;
                try {
                    heapMB = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    sendJson(exchange, 400, Map.of("error", "堆内存必须是整数（MB）"));
                    return;
                }
                if (heapMB < AppConfig.MIN_HEAP_MB || heapMB > AppConfig.MAX_HEAP_MB) {
                    sendJson(exchange, 400, Map.of(
                            "error", "堆内存需在 " + AppConfig.MIN_HEAP_MB + " ~ " + AppConfig.MAX_HEAP_MB + " MB 之间"));
                    return;
                }
                // 保留已有配置项，只更新 heapMB（数字写回时不带引号）
                Map<String, String> values = AppConfig.load();
                values.put("heapMB", String.valueOf(heapMB));
                AppConfig.save(values);
                appendLog("应用配置已保存: 堆内存 " + heapMB + " MB → " + AppConfig.CONFIG_PATH.toAbsolutePath().normalize());
                sendJson(exchange, 200, Map.of("ok", true, "restartRequired", true));
                return;
            }

            sendMethodNotAllowed(exchange);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    /** 带新堆内存参数重启本服务：先拉起新进程（后台写日志），再退出当前进程释放端口。 */
    private void handleRestart(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "POST, OPTIONS")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            if (busy.get()) {
                sendJson(exchange, 409, Map.of("error", "有任务正在执行，请结束后再重启"));
                return;
            }
            int heapMB = AppConfig.heapMB();
            spawnRestartProcess(heapMB);
            // 先回复，再退出，让浏览器有机会收到成功响应
            new Thread(() -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    // 退出姿势被打断也没关系，主进程马上退出
                }
                appendLog("按配置重启（堆内存 " + heapMB + " MB）...");
                System.exit(0);
            }, "model2mc-restart-exit").start();
            sendJson(exchange, 200, Map.of("ok", true, "heapMB", heapMB));
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    /** 用当前 classpath 拉起新的 java 进程，输出重定向到日志文件，避免弹新控制台窗口。 */
    private void spawnRestartProcess(int heapMB) throws IOException {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java")
                .toAbsolutePath().normalize().toString();
        String classpath = System.getProperty("java.class.path");
        Path logDir = Paths.get("logs");
        Files.createDirectories(logDir);
        Path logFile = logDir.resolve("server.log");
        ProcessBuilder process = new ProcessBuilder(javaBin,
                "-Xmx" + heapMB + "m",
                "-Dfile.encoding=UTF-8",
                "-cp", classpath,
                "org.example.Main");
        process.directory(Paths.get("").toAbsolutePath().normalize().toFile());
        process.redirectErrorStream(true);
        process.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        process.start();
    }

    /** 把堆溢出等致命错误转成人话；其余异常原样返回。 */
    private static String friendlyError(Throwable e) {
        if (e instanceof OutOfMemoryError) {
            long maxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            return "内存不足：" + maxMB + " MB 堆内存已耗尽，请降低目标高度/体素参数，或到「设置」页调大堆内存后重启。";
        }
        return Objects.toString(e.getMessage(), e.getClass().getSimpleName());
    }

    private void handlePalette(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, POST, HEAD, OPTIONS")) {
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
                sendJson(exchange, 200, buildPalettePayload(query.get("profile")));
                return;
            }

            if ("POST".equalsIgnoreCase(method)) {
                Map<String, String> form = readForm(exchange);
                String action = form.getOrDefault("action", "").trim().toLowerCase(java.util.Locale.ROOT);
                String profileId = form.getOrDefault("profile", form.get("id"));
                switch (action) {
                    case "create" -> PaletteStore.createProfile(
                            form.get("id"),
                            form.get("inherits"),
                            form.get("mode"));
                    case "delete" -> PaletteStore.deleteProfile(profileId);
                    case "restore-defaults" -> PaletteStore.restoreDefaultProfile(profileId);
                    default -> PaletteStore.saveProfile(
                            profileId,
                            form.get("inherits"),
                            form.get("mode"),
                            parsePaletteForm(form));
                }
                sendJson(exchange, 200, Map.of(
                        "ok", true,
                        "storePath", PaletteStore.storePath().toString()
                ));
                return;
            }

            sendMethodNotAllowed(exchange);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> buildPalettePayload(String profileId) throws IOException {
        PaletteStore.PaletteProfile profile = PaletteStore.loadProfile(profileId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profiles", summarizeProfiles());
        payload.put("defaultProfileId", PaletteStore.DEFAULT_PROFILE_ID);
        payload.put("currentProfile", Map.of(
                "id", profile.id(),
                "inherits", Objects.toString(profile.inherits(), ""),
                "mode", profile.mode(),
                "builtin", profile.builtin(),
                "path", profile.path().toString()
        ));
        payload.put("entries", buildAllPaletteEntries(profile));
        payload.put("storePath", PaletteStore.storePath().toString());
        payload.put("blockOptions", MinecraftBlockTextures.buildBlockOptions(profile.effectiveEntries().keySet()));
        payload.put("textureAvailable", MinecraftBlockTextures.available());
        payload.put("textureSource", MinecraftBlockTextures.sourcePath());
        return payload;
    }

    /** 返回当前映射文件的完整条目；自定义文件在创建时已复制模板内容，运行时不再做继承展开。 */
    private List<Map<String, Object>> buildAllPaletteEntries(PaletteStore.PaletteProfile profile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : profile.effectiveEntries().entrySet()) {
            String block = entry.getKey();
            String source = profile.builtin() ? "default" : "current";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("block", block);
            row.put("color", entry.getValue());
            row.put("custom", !"default".equals(source));
            row.put("source", source);
            row.put("sourceLabel", switch (source) {
                case "current" -> "当前";
                default -> "默认";
            });
            entries.add(row);
        }
        return entries;
    }

    private List<Map<String, Object>> summarizeProfiles() throws IOException {
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (PaletteStore.ProfileSummary profile : PaletteStore.listProfiles()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", profile.id());
            row.put("mode", profile.mode());
            row.put("inherits", Objects.toString(profile.inherits(), ""));
            row.put("builtin", profile.builtin());
            row.put("path", profile.path().toString());
            profiles.add(row);
        }
        return profiles;
    }

    private void handleBlockTexture(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, HEAD, OPTIONS")) {
                return;
            }
            if (!isGetLike(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            String block = query.getOrDefault("block", "").trim();
            String style = query.getOrDefault("style", "icon").trim().toLowerCase(java.util.Locale.ROOT);
            if (block.isBlank()) {
                throw new IllegalArgumentException("缺少 block 参数");
            }

            MinecraftBlockTextures.Texture texture = switch (style) {
                case "flat" -> MinecraftBlockTextures.flatTextureFor(block);
                default -> MinecraftBlockTextures.textureFor(block);
            };
            if (texture == null) {
                sendBytes(exchange, 404, "image/png", new byte[0]);
                return;
            }

            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            sendBytes(exchange, 200, texture.contentType(), texture.bytes());
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                sendFailure(exchange, e);
            } else {
                sendBytes(exchange, 404, "image/png", new byte[0]);
            }
        } finally {
            exchange.close();
        }
    }

    /** 解析表单里的条目: count=N&block0=..&color0=..&block1=..&color1=.. */
    private List<Map<String, String>> parsePaletteForm(Map<String, String> form) {
        List<Map<String, String>> rows = new ArrayList<>();
        int count = 0;
        try {
            count = Integer.parseInt(form.getOrDefault("count", "0"));
        } catch (NumberFormatException ignored) {
            // ignore invalid count
        }
        count = Math.min(Math.max(0, count), 500);
        for (int i = 0; i < count; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("block", form.getOrDefault("block" + i, ""));
            row.put("color", form.getOrDefault("color" + i, ""));
            rows.add(row);
        }
        return rows;
    }

    private void handleRun(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "POST, OPTIONS")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            // 预览或另一写入正在执行时拒绝，避免两个大内存任务叠加 OOM
            if (!busy.compareAndSet(false, true)) {
                sendJson(exchange, 409, Map.of("error", "有任务正在执行（预览或写入），请稍候再试"));
                return;
            }

            boolean submitted = false;
            try {
                Map<String, String> values = mergeWithDefaults(readForm(exchange));
                // 写入存档：走完整参数（含 --world/--dimension），多模型全部写入（modelCount 用前端提交的真实值）
                Config config = Config.fromArgs(buildArgs(values));

                int jobId;
                synchronized (stateLock) {
                    if (running) {
                        throw new IllegalStateException("已有任务正在运行");
                    }
                    running = true;
                    currentJobId = ++nextJobId;
                    jobId = currentJobId;
                    logLines.clear();
                    lastStatus = "运行中";
                    lastError = "";
                }

                appendLog("开始写入存档...");
                appendLog("目标 region: " + config.regionDirectory());

                taskExecutor.submit(() -> runConversion(jobId, config));
                submitted = true;
                sendJson(exchange, 200, Map.of("ok", true, "jobId", jobId));
            } catch (Exception e) {
                if (!submitted) {
                    synchronized (stateLock) {
                        if (running) {
                            running = false;
                        }
                    }
                    busy.set(false);
                }
                sendFailure(exchange, e);
            }
        } finally {
            exchange.close();
        }
    }

    private void handleExport(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "POST, OPTIONS")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            // 预览或写入正在执行时拒绝，避免两个大内存任务叠加 OOM
            if (!busy.compareAndSet(false, true)) {
                sendJson(exchange, 409, Map.of("error", "有任务正在执行（预览或写入），请稍候再试"));
                return;
            }

            boolean submitted = false;
            try {
                Map<String, String> values = mergeWithDefaults(readForm(exchange));
                String format = values.getOrDefault("format", "").toLowerCase(java.util.Locale.ROOT);
                if (!"litematic".equals(format)) {
                    throw new IllegalArgumentException("不支持的导出格式: " + format + "，仅支持 litematic");
                }
                int modelCount = Math.min(parseModelCount(values), 1);
                String exportDirRaw = values.get("exportDir");
                Path exportDir = (exportDirRaw == null || exportDirRaw.isBlank())
                        ? Paths.get("exports")
                        : Paths.get(exportDirRaw);
                Config config = Config.fromArgsForExport(buildExportArgs(values, modelCount));

                int jobId;
                synchronized (stateLock) {
                    if (running) {
                        throw new IllegalStateException("已有任务正在运行");
                    }
                    running = true;
                    currentJobId = ++nextJobId;
                    jobId = currentJobId;
                    logLines.clear();
                    lastStatus = "运行中";
                    lastError = "";
                }

                appendLog("开始导出 Litematica 投影（只生成文件，不修改存档）...");

                taskExecutor.submit(() -> runExport(jobId, config, format, exportDir));
                submitted = true;
                sendJson(exchange, 200, Map.of("ok", true, "jobId", jobId));
            } catch (Exception e) {
                if (!submitted) {
                    synchronized (stateLock) {
                        if (running) {
                            running = false;
                        }
                    }
                    busy.set(false);
                }
                sendFailure(exchange, e);
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePreview(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "POST, OPTIONS")) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendMethodNotAllowed(exchange);
                return;
            }
            // 预览或写入正在执行时拒绝，避免两个大内存任务叠加 OOM
            if (!busy.compareAndSet(false, true)) {
                sendJson(exchange, 409, Map.of("error", "有任务正在执行（预览或写入），请稍候再试"));
                return;
            }

            int jobId;
            try {
                Map<String, String> values = mergeWithDefaults(readForm(exchange));
                String[] args = buildArgs(values);
                Config config = Config.fromArgsForPreview(args);
                // 预览结果磁盘缓存：完全相同的请求（参数+映射+模型文件+存档 region 均未变）直接回上次 JSON
                byte[] cachedJson = DiskCache.readBytes(DiskCache.previewFile(previewCacheKey(args, config)));
                TaskProgress progress = new TaskProgress();
                synchronized (stateLock) {
                    previewProgress = progress;
                    previewResult = null;
                    previewError = null;
                    previewResultJson = null;
                    previewJobSeq++;
                    jobId = previewJobSeq;
                }
                // 异步执行：前端轮询 /api/preview-status 显示进度条，完成后一次性取结果
                taskExecutor.submit(() -> {
                    try {
                        if (cachedJson != null) {
                            // 命中磁盘缓存：跳过体素化/提取/序列化全部阶段，直接回放缓存 JSON。
                            // previewResult 只作为“有结果”的非空标记，结果端点优先用 previewResultJson 原文
                            progress.phase = "cache";
                            previewResult = Map.of("fromDiskCache", true);
                            previewResultJson = cachedJson;
                            String mb = String.valueOf(cachedJson.length / 1024 / 1024);
                            // 详细命中摘要：大小 + 命中的缓存类型（完整预览结果）+ 关键参数（高度/映射/模型），
                            // 与「重建后写缓存」区分开，便于确认本次没有重新体素化
                            String params = "高度=" + values.getOrDefault("height", "-")
                                    + " 最大边长=" + values.getOrDefault("maxDimension", "-")
                                    + " 映射=" + values.getOrDefault("paletteProfile", "-")
                                    + " 三角采样=" + values.getOrDefault("maxTriangleSamples", "-");
                            String modelName = "";
                            String obj = values.getOrDefault("obj0", "");
                            int slash = Math.max(obj.lastIndexOf('\\'), obj.lastIndexOf('/'));
                            if (slash >= 0 && slash < obj.length() - 1) {
                                modelName = obj.substring(slash + 1);
                            }
                            System.out.println("预览命中磁盘缓存[完整结果]: " + mb + " MB，模型 " + modelName + "，参数 " + params);
                            appendLog("预览命中磁盘缓存（完整结果缓存，跳过体素化/表面提取/序列化）: "
                                    + mb + " MB | 模型: " + (modelName.isEmpty() ? obj : modelName) + " | " + params);
                            return;
                        }
                        Map<String, Object> result = PreviewSupport.generate(config, progress);
                        previewResult = result;
                        // 序列化挪进后台任务并按字节上报百分比：大结果序列化要十几秒，
                        // 放在取结果时才做会让进度条在“接收预览数据”上卡很久。
                        // 本地工具不做 gzip：压缩 38MB 要 5 秒，省下的本机传输时间远小于此
                        progress.phase = "serialize";
                        progress.subDone = 0;
                        long estimatedBytes = estimateResultJsonLength(result);
                        progress.subTotal = estimatedBytes;
                        // 预估即精确字节数（探针 + 位数统计与实际写出逐字节一致），
                        // 直接当初始容量：免掉 16MB→1GB 的 6 次倍增拷贝（约 1.7GB memcpy）
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                                (int) Math.min(Integer.MAX_VALUE - 64L, estimatedBytes + 64L));
                        CountingProgressOutputStream counting =
                                new CountingProgressOutputStream(buffer, progress, estimatedBytes);
                        // 64KB 缓冲是最大头：此前 2.8 亿个数字每个两次 synchronized 的
                        // ByteArrayOutputStream 微写（逗号单字节 + 数字短数组），锁与虚调用
                        // 开销压倒一切；合并成 64KB 大块后写入调用数降 4 个数量级
                        java.io.BufferedOutputStream buffered =
                                new java.io.BufferedOutputStream(counting, 64 * 1024);
                        writeJson(buffered, result);
                        buffered.flush();
                        previewResultJson = buffer.toByteArray();
                        System.out.println("预览结果已序列化: " + (previewResultJson.length / 1024 / 1024) + " MB");
                        // 结果落地：下次相同请求直接回放；写缓存失败不影响本次预览返回
                        try {
                            Path cacheFile = DiskCache.previewFile(previewCacheKey(args, config));
                            DiskCache.writeAtomically(cacheFile, out -> out.write(previewResultJson));
                            DiskCache.enforceBudget(cacheFile.getParent(), DiskCache.PREVIEW_BUDGET_BYTES);
                        } catch (IOException cacheWriteFailure) {
                            System.out.println("预览结果磁盘缓存写入失败（不影响预览）: " + cacheWriteFailure.getMessage());
                        }
                    } catch (Throwable e) {
                        // 用 Throwable 接住 OOM 等 Error：之前只 catch(Exception)，
                        // 堆溢出时任务静默死亡，前端既无报错也无结果，看起来就是“体素化完就没动作了”
                        String message = friendlyError(e);
                        previewError = message;
                        progress.error = message;
                        appendLog("预览失败: " + message);
                        e.printStackTrace(System.out);
                    } finally {
                        busy.set(false);
                        progress.finished = true;
                    }
                });
                sendJson(exchange, 200, Map.of("ok", true, "jobId", jobId));
            } catch (Exception e) {
                busy.set(false);
                sendFailure(exchange, e);
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePreviewStatus(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, HEAD, OPTIONS")) {
                return;
            }
            if (!isGetLike(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            int requestJobId = Integer.parseInt(query.getOrDefault("jobId", "-1"));
            Map<String, Object> payload = new LinkedHashMap<>();
            TaskProgress progress;
            Map<String, Object> result;
            synchronized (stateLock) {
                progress = previewProgress;
                result = previewResult;
                payload.put("jobId", previewJobSeq);
                payload.put("busy", busy.get());
            }
            // 前端请求的 jobId 与最新任务不一致（旧任务已被新预览覆盖）时不返回结果
            boolean current = progress != null && requestJobId == previewJobSeq;
            payload.put("phase", current ? progress.phase : "idle");
            payload.put("done", current ? progress.done : 0);
            payload.put("total", current ? progress.total : 1);
            payload.put("subDone", current ? progress.subDone : 0L);
            payload.put("subTotal", current ? progress.subTotal : 0L);
            payload.put("finished", current && progress.finished);
            payload.put("error", current ? previewError : null);
            // 大结果不再内嵌在轮询响应里：进度轮询保持轻量，完成后前端用 /api/preview-result 取结果
            payload.put("hasResult", current && progress.finished && result != null);
            sendJson(exchange, 200, payload);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    /** 预览结果专用端点：序列化一次后缓存原文，浏览器轮询状态时零开销。 */
    private void handlePreviewResult(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, HEAD, OPTIONS")) {
                return;
            }
            if (!isGetLike(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            int requestJobId = Integer.parseInt(query.getOrDefault("jobId", "-1"));
            Map<String, Object> result;
            int currentSeq;
            synchronized (stateLock) {
                result = previewResult;
                currentSeq = previewJobSeq;
            }
            if (requestJobId != currentSeq || result == null) {
                sendJson(exchange, 409, Map.of("error", "预览结果不存在或已被新任务覆盖"));
                return;
            }

            byte[] json = previewResultJson;
            if (json == null) {
                // 正常路径下序列化已在后台任务完成；此兜底仅覆盖旧任务遗留的 result 被重取的情况
                long estimated = estimateResultJsonLength(result);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                        (int) Math.min(Integer.MAX_VALUE - 64L, estimated + 64L));
                java.io.BufferedOutputStream buffered =
                        new java.io.BufferedOutputStream(buffer, 64 * 1024);
                writeJson(buffered, result);
                buffered.flush();
                json = buffer.toByteArray();
                previewResultJson = json;
                System.out.println("预览结果已序列化: " + (json.length / 1024 / 1024) + " MB");
            }

            // X-Decompressed-Length 与 Content-Length 一致：前端流式接收按它算接收百分比
            exchange.getResponseHeaders().set("X-Decompressed-Length", String.valueOf(json.length));
            sendBytes(exchange, 200, "application/json; charset=UTF-8", json);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (handleOptions(exchange, "GET, HEAD, OPTIONS")) {
                return;
            }
            if (!isGetLike(exchange)) {
                sendMethodNotAllowed(exchange);
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            int cursor = Integer.parseInt(query.getOrDefault("cursor", "0"));
            Map<String, Object> payload;
            synchronized (stateLock) {
                int safeCursor = Math.max(0, Math.min(cursor, logLines.size()));
                payload = new LinkedHashMap<>();
                payload.put("running", running);
                payload.put("jobId", currentJobId);
                payload.put("status", lastStatus);
                payload.put("error", lastError);
                payload.put("cursor", logLines.size());
                payload.put("lines", new ArrayList<>(logLines.subList(safeCursor, logLines.size())));
                TaskProgress taskProgress = running ? runProgress : null;
                payload.put("progress", taskProgress == null ? null : Map.of(
                        "phase", taskProgress.phase,
                        "done", taskProgress.done,
                        "total", taskProgress.total,
                        "subDone", taskProgress.subDone,
                        "subTotal", taskProgress.subTotal));
            }
            sendJson(exchange, 200, payload);
        } catch (Exception e) {
            sendFailure(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void runConversion(int jobId, Config config) {
        TaskProgress progress = new TaskProgress();
        synchronized (stateLock) {
            runProgress = progress;
        }
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream redirected = createLogPrintStream();
        try {
            System.setOut(redirected);
            System.setErr(redirected);
            new Converter(config).run(progress);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    lastStatus = "完成";
                }
            }
            appendLog("完成。");
        } catch (Throwable e) {
            String message = friendlyError(e);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    lastStatus = "失败";
                    lastError = message;
                }
            }
            appendLog("失败: " + message);
            e.printStackTrace(redirected);
            progress.error = message;
        } finally {
            redirected.flush();
            redirected.close();
            System.setOut(originalOut);
            System.setErr(originalErr);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    running = false;
                }
            }
            progress.finished = true;
            busy.set(false);
        }
    }

    private void runExport(int jobId, Config config, String format, Path exportDir) {
        TaskProgress progress = new TaskProgress();
        synchronized (stateLock) {
            runProgress = progress;
        }
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream redirected = createLogPrintStream();
        try {
            System.setOut(redirected);
            System.setErr(redirected);
            Path output = ExportSupport.export(config, format, progress, exportDir);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    lastStatus = "完成";
                }
            }
            appendLog("导出完成: " + output.toAbsolutePath().normalize());
        } catch (Throwable e) {
            String message = friendlyError(e);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    lastStatus = "失败";
                    lastError = message;
                }
            }
            appendLog("导出失败: " + message);
            e.printStackTrace(redirected);
            progress.error = message;
        } finally {
            redirected.flush();
            redirected.close();
            System.setOut(originalOut);
            System.setErr(originalErr);
            synchronized (stateLock) {
                if (currentJobId == jobId) {
                    running = false;
                }
            }
            progress.finished = true;
            busy.set(false);
        }
    }

    private PrintStream createLogPrintStream() {
        OutputStream output = new OutputStream() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                buffer.write(b);
                if (b == '\n') {
                    flush();
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    write(b[i]);
                }
            }

            @Override
            public void flush() {
                if (buffer.size() == 0) {
                    return;
                }
                String text = buffer.toString(StandardCharsets.UTF_8);
                buffer.reset();
                for (String line : text.split("\\R", -1)) {
                    if (!line.isEmpty()) {
                        appendLog(line);
                    }
                }
            }
        };
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    /**
     * 预览磁盘缓存 key：请求参数全文 + 映射文件内容签名 + 模型/贴图文件状态 + 存档 region 文件状态。
     * 任一输入变化（改参数/换映射/改模型文件/存档变动）都会得到不同的 key，天然防止缓存串味。
     */
    private String previewCacheKey(String[] args, Config config) {
        StringBuilder builder = new StringBuilder(1024);
        builder.append("blend=v2\u0000");
        for (String arg : args) {
            builder.append(arg).append('\u0000');
        }
        builder.append("|palette=").append(config.paletteSignature);
        for (PlacedModel model : config.models) {
            appendFileStat(builder, model.objPath());
            appendFileStat(builder, model.mtlPath());
            appendFileStat(builder, model.texturePath());
        }
        if (config.worldPath != null) {
            try {
                appendRegionStat(builder, config.regionDirectory());
            } catch (Exception missing) {
                builder.append("|region=unresolved");
            }
        }
        return builder.toString();
    }

    private void appendFileStat(StringBuilder builder, Path path) {
        if (path == null) {
            builder.append("|null");
            return;
        }
        builder.append('|').append(path.toAbsolutePath().normalize());
        try {
            builder.append('@').append(Files.size(path))
                    .append(':').append(Files.getLastModifiedTime(path).toMillis());
        } catch (IOException missing) {
            builder.append("@missing");
        }
    }

    /** region 目录内容指纹：目录下全部 .mca 的名字+大小+修改时间。存档变动后旧预览缓存自动失效。 */
    private void appendRegionStat(StringBuilder builder, Path regionDir) {
        builder.append("|region=").append(regionDir.toAbsolutePath().normalize());
        if (!Files.isDirectory(regionDir)) {
            builder.append("@missing");
            return;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException statFailed) {
            builder.append("@stat-failed");
            return;
        }
        files.sort(Comparator.comparing(Path::getFileName, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (Path file : files) {
            appendFileStat(builder, file);
        }
    }

    private Map<String, String> mergeWithDefaults(Map<String, String> submittedValues) {
        Map<String, String> merged = new LinkedHashMap<>(UiSettingsStore.defaults());
        if (submittedValues != null) {
            merged.putAll(submittedValues);
        }
        return merged;
    }

    private String[] buildArgs(Map<String, String> values) {
        List<String> args = new ArrayList<>();
        int modelCount = parseModelCount(values);
        args.add("--model-count");
        args.add(String.valueOf(modelCount));
        for (int i = 0; i < modelCount; i++) {
            String prefix = "--model-" + i + "-";
            addRequiredArg(args, prefix + "obj", values.get("obj" + i));
            addRequiredArg(args, prefix + "mtl", values.get("mtl" + i));
            addOptionalArg(args, prefix + "texture", values.get("texture" + i));
            addRequiredArg(args, prefix + "x", values.get("x" + i));
            addRequiredArg(args, prefix + "y", values.get("y" + i));
            addRequiredArg(args, prefix + "z", values.get("z" + i));
            addOptionalArg(args, prefix + "rotationX", values.get("rotationX" + i));
            addOptionalArg(args, prefix + "rotationY", values.get("rotationY" + i));
            addOptionalArg(args, prefix + "rotationZ", values.get("rotationZ" + i));
            // 兼容旧前端：仍透传单轴 rotation，新参数缺失时后端回退到它
            addOptionalArg(args, prefix + "rotation", values.get("rotation" + i));
            addOptionalArg(args, prefix + "height", values.get("height" + i));
        }
        addRequiredArg(args, "--world", values.get("world"));
        addRequiredArg(args, "--dimension", values.get("dimension"));
        addRequiredArg(args, "--height", values.get("height"));
        addRequiredArg(args, "--max-dimension", values.get("maxDimension"));
        addRequiredArg(args, "--samples-per-voxel", values.get("samplesPerVoxel"));
        addRequiredArg(args, "--max-triangle-samples", values.get("maxTriangleSamples"));
        addRequiredArg(args, "--denoise", values.get("denoise"));
        addRequiredArg(args, "--palette-profile", values.get("paletteProfile"));
        addRequiredArg(args, "--generate-model-palette", values.get("generateModelPalette"));
        addOptionalArg(args, "--generated-palette-name", values.get("generatePaletteName"));
        addRequiredArg(args, "--preview-padding", values.get("previewPadding"));
        addRequiredArg(args, "--backup", values.get("backup"));
        addRequiredArg(args, "--surface-light", values.get("surfaceLight"));
        return args.toArray(String[]::new);
    }

    /** 导出模式参数：不需要存档目录/维度/备份等写入相关字段；X/Y/Z 缺省用 0/64/0（导出原点以模型最小角归一，不影响内容）。 */
    private String[] buildExportArgs(Map<String, String> values, int modelCount) {
        List<String> args = new ArrayList<>();
        args.add("--model-count");
        args.add(String.valueOf(modelCount));
        for (int i = 0; i < modelCount; i++) {
            String prefix = "--model-" + i + "-";
            addRequiredArg(args, prefix + "obj", values.get("obj" + i));
            addRequiredArg(args, prefix + "mtl", values.get("mtl" + i));
            addOptionalArg(args, prefix + "texture", values.get("texture" + i));
            addRequiredArg(args, prefix + "x", defaultTo(values.get("x" + i), "0"));
            addRequiredArg(args, prefix + "y", defaultTo(values.get("y" + i), "64"));
            addRequiredArg(args, prefix + "z", defaultTo(values.get("z" + i), "0"));
            addOptionalArg(args, prefix + "rotationX", values.get("rotationX" + i));
            addOptionalArg(args, prefix + "rotationY", values.get("rotationY" + i));
            addOptionalArg(args, prefix + "rotationZ", values.get("rotationZ" + i));
            addOptionalArg(args, prefix + "rotation", values.get("rotation" + i));
            addOptionalArg(args, prefix + "height", values.get("height" + i));
        }
        addOptionalArg(args, "--height", values.get("height"));
        addOptionalArg(args, "--max-dimension", values.get("maxDimension"));
        addOptionalArg(args, "--samples-per-voxel", values.get("samplesPerVoxel"));
        addOptionalArg(args, "--max-triangle-samples", values.get("maxTriangleSamples"));
        addOptionalArg(args, "--denoise", values.get("denoise"));
        addOptionalArg(args, "--palette-profile", values.get("paletteProfile"));
        addOptionalArg(args, "--generate-model-palette", values.get("generateModelPalette"));
        addOptionalArg(args, "--generated-palette-name", values.get("generatePaletteName"));
        addRequiredArg(args, "--surface-light", values.get("surfaceLight"));
        return args.toArray(String[]::new);
    }

    private String defaultTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int parseModelCount(Map<String, String> values) {
        try {
            int count = Integer.parseInt(values.getOrDefault("modelCount", "1"));
            return Math.min(Math.max(1, count), Config.MAX_MODEL_COUNT);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void addRequiredArg(List<String> args, String key, String value) {
        args.add(key);
        args.add(value == null ? "" : value);
    }

    private void addOptionalArg(List<String> args, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        args.add(key);
        args.add(value);
    }

    private boolean isRunning() {
        synchronized (stateLock) {
            return running;
        }
    }

    private void appendLog(String message) {
        String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message;
        synchronized (stateLock) {
            logLines.add(line);
            if (logLines.size() > 2000) {
                logLines.remove(0);
            }
        }
        consoleOut.println(line);
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            appendLog("未能自动打开浏览器，请手动访问: " + url);
        }
    }

    private byte[] readResource(String resourcePath) throws IOException {
        try (InputStream input = WebUiServer.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("资源不存在: " + resourcePath);
            }
            return input.readAllBytes();
        }
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("请求格式错误，需要 application/x-www-form-urlencoded");
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseForm(body);
    }

    private Map<String, String> parseForm(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            values.put(urlDecode(key), urlDecode(value));
        }
        return values;
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendFailure(HttpExchange exchange, Exception e) throws IOException {
        int code = e instanceof IllegalArgumentException || e instanceof IOException ? 400 : 500;
        if (e instanceof IllegalStateException) {
            code = 409;
        }
        sendJson(exchange, code, Map.of("error", Objects.toString(e.getMessage(), e.getClass().getSimpleName())));
    }

    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, Map.of("error", "请求方法不支持"));
    }

    private boolean isGetLike(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private boolean handleOptions(HttpExchange exchange, String allowHeader) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        exchange.getResponseHeaders().set("Allow", allowHeader);
        sendBytes(exchange, 204, "text/plain; charset=UTF-8", new byte[0]);
        return true;
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        writeJson(buffer, payload);
        sendBytes(exchange, status, "application/json; charset=UTF-8", buffer.toByteArray());
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // 本地工具的 HTML 静态页频繁改动，禁止缓存，避免浏览器拿着旧页面调不存在的接口
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        }
        String method = exchange.getRequestMethod();
        exchange.sendResponseHeaders(status, body.length);
        if ("HEAD".equalsIgnoreCase(method) || status == 204) {
            exchange.getResponseBody().close();
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /**
     * 数字连同前导逗号一次性写进复用的 scratch 缓冲：每个数字恰好一次流调用（旧版是
     * 逗号、负号、数字串共 2-3 次），且 scratch 每个数组只分配一次——旧版每个数字
     * new byte[20]，2.8 亿个数字就是 5.6GB 垃圾分配。
     */
    private static void writeJsonInt(OutputStream output, long value, boolean comma, byte[] scratch) throws IOException {
        if (value == Long.MIN_VALUE) {
            if (comma) {
                output.write(',');
            }
            output.write("-9223372036854775808".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        int pos = scratch.length;
        if (value < 0) {
            long v = -value;
            while (v > 0) {
                scratch[--pos] = (byte) ('0' + v % 10);
                v /= 10;
            }
            scratch[--pos] = '-';
        } else if (value == 0) {
            scratch[--pos] = '0';
        } else {
            long v = value;
            while (v > 0) {
                scratch[--pos] = (byte) ('0' + v % 10);
                v /= 10;
            }
        }
        if (comma) {
            scratch[--pos] = ',';
        }
        output.write(scratch, pos, scratch.length - pos);
    }

    /** 序列化进度用的字节计数流：每写满约 1% 上报一次 subDone（写入方保持零分配）。 */
    private static final class CountingProgressOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final TaskProgress progress;
        private final long total;
        private final long reportInterval;
        private long count;
        private long nextReport;
        private final byte[] single = new byte[1];

        CountingProgressOutputStream(OutputStream delegate, TaskProgress progress, long total) {
            this.delegate = delegate;
            this.progress = progress;
            this.total = Math.max(1L, total);
            this.reportInterval = Math.max(1L, this.total / 100L);
            this.nextReport = this.reportInterval;
        }

        @Override
        public void write(int b) throws IOException {
            single[0] = (byte) b;
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
            if (count >= nextReport) {
                progress.subDone = Math.min(count, total);
                nextReport = Math.max(nextReport + reportInterval, count + 1);
            }
        }
    }

    /** 预估结果 JSON 字节数：小字段用空数组探针序列化取精确值，两个大数组按数字位数精确统计。 */
    private long estimateResultJsonLength(Map<String, Object> result) {
        Object building = result.get("building");
        Object terrain = result.get("terrain");
        Map<String, Object> probe = new java.util.LinkedHashMap<>(result);
        probe.put("building", new int[0]);
        probe.put("terrain", new int[0]);
        ByteArrayOutputStream small = new ByteArrayOutputStream(64 * 1024);
        try {
            writeJson(small, probe);
        } catch (IOException e) {
            return 0L;
        }
        long estimate = small.size();
        if (building instanceof int[] array) {
            estimate += jsonIntArrayLength(array);
        }
        if (terrain instanceof int[] array) {
            estimate += jsonIntArrayLength(array);
        }
        return Math.max(1L, estimate);
    }

    private static long jsonIntArrayLength(int[] array) {
        if (array.length == 0) {
            return 2L;
        }
        long total = 2L;
        for (int value : array) {
            total += jsonIntLength(value) + 1L;
        }
        return total - 1L;
    }

    private static int jsonIntLength(int value) {
        if (value >= 0) {
            return uintDigitLength(value);
        }
        if (value == Integer.MIN_VALUE) {
            return 11;
        }
        return uintDigitLength(-value) + 1;
    }

    /** 正数位数：比较链替代 %10 除法循环——除法 20+ 周期、比较 1 周期，2.8 亿元素的预估趟快一个数量级。 */
    private static int uintDigitLength(long v) {
        if (v < 10L) {
            return 1;
        }
        if (v < 100L) {
            return 2;
        }
        if (v < 1000L) {
            return 3;
        }
        if (v < 10000L) {
            return 4;
        }
        if (v < 100000L) {
            return 5;
        }
        if (v < 1000000L) {
            return 6;
        }
        if (v < 10000000L) {
            return 7;
        }
        if (v < 100000000L) {
            return 8;
        }
        if (v < 1000000000L) {
            return 9;
        }
        return 10;
    }

    /** 直接向输出流写 JSON（不再逐层拼接字符串）：千万级数字数组序列化必须零中间拷贝。 */
    private void writeJson(OutputStream output, Object value) throws IOException {
        if (value == null) {
            output.write("null".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (value instanceof String string) {
            writeJsonString(output, string);
            return;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            output.write(String.valueOf(value).getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            output.write(String.valueOf(value).getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (value instanceof int[] array) {
            output.write('[');
            byte[] scratch = new byte[24];
            for (int i = 0; i < array.length; i++) {
                writeJsonInt(output, array[i], i > 0, scratch);
            }
            output.write(']');
            return;
        }
        if (value instanceof long[] array) {
            output.write('[');
            byte[] scratch = new byte[24];
            for (int i = 0; i < array.length; i++) {
                writeJsonInt(output, array[i], i > 0, scratch);
            }
            output.write(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            output.write('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeJsonString(output, String.valueOf(entry.getKey()));
                output.write(':');
                writeJson(output, entry.getValue());
            }
            output.write('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            output.write('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeJson(output, item);
            }
            output.write(']');
            return;
        }
        writeJsonString(output, String.valueOf(value));
    }

    /** 写 JSON 字符串字面量；非 ASCII 字符强制反斜杠 U 转义，保证整包输出都是纯 ASCII 字节。 */
    private void writeJsonString(OutputStream output, String value) throws IOException {
        output.write('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> output.write("\\\\".getBytes(StandardCharsets.US_ASCII));
                case '"' -> output.write("\\\"".getBytes(StandardCharsets.US_ASCII));
                case '\b' -> output.write("\\b".getBytes(StandardCharsets.US_ASCII));
                case '\f' -> output.write("\\f".getBytes(StandardCharsets.US_ASCII));
                case '\n' -> output.write("\\n".getBytes(StandardCharsets.US_ASCII));
                case '\r' -> output.write("\\r".getBytes(StandardCharsets.US_ASCII));
                case '\t' -> output.write("\\t".getBytes(StandardCharsets.US_ASCII));
                default -> {
                    if (ch < 0x20 || ch > 0x7E) {
                        output.write(String.format("\\u%04x", (int) ch).getBytes(StandardCharsets.US_ASCII));
                    } else {
                        output.write(ch);
                    }
                }
            }
        }
        output.write('"');
    }
}
