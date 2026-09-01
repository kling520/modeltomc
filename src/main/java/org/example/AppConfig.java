package org.example;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用级配置 (config.json)：与浏览器端每页参数（model2mc-ui.properties）区分。
 * 目前只承载堆内存上限 heapMB——它要进 JVM 启动参数，进程级生效，重启前不落到位。
 * 约定：config.json 存在则加载其中各项，缺项/文件不存在套默认值；保存时保留未知键以供扩展。
 */
final class AppConfig {
    static final Path CONFIG_PATH = Paths.get("config.json");
    /** 堆内存下限：低于这个值基础功能都没法跑。 */
    static final int MIN_HEAP_MB = 1024;
    /** 上限只防手滑，理论上调多高随用户机器。 */
    static final int MAX_HEAP_MB = 262_144;

    /** 扁平 JSON 提取器：只支持 "key": "str" | 数字 | true | false，不支持嵌套对象/数组（当前配置面不需要）。 */
    private static final Pattern KEY_VALUE_PATTERN =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+|\btrue\b|\bfalse\b)");

    private AppConfig() {
    }

    /** 读取配置；文件不存在或不可读返回空 Map（调用方自行套默认值）。 */
    static Map<String, String> load() {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(CONFIG_PATH)) {
            return values;
        }
        try {
            String text = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            Matcher matcher = KEY_VALUE_PATTERN.matcher(text);
            while (matcher.find()) {
                String key = matcher.group(1);
                String raw = matcher.group(2);
                if (raw.startsWith("\"")) {
                    values.put(key, raw.substring(1, raw.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\"));
                } else {
                    values.put(key, raw);
                }
            }
            return values;
        } catch (IOException ignored) {
            return values;
        }
    }

    /** 保存配置（原子写）：数字值不带引号，其余转义为字符串。 */
    static void save(Map<String, String> values) throws IOException {
        StringBuilder json = new StringBuilder("{\n");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            json.append(index++ > 0 ? ",\n" : "");
            json.append("  \"").append(escape(entry.getKey())).append("\": ");
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.matches("-?\\d+")) {
                json.append(value);
            } else {
                json.append('"').append(escape(value)).append('"');
            }
        }
        json.append("\n}\n");
        // 用绝对路径：相对路径的 getParent() 为 null，DiskCache 原子写的第一步就要建目录
        DiskCache.writeAtomically(CONFIG_PATH.toAbsolutePath().normalize(),
                out -> out.write(json.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 配置里的堆内存（MB）；未配置或非法值时返回默认（物理内存 80%，下限 8192）。 */
    static int heapMB() {
        String raw = load().get("heapMB");
        if (raw != null && raw.matches("\\d+")) {
            try {
                int parsed = Integer.parseInt(raw);
                return Math.max(MIN_HEAP_MB, Math.min(MAX_HEAP_MB, parsed));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultHeapMB();
    }

    /** 默认堆内存：物理内存的 80% 向下取整（与 run.bat 一致），探测失败回退 8192MB。 */
    static int defaultHeapMB() {
        long total = physicalMemoryBytes();
        if (total <= 0) {
            return 8192;
        }
        long heap = total / (1024 * 1024) * 8 / 10;
        return (int) Math.max(MIN_HEAP_MB, Math.min(MAX_HEAP_MB, Math.max(8192L, heap)));
    }

    /** 物理内存字节数（JVM 当前可用探测），失败返回 0。 */
    static long physicalMemoryBytes() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean osBean) {
                return osBean.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // 非标准 JVM 上拿不到物理内存，调用方回退默认值
        }
        return 0L;
    }

    /** 当前 JVM 的 -Xmx 上限（MB），配置页展示用。 */
    static long currentHeapMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}