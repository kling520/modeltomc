package org.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ColorAccumulator {
    long red;
    long green;
    long blue;
    long count;

    void add(int argb) {
        red += (argb >> 16) & 0xFF;
        green += (argb >> 8) & 0xFF;
        blue += argb & 0xFF;
        count++;
    }

    int averageRgb() {
        int r = (int) (red / Math.max(1, count));
        int g = (int) (green / Math.max(1, count));
        int b = (int) (blue / Math.max(1, count));
        return (r << 16) | (g << 8) | b;
    }
}

final class BlockPalette {
    static final BlockPalette DEFAULT = new BlockPalette(List.of(
            // ===== 混凝土 (16) =====
            new PaletteEntry("minecraft:white_concrete", 207, 213, 214),
            new PaletteEntry("minecraft:light_gray_concrete", 125, 125, 115),
            new PaletteEntry("minecraft:gray_concrete", 54, 57, 61),
            new PaletteEntry("minecraft:black_concrete", 8, 10, 15),
            new PaletteEntry("minecraft:brown_concrete", 96, 59, 31),
            new PaletteEntry("minecraft:red_concrete", 142, 32, 32),
            new PaletteEntry("minecraft:orange_concrete", 224, 97, 0),
            new PaletteEntry("minecraft:yellow_concrete", 241, 175, 21),
            new PaletteEntry("minecraft:lime_concrete", 94, 168, 24),
            new PaletteEntry("minecraft:green_concrete", 73, 91, 36),
            new PaletteEntry("minecraft:cyan_concrete", 21, 119, 136),
            new PaletteEntry("minecraft:light_blue_concrete", 36, 137, 199),
            new PaletteEntry("minecraft:blue_concrete", 44, 46, 143),
            new PaletteEntry("minecraft:purple_concrete", 100, 32, 156),
            new PaletteEntry("minecraft:magenta_concrete", 169, 48, 159),
            new PaletteEntry("minecraft:pink_concrete", 214, 101, 143),
            // ===== 羊毛 (16) =====
            new PaletteEntry("minecraft:white_wool", 233, 236, 236),
            new PaletteEntry("minecraft:light_gray_wool", 164, 168, 184),
            new PaletteEntry("minecraft:gray_wool", 64, 64, 64),
            new PaletteEntry("minecraft:black_wool", 30, 27, 27),
            new PaletteEntry("minecraft:brown_wool", 131, 84, 50),
            new PaletteEntry("minecraft:red_wool", 160, 39, 34),
            new PaletteEntry("minecraft:orange_wool", 240, 118, 19),
            new PaletteEntry("minecraft:yellow_wool", 248, 198, 39),
            new PaletteEntry("minecraft:lime_wool", 112, 185, 25),
            new PaletteEntry("minecraft:green_wool", 84, 109, 27),
            new PaletteEntry("minecraft:cyan_wool", 22, 156, 156),
            new PaletteEntry("minecraft:light_blue_wool", 58, 175, 217),
            new PaletteEntry("minecraft:blue_wool", 37, 49, 146),
            new PaletteEntry("minecraft:purple_wool", 121, 42, 172),
            new PaletteEntry("minecraft:magenta_wool", 190, 68, 190),
            new PaletteEntry("minecraft:pink_wool", 237, 141, 172),
            // ===== 陶瓦 (17) =====
            new PaletteEntry("minecraft:terracotta", 152, 95, 67),
            new PaletteEntry("minecraft:white_terracotta", 209, 178, 161),
            new PaletteEntry("minecraft:light_gray_terracotta", 135, 107, 98),
            new PaletteEntry("minecraft:gray_terracotta", 58, 42, 36),
            new PaletteEntry("minecraft:black_terracotta", 37, 23, 16),
            new PaletteEntry("minecraft:brown_terracotta", 77, 51, 36),
            new PaletteEntry("minecraft:red_terracotta", 143, 61, 47),
            new PaletteEntry("minecraft:orange_terracotta", 162, 84, 38),
            new PaletteEntry("minecraft:yellow_terracotta", 186, 133, 35),
            new PaletteEntry("minecraft:lime_terracotta", 103, 118, 53),
            new PaletteEntry("minecraft:green_terracotta", 76, 83, 42),
            new PaletteEntry("minecraft:cyan_terracotta", 86, 91, 92),
            new PaletteEntry("minecraft:light_blue_terracotta", 113, 109, 138),
            new PaletteEntry("minecraft:blue_terracotta", 74, 60, 91),
            new PaletteEntry("minecraft:purple_terracotta", 118, 70, 86),
            new PaletteEntry("minecraft:magenta_terracotta", 150, 88, 109),
            new PaletteEntry("minecraft:pink_terracotta", 162, 78, 79),
            new PaletteEntry("minecraft:black_glazed_terracotta", 67, 30, 32),
            // ===== 木板与原木 (14) =====
            new PaletteEntry("minecraft:oak_planks", 162, 130, 79),
            new PaletteEntry("minecraft:spruce_planks", 114, 84, 48),
            new PaletteEntry("minecraft:birch_planks", 193, 180, 143),
            new PaletteEntry("minecraft:jungle_planks", 160, 113, 67),
            new PaletteEntry("minecraft:acacia_planks", 178, 94, 53),
            new PaletteEntry("minecraft:dark_oak_planks", 67, 43, 20),
            new PaletteEntry("minecraft:mangrove_planks", 97, 59, 39),
            new PaletteEntry("minecraft:cherry_planks", 255, 215, 212),
            new PaletteEntry("minecraft:crimson_planks", 107, 48, 53),
            new PaletteEntry("minecraft:warped_planks", 35, 80, 88),
            new PaletteEntry("minecraft:bamboo_planks", 202, 171, 108),
            new PaletteEntry("minecraft:dark_oak_log", 60, 46, 26),
            new PaletteEntry("minecraft:spruce_log", 58, 37, 16),
            new PaletteEntry("minecraft:stripped_dark_oak_log", 72, 56, 36),
            // ===== 树叶 (9) =====
            new PaletteEntry("minecraft:oak_leaves", 48, 96, 48),
            new PaletteEntry("minecraft:spruce_leaves", 39, 70, 38),
            new PaletteEntry("minecraft:birch_leaves", 70, 106, 49),
            new PaletteEntry("minecraft:jungle_leaves", 37, 94, 37),
            new PaletteEntry("minecraft:acacia_leaves", 42, 85, 38),
            new PaletteEntry("minecraft:dark_oak_leaves", 37, 70, 34),
            new PaletteEntry("minecraft:mangrove_leaves", 48, 94, 39),
            new PaletteEntry("minecraft:cherry_leaves", 173, 178, 222),
            new PaletteEntry("minecraft:azalea_leaves", 70, 110, 50),
            // ===== 石头类 (27) =====
            new PaletteEntry("minecraft:stone", 125, 125, 125),
            new PaletteEntry("minecraft:cobblestone", 117, 117, 117),
            new PaletteEntry("minecraft:mossy_cobblestone", 106, 116, 94),
            new PaletteEntry("minecraft:andesite", 136, 136, 137),
            new PaletteEntry("minecraft:polished_andesite", 140, 140, 141),
            new PaletteEntry("minecraft:diorite", 188, 188, 188),
            new PaletteEntry("minecraft:polished_diorite", 192, 192, 192),
            new PaletteEntry("minecraft:granite", 149, 103, 85),
            new PaletteEntry("minecraft:polished_granite", 154, 108, 90),
            new PaletteEntry("minecraft:deepslate", 77, 77, 82),
            new PaletteEntry("minecraft:cobbled_deepslate", 74, 74, 79),
            new PaletteEntry("minecraft:polished_deepslate", 82, 82, 86),
            new PaletteEntry("minecraft:deepslate_bricks", 80, 80, 85),
            new PaletteEntry("minecraft:deepslate_tiles", 78, 78, 82),
            new PaletteEntry("minecraft:chiseled_deepslate", 54, 54, 54),
            new PaletteEntry("minecraft:cracked_deepslate_tiles", 52, 52, 52),
            new PaletteEntry("minecraft:sculk", 12, 29, 36),
            new PaletteEntry("minecraft:tuff", 104, 101, 94),
            new PaletteEntry("minecraft:calcite", 226, 223, 214),
            new PaletteEntry("minecraft:dripstone_block", 131, 111, 102),
            new PaletteEntry("minecraft:sandstone", 216, 210, 174),
            new PaletteEntry("minecraft:red_sandstone", 189, 105, 72),
            new PaletteEntry("minecraft:smooth_stone", 158, 158, 158),
            new PaletteEntry("minecraft:stone_bricks", 123, 123, 123),
            new PaletteEntry("minecraft:mossy_stone_bricks", 108, 118, 96),
            new PaletteEntry("minecraft:cracked_stone_bricks", 123, 121, 118),
            new PaletteEntry("minecraft:obsidian", 19, 16, 27),
            new PaletteEntry("minecraft:crying_obsidian", 25, 17, 48),
            new PaletteEntry("minecraft:blackstone", 44, 39, 46),
            new PaletteEntry("minecraft:basalt", 70, 70, 74),
            new PaletteEntry("minecraft:smooth_basalt", 104, 104, 110),
            new PaletteEntry("minecraft:polished_basalt", 90, 90, 96),
            // ===== 金属与矿石块 (16) =====
            new PaletteEntry("minecraft:iron_block", 220, 220, 220),
            new PaletteEntry("minecraft:gold_block", 250, 208, 49),
            new PaletteEntry("minecraft:diamond_block", 100, 233, 221),
            new PaletteEntry("minecraft:emerald_block", 84, 208, 103),
            new PaletteEntry("minecraft:lapis_block", 33, 58, 138),
            new PaletteEntry("minecraft:redstone_block", 190, 35, 17),
            new PaletteEntry("minecraft:copper_block", 192, 108, 80),
            new PaletteEntry("minecraft:exposed_copper", 161, 126, 104),
            new PaletteEntry("minecraft:weathered_copper", 108, 153, 110),
            new PaletteEntry("minecraft:oxidized_copper", 82, 163, 133),
            new PaletteEntry("minecraft:netherite_block", 66, 61, 65),
            new PaletteEntry("minecraft:coal_block", 15, 15, 15),
            new PaletteEntry("minecraft:amethyst_block", 132, 97, 209),
            new PaletteEntry("minecraft:quartz_block", 236, 233, 226),
            new PaletteEntry("minecraft:smooth_quartz", 229, 225, 217),
            new PaletteEntry("minecraft:chiseled_quartz_block", 229, 224, 218),
            // ===== 下界 (13，不含发光方块) =====
            new PaletteEntry("minecraft:netherrack", 108, 49, 49),
            new PaletteEntry("minecraft:nether_bricks", 44, 22, 25),
            new PaletteEntry("minecraft:red_nether_bricks", 72, 20, 18),
            new PaletteEntry("minecraft:cracked_nether_bricks", 42, 22, 26),
            new PaletteEntry("minecraft:chiseled_nether_bricks", 46, 24, 28),
            new PaletteEntry("minecraft:soul_sand", 78, 58, 42),
            new PaletteEntry("minecraft:soul_soil", 77, 60, 52),
            new PaletteEntry("minecraft:nether_wart_block", 115, 11, 25),
            new PaletteEntry("minecraft:warped_wart_block", 22, 119, 121),
            new PaletteEntry("minecraft:ancient_debris", 104, 70, 61),
            new PaletteEntry("minecraft:crimson_nylium", 117, 31, 34),
            new PaletteEntry("minecraft:warped_nylium", 38, 89, 87),
            // ===== 末地 (7) =====
            new PaletteEntry("minecraft:end_stone", 219, 220, 163),
            new PaletteEntry("minecraft:end_stone_bricks", 212, 212, 167),
            new PaletteEntry("minecraft:purpur_block", 169, 128, 170),
            new PaletteEntry("minecraft:purpur_pillar", 164, 124, 165),
            new PaletteEntry("minecraft:chorus_plant", 126, 90, 127),
            // ===== 地表 (20) =====
            new PaletteEntry("minecraft:dirt", 134, 96, 67),
            new PaletteEntry("minecraft:coarse_dirt", 119, 85, 59),
            new PaletteEntry("minecraft:rooted_dirt", 133, 96, 66),
            new PaletteEntry("minecraft:podzol", 121, 83, 49),
            new PaletteEntry("minecraft:sand", 218, 210, 158),
            new PaletteEntry("minecraft:red_sand", 190, 102, 57),
            new PaletteEntry("minecraft:gravel", 130, 125, 122),
            new PaletteEntry("minecraft:clay", 158, 163, 171),
            new PaletteEntry("minecraft:mud", 60, 43, 27),
            new PaletteEntry("minecraft:packed_mud", 133, 105, 74),
            new PaletteEntry("minecraft:mud_bricks", 92, 68, 51),
            new PaletteEntry("minecraft:snow_block", 240, 245, 250),
            // ===== 结构砖 (9，不含发光方块) =====
            new PaletteEntry("minecraft:bricks", 145, 88, 80),
            new PaletteEntry("minecraft:prismarine", 99, 168, 160),
            new PaletteEntry("minecraft:prismarine_bricks", 105, 170, 152),
            new PaletteEntry("minecraft:dark_prismarine", 46, 92, 91),
            new PaletteEntry("minecraft:moss_block", 89, 109, 45),
            new PaletteEntry("minecraft:hay_block", 168, 139, 25),
            new PaletteEntry("minecraft:bone_block", 219, 214, 181),
            new PaletteEntry("minecraft:dried_kelp_block", 52, 70, 58),
            // ===== 杂项 (13，不含发光方块) =====
            new PaletteEntry("minecraft:melon", 105, 146, 51),
            new PaletteEntry("minecraft:pumpkin", 195, 121, 23),
            new PaletteEntry("minecraft:brown_mushroom_block", 148, 112, 82),
            new PaletteEntry("minecraft:red_mushroom_block", 178, 57, 54),
            new PaletteEntry("minecraft:mushroom_stem", 206, 202, 190),
            new PaletteEntry("minecraft:sponge", 195, 195, 84),
            new PaletteEntry("minecraft:wet_sponge", 158, 172, 83)
    ));

    static final Set<String> SUPPORTED_BLOCK_STATES = DEFAULT.entries.stream()
            .map(PaletteEntry::blockState)
            .collect(Collectors.toUnmodifiableSet());

    final List<PaletteEntry> entries;

    /** 用户自定义覆盖: blockState name -> 0xRRGGBB，优先级高于 entries 与规则匹配。 */
    private final Map<String, Integer> overrides = new HashMap<>();
    /**
     * 当前映射文件展开后的完整候选集；非空时最近色匹配只在这份条目里进行，
     * 未列出的方块一律不参与匹配（映射文件即完整调色板，而非对内置调色板的局部微调）。
     */
    private volatile List<PaletteEntry> profileEntries;
    /** rgb -> 最近方块 缓存：2km 大模型体素可达数百万，避免每个体素都重新遍历整个调色板。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> closestBlockCache = new java.util.concurrent.ConcurrentHashMap<>();
    BlockPalette(List<PaletteEntry> entries) {
        this.entries = entries;
    }

    static boolean supportsBlock(String blockState) {
        if (blockState == null || blockState.isBlank()) {
            return false;
        }
        return SUPPORTED_BLOCK_STATES.contains(ParsedBlockState.parse(blockState).name());
    }

    /** 应用用户映射文件的全部条目（预览/写入/导出前调用）；空映射回退到内置完整调色板。 */
    void applyOverrides(Map<String, Integer> customColors) {
        synchronized (overrides) {
            overrides.clear();
            if (customColors != null) {
                overrides.putAll(customColors);
            }
        }
        List<PaletteEntry> applied = null;
        if (customColors != null && !customColors.isEmpty()) {
            applied = new ArrayList<>(customColors.size());
            for (Map.Entry<String, Integer> entry : customColors.entrySet()) {
                int rgbValue = entry.getValue();
                applied.add(new PaletteEntry(
                        entry.getKey(),
                        (rgbValue >> 16) & 0xFF,
                        (rgbValue >> 8) & 0xFF,
                        rgbValue & 0xFF));
            }
        }
        profileEntries = applied;
        closestBlockCache.clear();
    }

    /** 保留旧接口兼容；当前已移除 simple 模式，所有匹配都使用完整调色板。 */
    void applyMode(String paletteMode) {
        // no-op
    }

    Map<String, Integer> overrides() {
        synchronized (overrides) {
            return new HashMap<>(overrides);
        }
    }

    /** 当前生效的匹配候选数：映射文件存在时为其条目数，否则为内置调色板数。 */
    int activeEntryCount() {
        List<PaletteEntry> profile = profileEntries;
        return profile != null ? profile.size() : entries.size();
    }

    /** 是否正在使用映射文件限定的调色板（而非内置完整调色板）。 */
    boolean usesProfilePalette() {
        return profileEntries != null;
    }

    String closestBlock(int rgb) {
        String cached = closestBlockCache.get(rgb);
        if (cached != null) {
            return cached;
        }

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        // 候选集：映射文件条目优先；未提供映射文件时用内置完整调色板
        List<PaletteEntry> candidates = profileEntries != null ? profileEntries : entries;
        PaletteEntry best = candidates.getFirst();
        long bestDistance = Long.MAX_VALUE;
        for (PaletteEntry entry : candidates) {
            long distance = colorDistance(r, g, b, entry.red(), entry.green(), entry.blue());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry;
            }
        }

        String blockState = best.blockState();
        if (closestBlockCache.size() < 262_144) {
            closestBlockCache.putIfAbsent(rgb, blockState);
        }
        return blockState;
    }

    private static long colorDistance(int r, int g, int b, int er, int eg, int eb) {
        long dr = r - er;
        long dg = g - eg;
        long db = b - eb;
        return dr * dr + dg * dg + db * db;
    }

    /** 方块在调色板中的代表色（自定义覆盖优先于默认条目）；不在调色板中时返回 -1。 */
    int blockColor(String blockState) {
        return blockColor(blockState, overrides());
    }

    /** 同上，但使用调用方预先取得的 overrides 快照：去杂等热点循环里避免每次 synchronized+复制。 */
    int blockColor(String blockState, Map<String, Integer> overridesSnapshot) {
        String name = ParsedBlockState.parse(blockState).name();
        Integer override = overridesSnapshot.get(name);
        if (override != null) {
            return override;
        }
        for (PaletteEntry entry : entries) {
            if (entry.blockState().equals(name)) {
                return rgb(entry.red(), entry.green(), entry.blue());
            }
        }
        return -1;
    }

    int previewColor(String blockState) {
        String name = ParsedBlockState.parse(blockState).name();
        int exact = blockColor(blockState);
        if (exact >= 0) {
            return exact;
        }

        if (name.contains("water")) {
            return rgb(64, 96, 255);
        }
        if (name.contains("lava")) {
            return rgb(255, 103, 32);
        }
        if (name.contains("grass_block")) {
            return rgb(111, 171, 74);
        }
        if (name.contains("leaves")) {
            return rgb(74, 118, 52);
        }
        if (name.contains("log") || name.contains("wood")) {
            return rgb(117, 84, 54);
        }
        if (name.contains("dirt") || name.contains("podzol") || name.contains("mud")) {
            return rgb(122, 85, 54);
        }
        if (name.contains("sand")) {
            return rgb(218, 210, 158);
        }
        if (name.contains("snow")) {
            return rgb(240, 245, 250);
        }
        if (name.contains("ice")) {
            return rgb(148, 194, 255);
        }
        if (name.contains("deepslate")) {
            return rgb(77, 77, 82);
        }
        if (name.contains("stone") || name.contains("andesite") || name.contains("diorite")
                || name.contains("granite") || name.contains("cobblestone") || name.contains("gravel")
                || name.contains("tuff") || name.contains("ore")) {
            return rgb(128, 128, 128);
        }
        if (name.contains("netherrack")) {
            return rgb(108, 49, 49);
        }
        if (name.contains("end_stone")) {
            return rgb(219, 220, 163);
        }
        if (name.endsWith("air")) {
            return 0;
        }

        // 未知方块统一到柔和地表色系，避免预览里出现突兀的杂色
        int hash = Math.abs(name.hashCode());
        int red = 96 + (hash & 0x2F);
        int green = 96 + ((hash >>> 8) & 0x2F);
        int blue = 88 + ((hash >>> 16) & 0x2F);
        return rgb(red, green, blue);
    }

    private int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }
}

record PaletteEntry(String blockState, int red, int green, int blue) {
}

final class PaletteBuilder {
    final LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();

    PaletteBuilder() {
        palette.put("minecraft:air", 0);
    }

    int indexOf(String blockState) {
        return palette.computeIfAbsent(blockState, ignored -> palette.size());
    }

    LinkedHashMap<String, Integer> palette() {
        return palette;
    }

    List<String> statesByIndex() {
        String[] states = new String[palette.size()];
        for (Map.Entry<String, Integer> entry : palette.entrySet()) {
            states[entry.getValue()] = entry.getKey();
        }
        return List.of(states);
    }
}

record ParsedBlockState(String name, LinkedHashMap<String, String> properties) {
    static ParsedBlockState parse(String blockState) {
        int bracketIndex = blockState.indexOf('[');
        if (bracketIndex < 0 || !blockState.endsWith("]")) {
            return new ParsedBlockState(blockState, new LinkedHashMap<>());
        }

        String name = blockState.substring(0, bracketIndex);
        String rawProperties = blockState.substring(bracketIndex + 1, blockState.length() - 1);
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        if (!rawProperties.isEmpty()) {
            for (String property : rawProperties.split(",")) {
                int separator = property.indexOf('=');
                if (separator > 0 && separator < property.length() - 1) {
                    properties.put(property.substring(0, separator), property.substring(separator + 1));
                }
            }
        }
        return new ParsedBlockState(name, properties);
    }
}
