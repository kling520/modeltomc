package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Config {
    static final int DEFAULT_TARGET_HEIGHT = 96;
    static final int DEFAULT_MAX_DIMENSION = 4096;
    static final double DEFAULT_SAMPLES_PER_VOXEL = 1.35;
    /** 三角形采样步数上限：必须远大于模型最大三角形边长（体素数），否则大三角形内部漏采样形成镂空。 */
    static final int DEFAULT_MAX_TRIANGLE_SAMPLES = 8192;
    static final int MAX_TRIANGLE_SAMPLES_LIMIT = 8192;
    /** 体素化后去杂强度：0=关，1=只清孤立单点，2=同时清 1-2 格小簇（建筑模型推荐）。 */
    static final int DEFAULT_DENOISE = 0;
    /** 默认映射文件：完整调色板。 */
    static final String DEFAULT_PALETTE_PROFILE = PaletteStore.DEFAULT_PROFILE_ID;
    // 三轴朝向（任意角度）；旧单轴 rotation 仅用于兼容调用
    static final int DEFAULT_ROTATION = 0;
    // 预览地形时围绕每个模型边界各自向外扩展读取的格数
    static final int DEFAULT_PREVIEW_PADDING = 12;
    static final int MAX_MODEL_COUNT = 64;

    final List<PlacedModel> models;
    final Path worldPath;
    final String dimension;
    final int targetHeight;
    final int maxDimension;
    final double samplesPerVoxel;
    final int maxTriangleSamples;
    final int denoise;
    final String paletteProfile;
    final String paletteMode;
    /** 映射文件全部生效条目（block name -> 0xRRGGBB）；匹配时只使用这些方块。 */
    final Map<String, Integer> paletteOverrides;
    final String paletteSignature;
    final int previewPadding;
    final boolean createRegionBackups;
    final boolean addSurfaceLight;
    final boolean generateModelPalette;
    /** 生成模型方块映射时的目标文件名；空表示沿用固定的 model_loaded_blocks。 */
    final String generatedPaletteName;

    Config(List<PlacedModel> models,
           Path worldPath,
           String dimension,
           int targetHeight,
           int maxDimension,
           double samplesPerVoxel,
           int maxTriangleSamples,
           int denoise,
           String paletteProfile,
           String paletteMode,
           Map<String, Integer> paletteOverrides,
           String paletteSignature,
           int previewPadding,
           boolean createRegionBackups,
           boolean addSurfaceLight,
           boolean generateModelPalette,
           String generatedPaletteName) {
        this.models = List.copyOf(models);
        this.worldPath = worldPath;
        this.dimension = dimension;
        this.targetHeight = targetHeight;
        this.maxDimension = maxDimension;
        this.samplesPerVoxel = samplesPerVoxel;
        this.maxTriangleSamples = maxTriangleSamples;
        this.denoise = denoise;
        this.paletteProfile = paletteProfile;
        this.paletteMode = paletteMode;
        this.paletteOverrides = Map.copyOf(paletteOverrides);
        this.paletteSignature = paletteSignature;
        this.previewPadding = previewPadding;
        this.createRegionBackups = createRegionBackups;
        this.addSurfaceLight = addSurfaceLight;
        this.generateModelPalette = generateModelPalette;
        this.generatedPaletteName = generatedPaletteName == null || generatedPaletteName.isBlank()
                ? null
                : generatedPaletteName.trim();
    }

    /** 第一个模型作为"主模型"，用于目标标记与落点地表查询。 */
    PlacedModel primary() {
        return models.get(0);
    }

    static Config fromArgs(String[] args) throws IOException {
        return fromArgs(args, true);
    }

    /** 导出模式：只做体素化与文件导出，不要求存档目录等写入相关参数。 */
    static Config fromArgsForExport(String[] args) throws IOException {
        return fromArgs(args, false);
    }

    /** 预览模式：允许不填存档目录，此时只预览模型。 */
    static Config fromArgsForPreview(String[] args) throws IOException {
        return fromArgs(args, false);
    }

    private static Config fromArgs(String[] args, boolean requireWorld) throws IOException {
        Map<String, String> options = parseArgs(args);

        List<PlacedModel> models = parseModels(options);
        String worldRaw = options.get("--world");
        if (requireWorld && (worldRaw == null || worldRaw.isBlank())) {
            throw new IllegalArgumentException("缺少参数: --world");
        }
        Path worldPath = (worldRaw == null || worldRaw.isBlank()) ? null : Paths.get(worldRaw);
        String dimension = normalizeDimension(options.getOrDefault("--dimension", "overworld"));

        int targetHeight = Integer.parseInt(options.getOrDefault("--height", String.valueOf(DEFAULT_TARGET_HEIGHT)));
        int maxDimension = Integer.parseInt(options.getOrDefault("--max-dimension", String.valueOf(DEFAULT_MAX_DIMENSION)));
        double samplesPerVoxel = Double.parseDouble(options.getOrDefault("--samples-per-voxel", String.valueOf(DEFAULT_SAMPLES_PER_VOXEL)));
        int maxTriangleSamples = Math.max(1, Math.min(MAX_TRIANGLE_SAMPLES_LIMIT,
                Integer.parseInt(options.getOrDefault("--max-triangle-samples", String.valueOf(DEFAULT_MAX_TRIANGLE_SAMPLES)))));
        int denoise = Math.max(0, Math.min(2, Integer.parseInt(options.getOrDefault("--denoise", String.valueOf(DEFAULT_DENOISE)))));
        String paletteProfileId = options.get("--palette-profile");
        if (paletteProfileId == null || paletteProfileId.isBlank()) {
            paletteProfileId = PaletteStore.DEFAULT_PROFILE_ID;
        }
        PaletteStore.PaletteProfile paletteProfile = PaletteStore.loadProfile(paletteProfileId);
        String paletteMode = DEFAULT_PALETTE_PROFILE;
        Map<String, Integer> paletteOverrides = PaletteStore.toProfileRgb(paletteProfile.effectiveEntries());
        int previewPadding = Integer.parseInt(options.getOrDefault("--preview-padding", String.valueOf(DEFAULT_PREVIEW_PADDING)));
        if (previewPadding < 0) {
            throw new IllegalArgumentException("地形预览范围不能为负数: " + previewPadding);
        }
        boolean createRegionBackups = !"false".equalsIgnoreCase(options.getOrDefault("--backup", "true"));
        boolean addSurfaceLight = Boolean.parseBoolean(options.getOrDefault("--surface-light", "false"));
        boolean generateModelPalette = Boolean.parseBoolean(options.getOrDefault("--generate-model-palette", "false"));
        String generatedPaletteName = options.get("--generated-palette-name");

        return new Config(
                models,
                worldPath == null ? null : worldPath.toAbsolutePath().normalize(),
                dimension,
                targetHeight,
                maxDimension,
                samplesPerVoxel,
                maxTriangleSamples,
                denoise,
                paletteProfile.id(),
                paletteMode,
                paletteOverrides,
                paletteProfile.signature(),
                previewPadding,
                createRegionBackups,
                addSurfaceLight,
                generateModelPalette,
                generatedPaletteName);
    }

    /** 解析模型列表：优先 --model-count N + --model-{i}-xxx；缺失时兼容旧单模型参数 --obj/--mtl/--texture/--x/--y/--z/--rotation。 */
    private static List<PlacedModel> parseModels(Map<String, String> options) throws IOException {
        boolean indexed = options.containsKey("--model-count");
        int count = 1;
        if (indexed) {
            count = Integer.parseInt(options.get("--model-count"));
            if (count < 1 || count > MAX_MODEL_COUNT) {
                throw new IllegalArgumentException("模型数量非法(1-" + MAX_MODEL_COUNT + "): " + count);
            }
        }

        List<PlacedModel> models = new ArrayList<>(count);
        if (!indexed) {
            Path obj = optionPath(options, "--obj", null);
            Path mtl = optionPath(options, "--mtl", null);
            if (obj == null || mtl == null) {
                Path modelDir = findModelDir();
                if (obj == null) {
                    obj = findFirst(modelDir, ".obj");
                }
                if (mtl == null) {
                    mtl = modelDir.resolve("material.mtl");
                }
            }
            Path texture = optionPath(options, "--texture", null);
            if (texture == null) {
                texture = resolveDiffuseTexture(mtl);
            }
            models.add(PlacedModel.ofLegacyRotation(
                        obj.toAbsolutePath().normalize(),
                        mtl.toAbsolutePath().normalize(),
                        texture.toAbsolutePath().normalize(),
                        requireIntOption(options, "--x"),
                        requireIntOption(options, "--y"),
                        requireIntOption(options, "--z"),
                        parseRotation(options.getOrDefault("--rotation", String.valueOf(DEFAULT_ROTATION))),
                        parseHeight(options.getOrDefault("--height", String.valueOf(DEFAULT_TARGET_HEIGHT)))));
            return models;
        }

        for (int i = 0; i < count; i++) {
            boolean first = i == 0;
            String prefix = "--model-" + i + "-";
            Path obj = optionPath(options, prefix + "obj", null);
            Path mtl = optionPath(options, prefix + "mtl", null);
            if (first && (obj == null || mtl == null)) {
                Path modelDir = findModelDir();
                if (obj == null) {
                    obj = findFirst(modelDir, ".obj");
                }
                if (mtl == null) {
                    mtl = modelDir.resolve("material.mtl");
                }
            }
            if (obj == null) {
                throw new IllegalArgumentException("缺少参数: " + prefix + "obj");
            }
            if (mtl == null) {
                throw new IllegalArgumentException("缺少参数: " + prefix + "mtl");
            }
            Path texture = optionPath(options, prefix + "texture", null);
            if (texture == null) {
                texture = resolveDiffuseTexture(mtl);
            }
            // 三轴朝向（新参数，任意角度）；旧单轴 rotation 映射为绕 Y 轴，两者并存时三轴参数优先
            int rotationX = parseAnyAngle(options.getOrDefault(prefix + "rotationX", "0"));
            int rotationY = options.containsKey(prefix + "rotationY")
                    ? parseAnyAngle(options.get(prefix + "rotationY"))
                    : parseRotation(options.getOrDefault(prefix + "rotation", String.valueOf(DEFAULT_ROTATION)));
            int rotationZ = parseAnyAngle(options.getOrDefault(prefix + "rotationZ", "0"));
            models.add(new PlacedModel(
                    obj.toAbsolutePath().normalize(),
                    mtl.toAbsolutePath().normalize(),
                    texture.toAbsolutePath().normalize(),
                    requireIntOption(options, prefix + "x"),
                    requireIntOption(options, prefix + "y"),
                    requireIntOption(options, prefix + "z"),
                    rotationX,
                    rotationY,
                    rotationZ,
                    parseHeight(options.getOrDefault(prefix + "height", "0"))));
        }
        return models;
    }

    /** 解析单个模型的目标高度；空白或 0（未单独设置）表示继承全局 --height。 */
    static int parseHeight(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int height = Integer.parseInt(raw);
        if (height < 0) {
            throw new IllegalArgumentException("目标高度不能为负数: " + raw);
        }
        return height;
    }

    /** 旧参数校验：仅允许 0/90/180/270（兼容遗留调用）。 */
    static int parseRotation(String raw) {
        int rotation = parseAnyAngle(raw);
        if (rotation % 90 != 0) {
            throw new IllegalArgumentException("旧版模型朝向只支持 0/90/180/270 度，收到: " + raw
                    + "；如需任意角度请使用 rotationX/rotationY/rotationZ 参数");
        }
        return rotation;
    }

    /** 三轴任意角度：0-359 整数，负值自动取模到 [0,360)。 */
    static int parseAnyAngle(String raw) {
        int angle;
        try {
            angle = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("旋转角度必须是整数，收到: " + raw);
        }
        if (angle < 0 || angle >= 360) {
            throw new IllegalArgumentException("旋转角度必须在 0-359 之间，收到: " + raw);
        }
        return angle;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("参数缺少值: " + key);
                }
                options.put(key, args[++i]);
            }
        }
        return options;
    }

    static Path optionPath(Map<String, String> options, String key, Path fallback) {
        if (options.containsKey(key)) {
            String value = options.get(key);
            if (value != null && !value.isBlank()) {
                return Paths.get(value);
            }
        }
        return fallback;
    }

    static Path requirePathOption(Map<String, String> options, String key) {
        if (!options.containsKey(key) || options.get(key) == null || options.get(key).isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return Paths.get(options.get(key));
    }

    static int requireIntOption(Map<String, String> options, String key) {
        if (!options.containsKey(key) || options.get(key) == null || options.get(key).isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return Integer.parseInt(options.get(key));
    }

    static Path findModelDir() throws IOException {
        List<Path> candidates = List.of(
                Paths.get("src", "main", "resources", "model"),
                Paths.get("target", "classes", "model"),
                Paths.get("test-model")
        );
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IOException("未找到模型目录，期望存在 src/main/resources/model、target/classes/model 或 test-model；"
                + "也可通过 --obj/--mtl 或 --model-{i}-obj/--model-{i}-mtl 显式指定模型文件路径");
    }

    static Path findFirst(Path directory, String suffix) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("在目录中未找到 " + suffix + " 文件: " + directory));
        }
    }

    static Path resolveDiffuseTexture(Path mtlPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(mtlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("map_Kd ")) {
                    return mtlPath.getParent().resolve(trimmed.substring("map_Kd ".length()).trim());
                }
            }
        }
        throw new IOException("MTL 中没有找到 map_Kd 贴图: " + mtlPath);
    }

    static String normalizeDimension(String rawDimension) {
        String normalized = rawDimension.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "overworld", "world", "minecraft:overworld" -> "overworld";
            case "nether", "the_nether", "minecraft:the_nether" -> "nether";
            case "end", "the_end", "minecraft:the_end" -> "end";
            default -> throw new IllegalArgumentException("不支持的维度: " + rawDimension + "，可选 overworld/nether/end");
        };
    }

    Path regionDirectory() {
        if (worldPath == null) {
            throw new IllegalStateException("未提供存档目录");
        }
        Path dimensionRegionPath = switch (dimension) {
            case "nether" -> worldPath.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("region");
            case "end" -> worldPath.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region");
            default -> worldPath.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        };
        if (Files.isDirectory(dimensionRegionPath)) {
            return dimensionRegionPath;
        }

        return switch (dimension) {
            case "nether" -> worldPath.resolve("DIM-1").resolve("region");
            case "end" -> worldPath.resolve("DIM1").resolve("region");
            default -> worldPath.resolve("region");
        };
    }
}
