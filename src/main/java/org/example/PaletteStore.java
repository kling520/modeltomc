package org.example;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * 管理可复制模板的方块颜色映射文件。
 *
 * 存储目录: model2mc-palettes/*.properties
 * 文件格式:
 * _inherits=模板来源ID（仅用于展示，不参与运行时继承）
 * minecraft:stone=#7d7d7d
 */
final class PaletteStore {
    static final String DEFAULT_PROFILE_ID = "默认";
    /** 旧版本的内置模板叫 default；读取时一律映射到 默认，避免旧设置/旧文件失效。 */
    static final String OLD_DEFAULT_PROFILE_ID = "default";
    static final String MODEL_LOADED_PROFILE_ID = "model_loaded_blocks";
    private static final String LEGACY_FULL_PROFILE_ID = "full";
    private static final String LEGACY_SIMPLE_PROFILE_ID = "simple";

    private static final Path STORE_DIR = Paths.get("model2mc-palettes");
    private static final Path LEGACY_STORE_PATH = Paths.get("model2mc-palette.properties");
    private static final String META_INHERITS = "_inherits";
    private static final Set<String> BUILTIN_IDS = Set.of(DEFAULT_PROFILE_ID);

    private PaletteStore() {
    }

    static Path storePath() {
        return STORE_DIR.toAbsolutePath().normalize();
    }

    static List<ProfileSummary> listProfiles() throws IOException {
        ensureInitialized();
        List<ProfileSummary> profiles = new ArrayList<>();
        try (var stream = Files.list(STORE_DIR)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String id = fileNameToId(path.getFileName().toString());
                        ProfileDefinition definition = readDefinition(path, id);
                        profiles.add(new ProfileSummary(
                                id,
                                normalizeMode(definition.mode()),
                                normalizeProfileId(definition.inherits()),
                                BUILTIN_IDS.contains(id),
                                path.toAbsolutePath().normalize()));
                    });
        }
        profiles.sort(Comparator
                .comparing((ProfileSummary item) -> !item.builtin())
                .thenComparing(ProfileSummary::id));
        profiles.removeIf(profile -> LEGACY_SIMPLE_PROFILE_ID.equals(profile.id())
                || LEGACY_FULL_PROFILE_ID.equals(profile.id()));
        return profiles;
    }

    static PaletteProfile loadProfile(String requestedId) throws IOException {
        ensureInitialized();
        String id = normalizeProfileId(requestedId);
        if (id == null || id.isBlank()
                || LEGACY_SIMPLE_PROFILE_ID.equals(id)
                || LEGACY_FULL_PROFILE_ID.equals(id)) {
            id = DEFAULT_PROFILE_ID;
        }
        return resolveProfile(id, new LinkedHashSet<>());
    }

    static void createProfile(String requestedId, String inherits, String mode) throws IOException {
        ensureInitialized();
        String id = requireProfileId(requestedId);
        if (BUILTIN_IDS.contains(id) || LEGACY_FULL_PROFILE_ID.equals(id)) {
            throw new IOException("默认模板 ID 已被保留: " + id);
        }
        Path path = profilePath(id);
        if (Files.exists(path)) {
            throw new IOException("映射文件已存在: " + id);
        }
        String templateId = normalizeProfileId(inherits);
        if (LEGACY_SIMPLE_PROFILE_ID.equals(templateId) || LEGACY_FULL_PROFILE_ID.equals(templateId)) {
            templateId = DEFAULT_PROFILE_ID;
        }
        PaletteProfile template = templateId == null ? null : loadProfile(templateId);
        String finalMode = DEFAULT_PROFILE_ID;
        Map<String, String> baseEntries = template != null ? template.effectiveEntries() : Map.of();
        saveDefinition(path, id, templateId, finalMode, baseEntries);
    }

    static void deleteProfile(String requestedId) throws IOException {
        ensureInitialized();
        String id = requireProfileId(requestedId);
        if (BUILTIN_IDS.contains(id)) {
            throw new IOException("默认模板不能删除: " + id);
        }
        Files.deleteIfExists(profilePath(id));
    }

    static void saveProfile(String requestedId,
                            String inherits,
                            String mode,
                            List<Map<String, String>> rows) throws IOException {
        ensureInitialized();
        String id = requireProfileId(requestedId);
        if (LEGACY_FULL_PROFILE_ID.equals(id)) {
            id = DEFAULT_PROFILE_ID;
        }
        boolean builtin = BUILTIN_IDS.contains(id);
        String parentId = normalizeProfileId(inherits);
        if (LEGACY_SIMPLE_PROFILE_ID.equals(parentId) || LEGACY_FULL_PROFILE_ID.equals(parentId)) {
            parentId = DEFAULT_PROFILE_ID;
        }
        if (!builtin && id.equals(parentId)) {
            throw new IOException("映射文件不能复制自己作为模板");
        }
        if (!builtin && parentId != null && !Files.exists(profilePath(parentId))) {
            throw new IOException("未找到模板映射文件: " + parentId);
        }
        Map<String, String> requestedEntries = normalize(rows);
        saveDefinition(profilePath(id), id, builtin ? null : parentId, normalizeMode(mode), requestedEntries);
    }

    static void restoreDefaultProfile(String requestedId) throws IOException {
        ensureInitialized();
        String id = requireProfileId(requestedId);
        if (LEGACY_FULL_PROFILE_ID.equals(id)) {
            id = DEFAULT_PROFILE_ID;
        }
        if (!BUILTIN_IDS.contains(id)) {
            throw new IOException("只有默认模板支持恢复默认设置: " + id);
        }
        saveDefinition(profilePath(id), id, null, DEFAULT_PROFILE_ID, defaultEntriesHex());
    }

    static GeneratedProfile saveModelLoadedProfile(Iterable<String> blockStates) throws IOException {
        return saveGeneratedProfile(null, blockStates);
    }

    /**
     * 把模型中实际出现的方块生成为映射文件。
     * requestedId 为空时沿用固定的 model_loaded_blocks；同名文件已存在时保留其现有颜色（见 mergeGeneratedProfileEntries）。
     */
    static GeneratedProfile saveGeneratedProfile(String requestedId, Iterable<String> blockStates) throws IOException {
        ensureInitialized();
        String id = requestedId == null || requestedId.isBlank()
                ? MODEL_LOADED_PROFILE_ID
                : requireProfileId(requestedId);
        if (BUILTIN_IDS.contains(id)) {
            throw new IOException("默认模板 ID 已被保留: " + id);
        }
        Map<String, String> entries = buildGeneratedEntries(blockStates);
        Path path = profilePath(id);
        Map<String, String> mergedEntries = mergeGeneratedProfileEntries(path, id, entries);
        saveDefinition(path, id, DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID, mergedEntries);
        return new GeneratedProfile(
                id,
                path.toAbsolutePath().normalize(),
                mergedEntries.size());
    }

    static Map<String, String> buildGeneratedEntries(Iterable<String> blockStates) {
        LinkedHashSet<String> uniqueBlocks = new LinkedHashSet<>();
        if (blockStates != null) {
            for (String blockState : blockStates) {
                String block = ParsedBlockState.parse(blockState == null ? "" : blockState.trim()).name();
                if (!block.isBlank() && BlockPalette.supportsBlock(block)) {
                    uniqueBlocks.add(block);
                }
            }
        }
        List<String> sortedBlocks = uniqueBlocks.stream().sorted().toList();
        Map<String, String> entries = new LinkedHashMap<>();
        for (String block : sortedBlocks) {
            int rgb = BlockPalette.DEFAULT.blockColor(block);
            if (rgb < 0) {
                rgb = BlockPalette.DEFAULT.previewColor(block);
            }
            entries.put(block, String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF));
        }
        return entries;
    }

    /**
     * 生成映射文件时，尽量保留用户刚刚改过的条目。
     * 优先级：
     * 1. 同名方块沿用现有颜色（保留改色）
     * 2. 其他情况回退到本轮新生成的默认条目
     *
     * 注意：这里必须始终保留“本轮实际生成到的方块名”。
     * 否则如果仅按颜色复用旧条目，映射文件里会漏掉预览里真实出现的方块，
     * 例如预览点击到 gray_wool，但生成的映射文件中却没有该条目。
     */
    private static Map<String, String> mergeGeneratedProfileEntries(Path path,
                                                                    String id,
                                                                    Map<String, String> generatedEntries) {
        if (!Files.exists(path) || generatedEntries.isEmpty()) {
            return generatedEntries;
        }

        ProfileDefinition existing = readDefinition(path, id);
        Map<String, String> existingEntries = new LinkedHashMap<>(existing.directEntries());
        if (existingEntries.isEmpty()) {
            return generatedEntries;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : generatedEntries.entrySet()) {
            String generatedBlock = entry.getKey();
            String generatedHex = entry.getValue();

            String exactHex = existingEntries.get(generatedBlock);
            if (exactHex != null) {
                merged.putIfAbsent(generatedBlock, exactHex);
                continue;
            }

            merged.putIfAbsent(generatedBlock, generatedHex);
        }
        return merged;
    }

    /** 兼容旧逻辑：读取 legacy 单文件映射，供迁移时使用。 */
    static Map<String, String> loadLegacyCustom() {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(LEGACY_STORE_PATH)) {
            return values;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(LEGACY_STORE_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (String name : properties.stringPropertyNames()) {
                String color = normalizeHex(properties.getProperty(name, ""));
                String block = ParsedBlockState.parse(name).name();
                if (color != null && BlockPalette.supportsBlock(block)) {
                    values.put(block, color);
                }
            }
        } catch (IOException ignored) {
            // Fall back to empty map when the legacy palette file is unreadable.
        }
        return values;
    }

    static Map<String, String> normalize(List<Map<String, String>> entries) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (entries == null) {
            return values;
        }
        for (Map<String, String> entry : entries) {
            String block = entry == null ? null : entry.get("block");
            String color = entry == null ? null : entry.get("color");
            if (block == null || block.isBlank() || color == null || color.isBlank()) {
                continue;
            }
            String normalizedBlock = ParsedBlockState.parse(block.trim()).name();
            String hex = normalizeHex(color);
            if (!normalizedBlock.isBlank() && hex != null && BlockPalette.supportsBlock(normalizedBlock)) {
                values.put(normalizedBlock, hex);
            }
        }
        return values;
    }

    static String normalizeHex(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 3) {
            StringBuilder expanded = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                char c = value.charAt(i);
                expanded.append(c).append(c);
            }
            value = expanded.toString();
        }
        if (value.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            char c = value.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hexDigit) {
                return null;
            }
        }
        return "#" + value.toLowerCase(Locale.ROOT);
    }

    static Integer hexToRgb(String hex) {
        String normalized = normalizeHex(hex);
        if (normalized == null) {
            return null;
        }
        return (int) Long.parseLong(normalized.substring(1), 16);
    }

    static List<Map<String, String>> toEntryList(Map<String, String> custom) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : custom.entrySet()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("block", entry.getKey());
            row.put("color", entry.getValue());
            entries.add(row);
        }
        return entries;
    }

    /**
     * 把映射文件的生效条目完整转成 RGB。
     * 注意：不能按「与内置默认色不同」过滤——映射文件里的条目就是完整的匹配调色板，
     * 条目数决定哪些方块参与匹配，即使颜色与默认值相同也要保留。
     */
    static Map<String, Integer> toProfileRgb(Map<String, String> effectiveEntries) {
        Map<String, Integer> profile = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : effectiveEntries.entrySet()) {
            String hex = normalizeHex(entry.getValue());
            Integer rgb = hexToRgb(hex);
            if (rgb == null) {
                continue;
            }
            String block = ParsedBlockState.parse(entry.getKey()).name();
            if (!block.isBlank()) {
                profile.put(block, rgb);
            }
        }
        return profile;
    }

    private static PaletteProfile resolveProfile(String id, LinkedHashSet<String> stack) throws IOException {
        Path path = profilePath(id);
        if (!Files.exists(path)) {
            throw new IOException("未找到映射文件: " + id);
        }

        ProfileDefinition definition = readDefinition(path, id);
        String mode = DEFAULT_PROFILE_ID;
        String inherits = normalizeProfileId(definition.inherits());
        if (LEGACY_SIMPLE_PROFILE_ID.equals(inherits) || LEGACY_FULL_PROFILE_ID.equals(inherits)) {
            inherits = DEFAULT_PROFILE_ID;
        }
        boolean builtin = BUILTIN_IDS.contains(id);
        Map<String, String> effectiveEntries = definition.directEntries().isEmpty() && builtin
                ? defaultEntriesHex()
                : new LinkedHashMap<>(definition.directEntries());

        String signature = buildSignature(mode, inherits, effectiveEntries);
        return new PaletteProfile(
                id,
                inherits,
                mode,
                builtin,
                path.toAbsolutePath().normalize(),
                definition.directEntries(),
                effectiveEntries,
                signature);
    }

    private static void ensureInitialized() throws IOException {
        Files.createDirectories(STORE_DIR);
        migrateLegacyDefaultProfile();
        migrateChineseDefaultProfile();
        ensureBuiltinProfile(DEFAULT_PROFILE_ID);
        migrateLegacyStore();
        pruneUnsupportedEntriesInStore();
    }

    private static void migrateLegacyDefaultProfile() throws IOException {
        Path legacyPath = profilePath(LEGACY_FULL_PROFILE_ID);
        Path defaultPath = profilePath(DEFAULT_PROFILE_ID);
        if (!Files.exists(defaultPath) && Files.exists(legacyPath)) {
            Files.move(legacyPath, defaultPath);
        }
    }

    /** 内置默认模板由 default 改名为 默认：把旧文件改名，已有用户的自定义色板无缝过渡。 */
    private static void migrateChineseDefaultProfile() throws IOException {
        Path oldPath = profilePath(OLD_DEFAULT_PROFILE_ID);
        Path newPath = profilePath(DEFAULT_PROFILE_ID);
        if (!Files.exists(newPath) && Files.exists(oldPath)) {
            Files.move(oldPath, newPath);
        }
    }

    private static void ensureBuiltinProfile(String id) throws IOException {
        Path path = profilePath(id);
        if (Files.exists(path)) {
            return;
        }
        saveDefinition(path, id, null, DEFAULT_PROFILE_ID, Map.of());
    }

    private static void migrateLegacyStore() throws IOException {
        if (!Files.exists(LEGACY_STORE_PATH)) {
            return;
        }
        Path importedPath = profilePath("legacy_imported");
        if (Files.exists(importedPath)) {
            return;
        }
        Map<String, String> legacy = loadLegacyCustom();
        if (legacy.isEmpty()) {
            return;
        }
        saveDefinition(importedPath, "legacy_imported", DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID, legacy);
    }

    private static void pruneUnsupportedEntriesInStore() throws IOException {
        try (var stream = Files.list(STORE_DIR)) {
            for (Path path : stream
                    .filter(item -> Files.isRegularFile(item) && item.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                if (!containsUnsupportedEntries(path)) {
                    continue;
                }
                String id = fileNameToId(path.getFileName().toString());
                ProfileDefinition definition = readDefinition(path, id);
                saveDefinition(path,
                        id,
                        normalizeProfileId(definition.inherits()),
                        normalizeMode(definition.mode()),
                        definition.directEntries());
            }
        }
    }

    private static boolean containsUnsupportedEntries(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            return false;
        }
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("_")) {
                continue;
            }
            if (!BlockPalette.supportsBlock(name)) {
                return true;
            }
        }
        return false;
    }

    private static Path profilePath(String id) {
        return STORE_DIR.resolve(id + ".properties");
    }

    private static void saveDefinition(Path path,
                                       String id,
                                       String inherits,
                                       String mode,
                                       Map<String, String> entries) throws IOException {
        Properties properties = new Properties();
        if (inherits != null && !inherits.isBlank()) {
            properties.setProperty(META_INHERITS, inherits);
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String block = ParsedBlockState.parse(entry.getKey()).name();
            String hex = normalizeHex(entry.getValue());
            if (!block.isBlank() && hex != null && BlockPalette.supportsBlock(block)) {
                properties.setProperty(block, hex);
            }
        }
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "model2mc palette profile");
        }
    }

    private static ProfileDefinition readDefinition(Path path, String id) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            // Fall back to an empty definition when the file is unreadable.
        }

        String mode = DEFAULT_PROFILE_ID;
        String inherits = properties.getProperty(META_INHERITS, "");
        Map<String, String> directEntries = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("_")) {
                continue;
            }
            String hex = normalizeHex(properties.getProperty(name, ""));
            String block = ParsedBlockState.parse(name).name();
            if (hex != null && BlockPalette.supportsBlock(block)) {
                directEntries.put(block, hex);
            }
        }
        return new ProfileDefinition(id, mode, inherits, directEntries);
    }

    private static String requireProfileId(String raw) {
        String id = normalizeProfileId(raw);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("映射文件名称不能为空；支持中文、字母、数字、- 和 _");
        }
        if (id.length() > 64) {
            throw new IllegalArgumentException("映射文件名称过长（最多 64 个字符）");
        }
        if (RESERVED_FILE_NAMES.contains(id)) {
            throw new IllegalArgumentException("该名称是 Windows 保留设备名，请换一个: " + id);
        }
        return id;
    }

    /** 这些名字在 Windows 上会被当成设备而不是文件，禁止用作映射文件名。 */
    private static final Set<String> RESERVED_FILE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** 保留 Unicode 字母（含中文）、数字、- 和 _；其余字符（路径分隔符、Windows 非法字符等）剔除。 */
    private static String normalizeProfileId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '_' || c == '-' || Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        if (builder.isEmpty()) {
            return null;
        }
        String id = builder.toString();
        return OLD_DEFAULT_PROFILE_ID.equals(id) ? DEFAULT_PROFILE_ID : id;
    }

    private static String normalizeMode(String raw) {
        return DEFAULT_PROFILE_ID;
    }

    private static String fileNameToId(String fileName) {
        return fileName.endsWith(".properties")
                ? fileName.substring(0, fileName.length() - ".properties".length())
                : fileName;
    }

    private static Map<String, String> defaultEntriesHex() {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (PaletteEntry entry : BlockPalette.DEFAULT.entries) {
            defaults.put(
                    entry.blockState(),
                    String.format(Locale.ROOT, "#%02x%02x%02x", entry.red(), entry.green(), entry.blue()));
        }
        return defaults;
    }

    private static String buildSignature(String mode, String inherits, Map<String, String> effectiveEntries) {
        StringBuilder builder = new StringBuilder();
        builder.append(mode).append('|').append(Objects.toString(inherits, "")).append('|');
        effectiveEntries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';'));
        return Integer.toHexString(builder.toString().hashCode());
    }

    private record ProfileDefinition(String id,
                                     String mode,
                                     String inherits,
                                     Map<String, String> directEntries) {
    }

    record ProfileSummary(String id,
                          String mode,
                          String inherits,
                          boolean builtin,
                          Path path) {
    }

    record PaletteProfile(String id,
                          String inherits,
                          String mode,
                          boolean builtin,
                          Path path,
                          Map<String, String> directEntries,
                          Map<String, String> effectiveEntries,
                          String signature) {
    }

    record GeneratedProfile(String id, Path path, int blockCount) {
    }
}
