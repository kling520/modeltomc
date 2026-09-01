package org.example;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class UiSettingsStore {
    private static final Path SETTINGS_PATH = Paths.get("model2mc-ui.properties");

    private UiSettingsStore() {
    }

    static Map<String, String> load() {
        Map<String, String> values = defaultValues();
        if (!Files.exists(SETTINGS_PATH)) {
            return values;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(SETTINGS_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (String name : properties.stringPropertyNames()) {
                values.put(name, properties.getProperty(name, ""));
            }
        } catch (IOException ignored) {
            // Fall back to defaults when the UI config file is unreadable.
        }
        upgradeLegacyValues(values);
        return values;
    }

    /** 旧默认值升级：①三角最大采样早期默认 8（大三角形漏采出镂空），低于 256 的旧存量一律升到新默认；②旧单轴 rotation{i} 迁移到 rotationY{i}。 */
    private static void upgradeLegacyValues(Map<String, String> values) {
        String raw = values.get("maxTriangleSamples");
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed < 256) {
                    values.put("maxTriangleSamples", String.valueOf(Config.DEFAULT_MAX_TRIANGLE_SAMPLES));
                }
            } catch (NumberFormatException ignored) {
                // 非法值交给后续解析报错
            }
        }
        // 旧版只存 rotation{i}（绕 Y 轴单轴）；迁移到 rotationY{i}，没有新字段时沿用旧值
        for (String key : values.keySet().stream().filter(k -> k.matches("rotation\\d+")).toList()) {
            String index = key.substring("rotation".length());
            String target = "rotationY" + index;
            if (!values.containsKey(target)) {
                values.put(target, values.get(key));
            }
        }
    }

    static Map<String, String> defaults() {
        return defaultValues();
    }

    static void save(Map<String, String> values) throws IOException {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }

        Path parent = SETTINGS_PATH.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(SETTINGS_PATH, StandardCharsets.UTF_8)) {
            properties.store(writer, "model2mc web ui settings");
        }
    }

    static Path settingsPath() {
        return SETTINGS_PATH.toAbsolutePath().normalize();
    }

    private static Map<String, String> defaultValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("modelCount", "1");
        values.put("obj0", "");
        values.put("mtl0", "");
        values.put("texture0", "");
        values.put("x0", "0");
        values.put("y0", "64");
        values.put("z0", "0");
        values.put("rotationX0", "0");
        values.put("rotationY0", String.valueOf(Config.DEFAULT_ROTATION));
        values.put("rotationZ0", "0");
        values.put("world", "");
        values.put("dimension", "overworld");
        values.put("height", String.valueOf(Config.DEFAULT_TARGET_HEIGHT));
        values.put("maxDimension", String.valueOf(Config.DEFAULT_MAX_DIMENSION));
        values.put("samplesPerVoxel", String.valueOf(Config.DEFAULT_SAMPLES_PER_VOXEL));
        values.put("maxTriangleSamples", String.valueOf(Config.DEFAULT_MAX_TRIANGLE_SAMPLES));
        values.put("denoise", String.valueOf(Config.DEFAULT_DENOISE));
        values.put("paletteProfile", Config.DEFAULT_PALETTE_PROFILE);
        values.put("generateModelPalette", "false");
        values.put("previewPadding", String.valueOf(Config.DEFAULT_PREVIEW_PADDING));
        values.put("backup", "true");
        values.put("surfaceLight", "false");

        try {
            Path modelDir = Config.findModelDir();
            Path objPath = Config.findFirst(modelDir, ".obj");
            Path mtlPath = modelDir.resolve("material.mtl");
            values.put("obj0", objPath.toAbsolutePath().normalize().toString());
            values.put("mtl0", mtlPath.toAbsolutePath().normalize().toString());
            if (Files.exists(mtlPath)) {
                values.put("texture0", Config.resolveDiffuseTexture(mtlPath).toAbsolutePath().normalize().toString());
            }
        } catch (IOException ignored) {
            // Leave blank defaults when the bundled model files are unavailable.
        }

        return values;
    }
}
