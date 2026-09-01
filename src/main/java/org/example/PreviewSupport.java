package org.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.pauleff.jnbt.api.ICompoundTag;
import de.pauleff.jnbt.api.IListTag;

final class PreviewSupport {
    /** 预览地形最大采样列数：超过后按列步进降采样，防止多模型距离过远时对整个包围盒逐列采样导致 OOM。 */
    private static final int MAX_PREVIEW_TERRAIN_COLUMNS = 200_000;
    /** 悬崖/裙边最大填充深度：避免把存档里已有的高建筑整面墙当地形输出（每个采样列最多补这么多格）。 */
    private static final int MAX_CLIFF_DEPTH = 16;

    private PreviewSupport() {
    }

    static Map<String, Object> generate(Config config) throws IOException {
        return generate(config, null);
    }

    static Map<String, Object> generate(Config config, TaskProgress progress) throws IOException {
        // 应用映射文件的全部条目（体素化去杂、映射候选提取、建筑预览、地形着色都依赖它），
        // 必须先于体素化执行：缓存命中时体素器内部不会再 applyOverrides
        BlockPalette.DEFAULT.applyOverrides(config.paletteOverrides);
        BlockPalette.DEFAULT.applyMode(config.paletteMode);
        if (BlockPalette.DEFAULT.usesProfilePalette()) {
            System.out.printf(Locale.ROOT,
                    "预览: 使用映射文件 %s (%d 个方块参与匹配)%n",
                    config.paletteProfile,
                    BlockPalette.DEFAULT.activeEntryCount());
        }
        // 先置为 parse（firstPass 一开始就会回报字节级进度，单模型不再全程停在 0/1）
        if (progress != null) {
            progress.phase = "parse";
            progress.done = 0;
            progress.total = config.models.size();
            progress.subDone = 0;
            progress.subTotal = 0;
        }
        List<SparseVoxelGrid> grids = ModelVoxelizer.voxelizeAll(config.models, config,
                done -> {
                    if (progress != null) {
                        progress.done = done;
                    }
                },
                (phase, subDone, subTotal) -> {
                    if (progress != null) {
                        progress.phase = phase;
                        progress.subDone = subDone;
                        progress.subTotal = subTotal;
                    }
                });
        if (progress != null) {
            progress.phase = "terrain";
            progress.done = 0;
            progress.total = 1;
            progress.subDone = 0;
            progress.subTotal = 0;
        }

        // 先收集全部模型的世界包围盒，用于确定地形预览范围与相机取景
        int buildingMinX = Integer.MAX_VALUE;
        int buildingMaxX = Integer.MIN_VALUE;
        int buildingMinY = Integer.MAX_VALUE;
        int buildingMaxY = Integer.MIN_VALUE;
        int buildingMinZ = Integer.MAX_VALUE;
        int buildingMaxZ = Integer.MIN_VALUE;
        int terrainPadding = config.previewPadding;
        // 地形预览范围 = 每个模型各自向外扩展 previewPadding 格后的并集（多模型相距远时每个模型四周都会带出地形）
        int previewMinX = Integer.MAX_VALUE;
        int previewMaxX = Integer.MIN_VALUE;
        int previewMinZ = Integer.MAX_VALUE;
        int previewMaxZ = Integer.MIN_VALUE;
        for (int i = 0; i < config.models.size(); i++) {
            PlacedModel model = config.models.get(i);
            PlacementAnchor anchor = PlacementAnchor.fromVoxelGrid(grids.get(i));
            int modelMinX = anchor.worldMinX(model.placeX());
            int modelMaxX = anchor.worldMaxX(model.placeX());
            int modelMinY = anchor.worldMinY(model.placeY());
            int modelMaxY = anchor.worldMaxY(model.placeY());
            int modelMinZ = anchor.worldMinZ(model.placeZ());
            int modelMaxZ = anchor.worldMaxZ(model.placeZ());
            buildingMinX = Math.min(buildingMinX, modelMinX);
            buildingMaxX = Math.max(buildingMaxX, modelMaxX);
            buildingMinY = Math.min(buildingMinY, modelMinY);
            buildingMaxY = Math.max(buildingMaxY, modelMaxY);
            buildingMinZ = Math.min(buildingMinZ, modelMinZ);
            buildingMaxZ = Math.max(buildingMaxZ, modelMaxZ);
            previewMinX = Math.min(previewMinX, modelMinX - terrainPadding);
            previewMaxX = Math.max(previewMaxX, modelMaxX + terrainPadding);
            previewMinZ = Math.min(previewMinZ, modelMinZ - terrainPadding);
            previewMaxZ = Math.max(previewMaxZ, modelMaxZ + terrainPadding);
        }

        // 对整个预览区域连续采样（保证模型之间的地形连续）。区域过大时按列步进降采样，
        // 每个采样列渲染为 step×1×step 的大块，既不 OOM 也不会出现断裂/稀疏薄片
        int terrainStep = 1;
        long totalColumns = (long) (previewMaxX - previewMinX + 1) * (previewMaxZ - previewMinZ + 1);
        if (totalColumns > MAX_PREVIEW_TERRAIN_COLUMNS) {
            terrainStep = Math.max(1, (int) Math.ceil(Math.sqrt(totalColumns / (double) MAX_PREVIEW_TERRAIN_COLUMNS)));
        }
        for (int i = 0; i < grids.size(); i++) {
            System.out.printf(Locale.ROOT,
                    "预览: 模型 %d 体素 %,d%n",
                    i + 1,
                    grids.get(i).size());
        }
        System.out.printf(Locale.ROOT,
                "预览: 地形区域 %dx%d, 采样步长 %d%n",
                previewMaxX - previewMinX + 1,
                previewMaxZ - previewMinZ + 1,
                terrainStep);

        // 渲染统一以模型脚底为基准再往下留 2 格，建筑与地形共用同一原点（不依赖地形采样，先于释放模型网格定下）
        int originY = buildingMinY - 2;

        boolean terrainEnabled = config.worldPath != null;

        // 先构建建筑预览体块（需要模型体素网格），有地形时再逐出缓存给地形采样腾堆；
        // 纯模型预览保留缓存，这样重复刷新同一模型时不用再次体素化。
        // 全局方块去重表：多模型各自产出游程后统一重映射下标，避免同名方块重复占位
        Map<String, Integer> globalBlockIndex = new HashMap<>();
        List<Map<String, Object>> blockTable = new ArrayList<>();
        IntArray buildingRuns = new IntArray();
        long totalBuildingCount = 0;
        // 各模型游程在 building 数组中的起始下标（盒子数计）：前端据此把建筑网格按模型拆分，
        // 支持预览里点选单个模型、轴线只作用于被选模型。末尾额外存一个总结束下标。
        int[] modelRunStarts = new int[config.models.size() + 1];
        Map<String, String> generatedPaletteEntries = null;
        PaletteStore.GeneratedProfile generatedProfile = null;
        if (config.generateModelPalette) {
            generatedPaletteEntries = ModelVoxelizer.collectGeneratedPaletteEntries(grids);
            System.out.printf(Locale.ROOT,
                    "预览: 已提取模型方块映射候选（%d 个方块）%n",
                    generatedPaletteEntries.size());
            if (config.generatedPaletteName != null) {
                generatedProfile = ModelVoxelizer.generateNamedPaletteProfile(config.generatedPaletteName, grids);
                System.out.printf(Locale.ROOT,
                        "预览: 已生成模型方块映射文件: %s (%d 个方块, %s)%n",
                        generatedProfile.id(),
                        generatedProfile.blockCount(),
                        generatedProfile.path());
            }
        }
        for (int i = 0; i < config.models.size(); i++) {
            PlacedModel model = config.models.get(i);
            SparseVoxelGrid grid = grids.get(i);
            if (progress != null) {
                // 千万级体素的表面提取/游程合并要几秒到几十秒，按体素数上报百分比
                progress.phase = "build";
                progress.subDone = 0;
                progress.subTotal = grid.size();
            }
            PlacementAnchor anchor = PlacementAnchor.fromVoxelGrid(grid);
            BuildingBlocks modelBlocks = buildBuildingPreview(
                    model,
                    grid,
                    anchor,
                    previewMinX,
                    originY,
                    previewMinZ,
                    progress);
            int[] runs = modelBlocks.runs();
            // 各模型独立去重的下标 -> 全局表下标
            int[] remap = new int[modelBlocks.blockTable().size()];
            for (int b = 0; b < remap.length; b++) {
                String block = String.valueOf(modelBlocks.blockTable().get(b).get("block"));
                Integer mapped = globalBlockIndex.get(block);
                if (mapped == null) {
                    mapped = blockTable.size();
                    globalBlockIndex.put(block, mapped);
                    blockTable.add(modelBlocks.blockTable().get(b));
                }
                remap[b] = mapped;
            }
            for (int r = 0; r < runs.length; r += 7) {
                runs[r + 6] = remap[runs[r + 6]];
            }
            modelRunStarts[i] = buildingRuns.size() / 7;
            totalBuildingCount += runs.length / 7;
            buildingRuns.addAll(runs);
        }
        modelRunStarts[config.models.size()] = buildingRuns.size() / 7;
        System.out.printf(Locale.ROOT,
                "预览: 建筑盒子 %,d 个, 方块种类 %d 种%n",
                totalBuildingCount,
                blockTable.size());
        // 有地形时才释放体素缓存，避免地形采样与大模型缓存叠加占用过多内存。
        if (terrainEnabled) {
            for (PlacedModel model : config.models) {
                ModelVoxelizer.evict(model, config);
            }
            grids = null;
        }

        int terrainMinY = buildingMinY - 1;
        int terrainMaxY = buildingMinY - 1;
        TopBlock targetSurface = null;
        IntArray terrainBlocks = new IntArray();
        long expectedColumns = 0;
        long loadedColumns = 0;
        PlacedModel primary = config.primary();
        if (terrainEnabled) {
            PreviewWorldReader worldReader = new PreviewWorldReader(config.regionDirectory());
            try {
                PreviewTerrain terrain = worldReader.collectSurfaceBlocks(
                        previewMinX,
                        previewMaxX,
                        previewMinZ,
                        previewMaxZ,
                        terrainStep,
                        progress);
                List<TerrainBlock> terrainSamples = terrain.blocks();
                expectedColumns = terrain.expectedColumns();
                loadedColumns = terrain.loadedColumns();
                System.out.printf(Locale.ROOT,
                        "预览: 地形采样完成, 列 %,d/%,d, 地形方块 %,d%n",
                        loadedColumns,
                        expectedColumns,
                        terrainSamples.size());

                terrainMinY = terrainSamples.stream()
                        .map(TerrainBlock::y)
                        .min(Comparator.naturalOrder())
                        .orElse(buildingMinY - 1);
                terrainMaxY = terrainSamples.stream()
                        .map(TerrainBlock::y)
                        .max(Comparator.naturalOrder())
                        .orElse(buildingMinY - 1);
                targetSurface = worldReader.findTopBlock(primary.placeX(), primary.placeZ());

                terrainBlocks = new IntArray();
                for (TerrainBlock sample : terrainSamples) {
                    terrainBlocks.add(sample.worldX() - previewMinX);
                    terrainBlocks.add(sample.y() - originY);
                    terrainBlocks.add(sample.worldZ() - previewMinZ);
                    terrainBlocks.add(sample.color());
                }
            } finally {
                // 立即释放预览期间打开的全部 region 文件句柄：
                // Windows 上这些只读句柄如果残留，会阻止随后写入时替换同名 .mca（AccessDenied），与游戏是否开启无关
                worldReader.close();
            }
        } else {
            System.out.println("预览: 未提供存档目录，跳过地形采样，仅加载模型");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("terrainEnabled", terrainEnabled);
        meta.put("terrainCount", terrainBlocks.size() / 4);
        meta.put("buildingCount", totalBuildingCount);
        meta.put("modelCount", config.models.size());
        meta.put("modelRunStarts", modelRunStarts);
        meta.put("terrainStep", terrainStep);
        meta.put("buildingStep", 1);
        meta.put("buildingStepXZ", 1);
        meta.put("buildingStepY", 1);
        meta.put("buildingSurfaceOnly", true);
        meta.put("terrainExpectedColumns", expectedColumns);
        meta.put("terrainLoadedColumns", loadedColumns);
        meta.put("originX", previewMinX);
        meta.put("originY", originY);
        meta.put("originZ", previewMinZ);
        meta.put("terrainMinY", terrainMinY - originY);
        meta.put("terrainMaxY", terrainMaxY - originY);
        meta.put("buildingMinX", buildingMinX - previewMinX);
        meta.put("buildingMaxX", buildingMaxX - previewMinX);
        meta.put("buildingMinY", buildingMinY - originY);
        meta.put("buildingMaxY", buildingMaxY - originY);
        meta.put("buildingMinZ", buildingMinZ - previewMinZ);
        meta.put("buildingMaxZ", buildingMaxZ - previewMinZ);
        meta.put("targetX", primary.placeX() - previewMinX);
        meta.put("targetY", primary.placeY() - originY);
        meta.put("targetZ", primary.placeZ() - previewMinZ);
        meta.put("surfaceY", targetSurface == null ? null : targetSurface.y() - originY);
        if (generatedPaletteEntries != null) {
            meta.put("generatedPaletteEntries", PaletteStore.toEntryList(generatedPaletteEntries));
            meta.put("generatedPaletteCount", generatedPaletteEntries.size());
        }
        if (generatedProfile != null) {
            meta.put("generatedPaletteProfileId", generatedProfile.id());
            meta.put("generatedPaletteCount", generatedProfile.blockCount());
        }

        // 紧凑格式：building = [x,y,z,sx,sy,sz,blockIdx]* 盒子 + blockTable；terrain = [x,y,z,color]*
        // 大模型（千万级游程）下比逐块对象小 5~6 倍，序列化/传输/解析全面提速
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terrain", terrainBlocks.toArray());
        payload.put("building", buildingRuns.toArray());
        payload.put("blockTable", blockTable);
        payload.put("meta", meta);
        if (progress != null) {
            // finished 由 WebUiServer 的后台任务在序列化+压缩完成后统一置位：
            // 这里提前置 true 会让前端在结果字节就绪前就发起 /api/preview-result
            progress.phase = "done";
            progress.done = progress.total;
        }
        return payload;
    }

    /** 建筑预览体块（紧凑格式）：盒子数据 [x,y,z,sx,sy,sz,blockIdx]*，配 blockTable 提供方块名与颜色。 */
    record BuildingBlocks(int[] runs, List<Map<String, Object>> blockTable) {
    }

    private static BuildingBlocks buildBuildingPreview(PlacedModel model,
                                                       SparseVoxelGrid voxelGrid,
                                                       PlacementAnchor anchor,
                                                       int originX,
                                                       int originY,
                                                       int originZ,
                                                       TaskProgress progress) {
        // 方块去重表：blockState -> 下标；颜色可由方块推出，不必随每个盒子重复传输
        Map<String, Integer> blockIndex = new HashMap<>();
        List<Map<String, Object>> blockTable = new ArrayList<>();

        // 体素网格是哈希表，迭代无序；提取 (z,x,y) 排序键后排序，
        // 才能按「行内 x 升序、列内 y 升序」单趟完成游程生成与 XZ 盒子合并。
        // 前置四步（扫描→收集→位图→排序）在亿级体素上合计几十秒，按固定权重上报进度：
        // 扫描 0-8%、收集 8-20%、位图 25%、排序 35%、主循环 35-100%。
        // 收集改并行分片：单线程 forEach 每体素还要 new int[3]（亿级体素就是亿次数组分配）
        long totalVoxels = voxelGrid.size();
        CollectScan scan = collectScan(voxelGrid, progress, totalVoxels);
        // 坐标按包围盒相对值编码（省掉高位零）；位宽放得下时把 24 位 RGB 一并打包进键，
        // 主循环查色从「每个表面体素一次哈希随机探测（约一次 cache miss）」变为一次移位解码
        SortKeyLayout layout = SortKeyLayout.of(scan);
        long[] sortKeys = new long[(int) totalVoxels];
        collectKeys(voxelGrid, scan, layout, sortKeys, progress, totalVoxels);

        // 占用位图：6 邻域表面判断从哈希表随机探测（每次探测约一次 cache miss，
        // 千万级体素模型耗时数十秒）换成稠密位测试（顺序内存、缓存行友好）。
        // 包围盒已在扫描阶段算出，这里无需再遍历一遍 keys 求 min/max。
        SurfaceBitmap occupancy = SurfaceBitmap.build(sortKeys, layout);
        if (progress != null) {
            progress.subDone = totalVoxels / 4;
        }
        // 大数组排序并行化（数千万元素时数秒级 -> 1s 级）；小数组自动退回串行。
        // parallelSort 无中途回调，排序期间进度停在 25%（约几十秒），排序完成跳到 35%
        java.util.Arrays.parallelSort(sortKeys);
        if (progress != null) {
            progress.subDone = totalVoxels * 35 / 100;
        }

        BuildingBoxMerger merger = new BuildingBoxMerger(model, anchor, originX, originY, originZ);
        long reportInterval = Math.max(1L, totalVoxels / 100L);
        int prevZ = -1;
        int prevX = -1;
        boolean hasPrev = false;
        boolean runActive = false;
        int runY = 0;
        int runTop = 0;
        int runBlock = -1;

        for (int i = 0; i < sortKeys.length; i++) {
            long sortKey = sortKeys[i];
            int z = layout.decodeZ(sortKey);
            int x = layout.decodeX(sortKey);
            int y = layout.decodeY(sortKey);
            if (progress != null && (i + 1) % reportInterval == 0L) {
                // 主循环覆盖 35-100%（前置步骤占了 0-35%）；long 运算防 2.36 亿级乘法溢出
                progress.subDone = totalVoxels * 35 / 100 + (i + 1L) * 65 / 100;
            }

            if (z != prevZ) {
                if (runActive) {
                    merger.run(prevX, prevZ, runY, runTop - runY + 1, runBlock);
                    runActive = false;
                }
                if (hasPrev) {
                    merger.endRow(prevZ);
                }
                hasPrev = true;
                prevZ = z;
                prevX = x;
            } else if (x != prevX) {
                if (runActive) {
                    merger.run(prevX, prevZ, runY, runTop - runY + 1, runBlock);
                    runActive = false;
                }
                prevX = x;
            }

            if (!isSurface(occupancy, x, y, z)) {
                if (runActive) {
                    merger.run(prevX, prevZ, runY, runTop - runY + 1, runBlock);
                    runActive = false;
                }
                continue;
            }
            // 打包模式下颜色就在键低 24 位，直接解码；回退模式才回哈希表随机探测。
            // sortKey 的字段顺序与 pack(x,y,z) 不同，不能拿 sortKey 当 pack 键，须真实坐标重打包
            int rgb = layout.packedRgb ? (int) (sortKey & 0xFFFFFF)
                    : voxelGrid.rgbPacked(SparseVoxelGrid.pack(x, y, z));
            String blockState = BlockPalette.DEFAULT.closestBlock(rgb);
            Integer index = blockIndex.get(blockState);
            if (index == null) {
                index = blockTable.size();
                blockIndex.put(blockState, index);
                blockTable.add(Map.of(
                        "block", blockState,
                        "color", BlockPalette.DEFAULT.previewColor(blockState)));
            }
            if (runActive && index == runBlock && y == runTop + 1) {
                runTop = y;
            } else {
                if (runActive) {
                    merger.run(prevX, prevZ, runY, runTop - runY + 1, runBlock);
                }
                runActive = true;
                runY = y;
                runTop = y;
                runBlock = index;
            }
        }
        if (runActive) {
            merger.run(prevX, prevZ, runY, runTop - runY + 1, runBlock);
        }
        merger.finish(prevZ);
        if (progress != null) {
            progress.subDone = totalVoxels;
        }
        return new BuildingBlocks(merger.boxes(), blockTable);
    }

    /** 扫描阶段结果：各分片体素数、写入起始下标（前缀和）与全局包围盒。 */
    private static final class CollectScan {
        int[] segCounts;
        int[] segStarts;
        int minX;
        int maxX;
        int minY;
        int maxY;
        int minZ;
        int maxZ;
    }

    /**
     * 排序键布局：坐标按包围盒相对值编码（字典序 z&gt;x&gt;y 与游程状态机兼容）。
     * packedRgb 模式把 24 位 RGB 塞进键低位，主循环取色从哈希随机探测
     * （每次约一次 cache miss，亿级体素累计 10 秒级）变为一次移位解码；
     * 包围盒位宽放不下时回退纯坐标模式（主循环照旧查哈希表，行为与旧版一致）。
     */
    private static final class SortKeyLayout {
        final boolean packedRgb;
        final int shiftZ;
        final int shiftX;
        final int shiftY;
        final int maskZ;
        final int maskX;
        final int maskY;
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;

        private SortKeyLayout(boolean packedRgb, int shiftZ, int shiftX, int shiftY,
                              int maskZ, int maskX, int maskY,
                              int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
            this.packedRgb = packedRgb;
            this.shiftZ = shiftZ;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.maskZ = maskZ;
            this.maskX = maskX;
            this.maskY = maskY;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        static SortKeyLayout of(CollectScan scan) {
            int bitsX = bitsFor(scan.maxX - scan.minX + 1);
            int bitsY = bitsFor(scan.maxY - scan.minY + 1);
            int bitsZ = bitsFor(scan.maxZ - scan.minZ + 1);
            // 总位宽 + 24 位 RGB ≤ 63：最高位留空，避免与哈希表的 EMPTY 哨兵（Long.MIN_VALUE）撞值
            if (bitsZ + bitsX + bitsY + 24 <= 63) {
                int shiftY = 24;
                int shiftX = 24 + bitsY;
                int shiftZ = 24 + bitsY + bitsX;
                return new SortKeyLayout(true, shiftZ, shiftX, shiftY,
                        maskFor(bitsZ), maskFor(bitsX), maskFor(bitsY),
                        scan.minX, scan.minY, scan.minZ,
                        scan.maxX, scan.maxY, scan.maxZ);
            }
            return new SortKeyLayout(false, 42, 21, 0,
                    0x1FFFFF, 0x1FFFFF, 0x1FFFFF,
                    scan.minX, scan.minY, scan.minZ,
                    scan.maxX, scan.maxY, scan.maxZ);
        }

        private static int bitsFor(int span) {
            return span <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(span - 1);
        }

        private static int maskFor(int bits) {
            return bits == 0 ? 0 : (int) ((1L << bits) - 1);
        }

        int decodeZ(long key) {
            return ((int) ((key >>> shiftZ) & maskZ)) + minZ;
        }

        int decodeX(long key) {
            return ((int) ((key >>> shiftX) & maskX)) + minX;
        }

        int decodeY(long key) {
            return ((int) ((key >>> shiftY) & maskY)) + minY;
        }
    }

    /**
     * 阶段1（并行分片）：只读扫描哈希槽区间，统计各段体素数与局部包围盒，主线程合并为
     * 全局包围盒与前缀和写入区间。相比单线程 forEach（每体素 new int[3] 的分配风暴）
     * 提速一个数量级；扫描完上报 8%。
     */
    private static CollectScan collectScan(SparseVoxelGrid grid, TaskProgress progress, long totalVoxels) {
        CollectScan scan = new CollectScan();
        scan.minX = scan.minY = scan.minZ = 0;
        scan.maxX = scan.maxY = scan.maxZ = 0;
        if (totalVoxels == 0L) {
            scan.segCounts = new int[1];
            scan.segStarts = new int[1];
            return scan;
        }
        int capacity = grid.slotCapacity();
        int threads = Math.max(1, Math.min(12, Runtime.getRuntime().availableProcessors()));
        if (totalVoxels < 2_000_000L) {
            threads = 1;
        }
        int stride = (capacity + threads - 1) / threads;
        int[] segCounts = new int[threads];
        // 每段局部包围盒：[t*3, t*3+3) 为 min 三元组，[threads*3 + t*3, ...) 为 max 三元组
        int[] bounds = new int[threads * 6];
        java.util.Arrays.fill(bounds, 0, threads * 3, Integer.MAX_VALUE);
        java.util.Arrays.fill(bounds, threads * 3, threads * 6, Integer.MIN_VALUE);
        long emptyKey = SparseVoxelGrid.emptySlotKey();
        final int threadCount = threads;
        java.util.concurrent.ExecutorService pool = null;
        if (threads > 1) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        }
        try {
            java.util.List<java.util.concurrent.Future<?>> futures =
                    new java.util.ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final int seg = t;
                final int start = t * stride;
                final int end = Math.min(start + stride, capacity);
                if (start >= end) {
                    continue;
                }
                Runnable task = () -> {
                    int count = 0;
                    int lMinX = Integer.MAX_VALUE, lMaxX = Integer.MIN_VALUE;
                    int lMinY = Integer.MAX_VALUE, lMaxY = Integer.MIN_VALUE;
                    int lMinZ = Integer.MAX_VALUE, lMaxZ = Integer.MIN_VALUE;
                    for (int i = start; i < end; i++) {
                        long key = grid.slotKey(i);
                        if (key == emptyKey) {
                            continue;
                        }
                        // 哈希表键布局为 pack(x,y,z)：x 占高 22 位、y 中 21 位、z 低 21 位
                        int x = (int) (key >>> 42);
                        int y = (int) ((key >>> 21) & 0x1FFFFF);
                        int z = (int) (key & 0x1FFFFF);
                        count++;
                        if (x < lMinX) lMinX = x;
                        if (x > lMaxX) lMaxX = x;
                        if (y < lMinY) lMinY = y;
                        if (y > lMaxY) lMaxY = y;
                        if (z < lMinZ) lMinZ = z;
                        if (z > lMaxZ) lMaxZ = z;
                    }
                    if (count > 0) {
                        segCounts[seg] = count;
                        bounds[seg * 3] = lMinX;
                        bounds[seg * 3 + 1] = lMinY;
                        bounds[seg * 3 + 2] = lMinZ;
                        bounds[threadCount * 3 + seg * 3] = lMaxX;
                        bounds[threadCount * 3 + seg * 3 + 1] = lMaxY;
                        bounds[threadCount * 3 + seg * 3 + 2] = lMaxZ;
                    }
                };
                if (pool == null) {
                    task.run();
                } else {
                    futures.add(pool.submit(task));
                }
            }
            if (pool != null) {
                for (java.util.concurrent.Future<?> future : futures) {
                    future.get();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("体素扫描被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("体素扫描失败", e.getCause());
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
        scan.segCounts = segCounts;
        scan.segStarts = new int[threads];
        int prefix = 0;
        int gMinX = Integer.MAX_VALUE, gMaxX = Integer.MIN_VALUE;
        int gMinY = Integer.MAX_VALUE, gMaxY = Integer.MIN_VALUE;
        int gMinZ = Integer.MAX_VALUE, gMaxZ = Integer.MIN_VALUE;
        for (int t = 0; t < threads; t++) {
            scan.segStarts[t] = prefix;
            prefix += segCounts[t];
            if (segCounts[t] > 0) {
                if (bounds[t * 3] < gMinX) gMinX = bounds[t * 3];
                if (bounds[t * 3 + 1] < gMinY) gMinY = bounds[t * 3 + 1];
                if (bounds[t * 3 + 2] < gMinZ) gMinZ = bounds[t * 3 + 2];
                if (bounds[threads * 3 + t * 3] > gMaxX) gMaxX = bounds[threads * 3 + t * 3];
                if (bounds[threads * 3 + t * 3 + 1] > gMaxY) gMaxY = bounds[threads * 3 + t * 3 + 1];
                if (bounds[threads * 3 + t * 3 + 2] > gMaxZ) gMaxZ = bounds[threads * 3 + t * 3 + 2];
            }
        }
        scan.minX = gMinX;
        scan.maxX = gMaxX;
        scan.minY = gMinY;
        scan.maxY = gMaxY;
        scan.minZ = gMinZ;
        scan.maxZ = gMaxZ;
        if (progress != null) {
            progress.subDone = totalVoxels * 8 / 100;
        }
        return scan;
    }

    /**
     * 阶段2（并行分片）：各段按阶段1算好的区间写入排序键（无竞争），进度 8-20%。
     * packed 模式键 = (z'&lt;&lt;shiftZ)|(x'&lt;&lt;shiftX)|(y'&lt;&lt;shiftY)|rgb（相对坐标 + 颜色）；
     * 回退模式键 = (z'&lt;&lt;42)|(x'&lt;&lt;21)|y'（相对坐标，与旧绝对坐标布局同字典序）。
     */
    private static void collectKeys(SparseVoxelGrid grid, CollectScan scan, SortKeyLayout layout,
                                    long[] sortKeys, TaskProgress progress, long totalVoxels) {
        if (totalVoxels == 0L || sortKeys.length == 0) {
            return;
        }
        int capacity = grid.slotCapacity();
        int threads = scan.segCounts.length;
        int stride = (capacity + threads - 1) / threads;
        long emptyKey = SparseVoxelGrid.emptySlotKey();
        boolean packed = layout.packedRgb;
        java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.ExecutorService pool = null;
        if (threads > 1) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        }
        try {
            java.util.List<java.util.concurrent.Future<?>> futures =
                    new java.util.ArrayList<>(threads);
            for (int t = 0; t < threads; t++) {
                final int start = t * stride;
                final int end = Math.min(start + stride, capacity);
                if (start >= end) {
                    continue;
                }
                final int outBase = scan.segStarts[t];
                Runnable task = () -> {
                    int out = outBase;
                    long localWritten = 0L;
                    for (int i = start; i < end; i++) {
                        long key = grid.slotKey(i);
                        if (key == emptyKey) {
                            continue;
                        }
                        int x = (int) (key >>> 42);
                        int y = (int) ((key >>> 21) & 0x1FFFFF);
                        int z = (int) (key & 0x1FFFFF);
                        if (packed) {
                            sortKeys[out++] = (((long) (z - layout.minZ) << layout.shiftZ)
                                    | ((long) (x - layout.minX) << layout.shiftX)
                                    | ((long) (y - layout.minY) << layout.shiftY)
                                    | grid.slotRgb(i));
                        } else {
                            sortKeys[out++] = (((long) (z - layout.minZ) << 42)
                                    | ((long) (x - layout.minX) << 21)
                                    | (long) (y - layout.minY));
                        }
                        localWritten++;
                        if (progress != null && (localWritten & 0xFFFFF) == 0L) {
                            progress.subDone = totalVoxels * (8 + (int) (written.addAndGet(localWritten) * 12 / totalVoxels)) / 100;
                            localWritten = 0L;
                        }
                    }
                    if (progress != null) {
                        written.addAndGet(localWritten);
                    }
                };
                if (pool == null) {
                    task.run();
                } else {
                    futures.add(pool.submit(task));
                }
            }
            if (pool != null) {
                for (java.util.concurrent.Future<?> future : futures) {
                    future.get();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("体素收集被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("体素收集失败", e.getCause());
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
        if (progress != null) {
            progress.subDone = totalVoxels * 20 / 100;
        }
    }

    /**
     * 竖直游程贪心合并为轴对齐盒子：行内先沿 X 合并相邻同属性 (y,sy,block) 游程，
     * 再沿 Z 合并完全相同的 X 段。盒子与逐游程渲染在几何上完全等价（每个被合并列
     * 的游程恰好铺满盒子），但实例数可降到 1/5 左右，大模型预览不再撑爆显存。
     */
    private static final class BuildingBoxMerger {
        private final PlacedModel model;
        private final PlacementAnchor anchor;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final IntArray boxes = new IntArray();
        /** 行内 X 开链：key = (y&lt;42)|(sy&lt;21)|blockIdx，值为 {x0, maxX}。 */
        private final HashMap<Long, int[]> openX = new HashMap<>();
        /** 上一行仍在延伸的盒子；下一行的延续目标。 */
        private HashMap<BoxChainKey, int[]> activeChains = new HashMap<>();
        private HashMap<BoxChainKey, int[]> nextChains = new HashMap<>();

        BuildingBoxMerger(PlacedModel model, PlacementAnchor anchor, int originX, int originY, int originZ) {
            this.model = model;
            this.anchor = anchor;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        int[] boxes() {
            return boxes.toArray();
        }

        /** 行内到达一条竖直游程（同 z 行内 x 必须升序）。 */
        void run(int x, int z, int y, int sy, int block) {
            long key = ((long) y << 42) | ((long) sy << 21) | (long) block;
            int[] segment = openX.get(key);
            if (segment != null && segment[1] + 1 == x) {
                segment[1] = x;
                return;
            }
            if (segment != null) {
                chainStep(z, segment[0], segment[1] - segment[0] + 1, y, sy, block);
            }
            openX.put(key, new int[]{x, x});
        }

        /** 结束一行：全部 X 段转链，未延续的盒子输出。 */
        void endRow(int z) {
            if (!openX.isEmpty()) {
                for (Map.Entry<Long, int[]> entry : openX.entrySet()) {
                    long key = entry.getKey();
                    int[] segment = entry.getValue();
                    chainStep(z, segment[0], segment[1] - segment[0] + 1,
                            (int) (key >>> 42), (int) ((key >>> 21) & 0x1FFFFF), (int) (key & 0x1FFFFF));
                }
                openX.clear();
            }
            for (int[] box : activeChains.values()) {
                emitBox(box);
            }
            activeChains = nextChains;
            nextChains = new HashMap<>();
        }

        /** 扫描结束：关闭最后一行并输出全部剩余盒子。 */
        void finish(int lastZ) {
            if (lastZ >= 0) {
                endRow(lastZ);
            }
            for (int[] box : activeChains.values()) {
                emitBox(box);
            }
            activeChains.clear();
        }

        private void chainStep(int z, int x0, int sx, int y, int sy, int block) {
            BoxChainKey key = new BoxChainKey(x0, sx, y, sy, block);
            int[] box = activeChains.remove(key);
            if (box == null) {
                box = new int[]{x0, y, z, sx, sy, 1, block};
            } else if (box[2] + box[5] == z) {
                box[5]++;
            } else {
                emitBox(box);
                box = new int[]{x0, y, z, sx, sy, 1, block};
            }
            nextChains.put(key, box);
        }

        private void emitBox(int[] box) {
            int worldX = model.placeX() + box[0] - anchor.centerVoxelX();
            int worldY = model.placeY() + box[1] - anchor.minVoxelY();
            int worldZ = model.placeZ() + box[2] - anchor.centerVoxelZ();
            boxes.add(worldX - originX);
            boxes.add(worldY - originY);
            boxes.add(worldZ - originZ);
            boxes.add(box[3]);
            boxes.add(box[4]);
            boxes.add(box[5]);
            boxes.add(box[6]);
        }
    }

    /** Z 合并链键：X 段起点、长度与 (y, sy, block) 完全一致才允许延伸。 */
    private record BoxChainKey(int x0, int sx, int y, int sy, int block) {
    }

    /** 可增长的原始 int 数组：千万级游程下避免装箱与 List 开销。 */
    private static final class IntArray {
        private int[] data = new int[4096];
        private int size;

        void add(int value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        void addAll(int[] values) {
            for (int value : values) {
                add(value);
            }
        }

        int size() {
            return size;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    /** 6 邻域任一方向无占用即为表面；位图越界（包围盒外）视作无占用。 */
    private static boolean isSurface(SurfaceBitmap occupancy, int x, int y, int z) {
        return !occupancy.occupied(x + 1, y, z)
                || !occupancy.occupied(x - 1, y, z)
                || !occupancy.occupied(x, y + 1, z)
                || !occupancy.occupied(x, y - 1, z)
                || !occupancy.occupied(x, y, z + 1)
                || !occupancy.occupied(x, y, z - 1);
    }

    /**
     * 体素占用位图：包围盒稠密 1bit/格。把 6 邻域判断从哈希表随机探测
     * （槽位散布在数 GB 数组里，每次探测基本是一次 cache miss）换成稠密位测试
     * （顺序内存布局、缓存行预取友好），千万级体素模型提速一个数量级。
     */
    private static final class SurfaceBitmap {
        private final long[] bits;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int spanX;
        private final int spanY;
        private final int spanZ;
        private final long spanXY;

        private SurfaceBitmap(long[] bits, int baseX, int baseY, int baseZ,
                              int spanX, int spanY, int spanZ, long spanXY) {
            this.bits = bits;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.spanX = spanX;
            this.spanY = spanY;
            this.spanZ = spanZ;
            this.spanXY = spanXY;
        }

        /** 由已收集的 sortKey 数组构建；包围盒取自扫描阶段（layout 自带），无需再遍历一遍求 min/max。 */
        static SurfaceBitmap build(long[] keys, SortKeyLayout layout) {
            if (keys.length == 0) {
                return new SurfaceBitmap(new long[0], 0, 0, 0, 0, 0, 0, 0L);
            }
            int minX = layout.minX;
            int minY = layout.minY;
            int minZ = layout.minZ;
            int spanX = layout.maxX - minX + 1;
            int spanY = layout.maxY - minY + 1;
            int spanZ = layout.maxZ - minZ + 1;
            long spanXY = (long) spanX * spanY;
            long[] bits = new long[(int) (((long) spanZ * spanXY + 63) >>> 6)];
            for (long key : keys) {
                int z = layout.decodeZ(key);
                int x = layout.decodeX(key);
                int y = layout.decodeY(key);
                long idx = (long) (z - minZ) * spanXY
                        + (long) (y - minY) * spanX
                        + (x - minX);
                bits[(int) (idx >>> 6)] |= 1L << (idx & 63);
            }
            return new SurfaceBitmap(bits, minX, minY, minZ, spanX, spanY, spanZ, spanXY);
        }

        boolean occupied(int x, int y, int z) {
            int lx = x - baseX;
            int ly = y - baseY;
            int lz = z - baseZ;
            if (lx < 0 || lx >= spanX || ly < 0 || ly >= spanY || lz < 0 || lz >= spanZ) {
                return false;
            }
            long idx = (long) lz * spanXY + (long) ly * spanX + lx;
            return (bits[(int) (idx >>> 6)] & (1L << (idx & 63))) != 0;
        }
    }

    private record TerrainBlock(int worldX, int y, int worldZ, int color) {
    }

    private record TopBlock(int y, String blockState) {
    }

    private record PreviewTerrain(List<TerrainBlock> blocks, long expectedColumns, long loadedColumns) {
    }

    private static final class PreviewWorldReader {
        /** chunk 快照缓存总上限（分片后按片均分）：大跨度预览会触及数万个 chunk，缓存无界时 palette 字符串会吃满堆导致 OOM。 */
        private static final int MAX_CHUNK_CACHE = 4096;
        private static final int CACHE_SHARDS = 16;
        /** 轻量 region 读取器缓存上限：每个只持有文件句柄 + 8KB 位置表，内存很小，多放可减少文件打开次数。 */
        private static final int MAX_REGION_READERS = 64;
        /** 地形采样线程数上限。 */
        private static final int MAX_TERRAIN_THREADS = 16;

        private final Path regionDirectory;
        private final List<LinkedHashMap<WorldChunkCoord, ChunkSnapshot>> chunkShards;
        private final Object[] shardLocks;
        private final Map<RegionCoord, ChunkNbtReader> readerCache = new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<RegionCoord, ChunkNbtReader> eldest) {
                if (size() <= MAX_REGION_READERS) {
                    return false;
                }
                ChunkNbtReader evicted = eldest.getValue();
                if (evicted != null) {
                    try {
                        evicted.close();
                    } catch (IOException ignored) {
                    }
                }
                return true;
            }
        };
        private final Object readerLock = new Object();

        PreviewWorldReader(Path regionDirectory) {
            this.regionDirectory = regionDirectory;
            this.chunkShards = new ArrayList<>(CACHE_SHARDS);
            this.shardLocks = new Object[CACHE_SHARDS];
            int perShard = Math.max(64, MAX_CHUNK_CACHE / CACHE_SHARDS);
            for (int i = 0; i < CACHE_SHARDS; i++) {
                final int capacity = perShard;
                chunkShards.add(new LinkedHashMap<>(capacity, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<WorldChunkCoord, ChunkSnapshot> eldest) {
                        return size() > capacity;
                    }
                });
                shardLocks[i] = new Object();
            }
        }

        PreviewTerrain collectSurfaceBlocks(int minX,
                                            int maxX,
                                            int minZ,
                                            int maxZ) throws IOException {
            return collectSurfaceBlocks(minX, maxX, minZ, maxZ, 1, null);
        }

        PreviewTerrain collectSurfaceBlocks(int minX,
                                            int maxX,
                                            int minZ,
                                            int maxZ,
                                            int step) throws IOException {
            return collectSurfaceBlocks(minX, maxX, minZ, maxZ, step, null);
        }

        PreviewTerrain collectSurfaceBlocks(int minX,
                                            int maxX,
                                            int minZ,
                                            int maxZ,
                                            int step,
                                            TaskProgress progress) throws IOException {
            int stride = Math.max(1, step);
            // 先枚举全部采样列，再并行找每列的最高方块（每列只读所在 chunk 的 sections，天然可并行）
            List<int[]> columns = new ArrayList<>();
            for (int worldZ = minZ; worldZ <= maxZ; worldZ += stride) {
                for (int worldX = minX; worldX <= maxX; worldX += stride) {
                    columns.add(new int[]{worldX, worldZ});
                }
            }
            int total = columns.size();
            if (progress != null) {
                progress.phase = "terrain";
                progress.subTotal = total;
                progress.subDone = 0;
            }

            ConcurrentHashMap<ColumnCoord, TopBlock> topBlocks = new ConcurrentHashMap<>();
            AtomicInteger minTopY = new AtomicInteger(Integer.MAX_VALUE);
            AtomicLong processed = new AtomicLong();
            int threads = Math.min(MAX_TERRAIN_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long reportInterval = Math.max(1, total / 200);
            try {
                List<Future<?>> futures = new ArrayList<>();
                int batch = Math.max(1, (total + threads * 8 - 1) / (threads * 8));
                for (int start = 0; start < total; start += batch) {
                    final int batchStart = start;
                    final int batchEnd = Math.min(total, start + batch);
                    futures.add(pool.submit(() -> {
                        for (int i = batchStart; i < batchEnd; i++) {
                            int[] column = columns.get(i);
                            TopBlock topBlock = findTopBlock(column[0], column[1]);
                            if (topBlock != null) {
                                topBlocks.put(new ColumnCoord(column[0], column[1]), topBlock);
                                minTopY.accumulateAndGet(topBlock.y(), Math::min);
                            }
                            long done = processed.incrementAndGet();
                            if (progress != null && done % reportInterval == 0) {
                                progress.subDone = Math.min(done, total);
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("地形采样被中断", e);
            } catch (ExecutionException e) {
                throw new IOException("地形采样失败", e.getCause());
            } finally {
                pool.shutdownNow();
            }

            int originY = minTopY.get() == Integer.MAX_VALUE ? 0 : minTopY.get() - 4;

            // 悬崖面填充串行执行（依赖相邻列结果，且所需 chunk 已大多在缓存中，开销小）
            Map<String, TerrainBlock> surfaceBlocks = new LinkedHashMap<>();
            for (Map.Entry<ColumnCoord, TopBlock> entry : topBlocks.entrySet()) {
                ColumnCoord column = entry.getKey();
                TopBlock topBlock = entry.getValue();
                addSurfaceBlock(surfaceBlocks, column.worldX(), topBlock.y(), column.worldZ(), topBlock.blockState());

                // 悬崖面按采样步进取邻居：step=1 时即真实相邻列，step>1 时连接相邻采样列（渲染为 step 宽大块，连续无缝隙）
                appendCliffFace(surfaceBlocks, topBlocks, column.worldX(), column.worldZ(), topBlock.y(), -stride, 0, originY);
                appendCliffFace(surfaceBlocks, topBlocks, column.worldX(), column.worldZ(), topBlock.y(), stride, 0, originY);
                appendCliffFace(surfaceBlocks, topBlocks, column.worldX(), column.worldZ(), topBlock.y(), 0, -stride, originY);
                appendCliffFace(surfaceBlocks, topBlocks, column.worldX(), column.worldZ(), topBlock.y(), 0, stride, originY);
            }
            if (progress != null) {
                progress.subDone = total;
            }
            return new PreviewTerrain(new ArrayList<>(surfaceBlocks.values()), total, topBlocks.size());
        }

        TopBlock findTopBlock(int worldX, int worldZ) throws IOException {
            return findTopBlockInternal(worldX, worldZ);
        }

        private TopBlock findTopBlockInternal(int worldX, int worldZ) throws IOException {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            ChunkSnapshot chunk = loadChunk(chunkX, chunkZ);
            if (chunk == null || chunk.isEmpty()) {
                return null;
            }
            return chunk.findTopBlock(Math.floorMod(worldX, 16), Math.floorMod(worldZ, 16));
        }

        private void appendCliffFace(Map<String, TerrainBlock> surfaceBlocks,
                                     Map<ColumnCoord, TopBlock> topBlocks,
                                     int worldX,
                                     int worldZ,
                                     int topY,
                                     int offsetX,
                                     int offsetZ,
                                     int originY) throws IOException {
            TopBlock neighbor = topBlocks.get(new ColumnCoord(worldX + offsetX, worldZ + offsetZ));
            int targetY = neighbor != null ? neighbor.y() : originY;

            if (targetY >= topY - 1) {
                return;
            }
            // 限制悬崖填充深度：只补顶部一小段，足够表现局部地形起伏。
            // 若不限制，存档里已有的 2km 高建筑会把整面墙当成地形输出，几万列就能产生千万级方块导致 OOM/卡死
            int minFillY = Math.max(targetY, topY - MAX_CLIFF_DEPTH);
            for (int worldY = topY - 1; worldY >= minFillY; worldY--) {
                String blockState = blockStateAt(worldX, worldY, worldZ);
                if (!isRenderableTerrainBlock(blockState)) {
                    continue;
                }
                addSurfaceBlock(surfaceBlocks, worldX, worldY, worldZ, blockState);
            }
        }

        private void addSurfaceBlock(Map<String, TerrainBlock> surfaceBlocks,
                                     int worldX,
                                     int worldY,
                                     int worldZ,
                                     String blockState) {
            String key = worldX + "," + worldY + "," + worldZ;
            surfaceBlocks.putIfAbsent(key, new TerrainBlock(
                    worldX,
                    worldY,
                    worldZ,
                    BlockPalette.DEFAULT.previewColor(blockState)));
        }

        private String blockStateAt(int worldX, int worldY, int worldZ) throws IOException {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            ChunkSnapshot chunk = loadChunk(chunkX, chunkZ);
            if (chunk == null || chunk.isEmpty()) {
                return "minecraft:air";
            }
            return chunk.blockStateAt(Math.floorMod(worldX, 16), worldY, Math.floorMod(worldZ, 16));
        }

        private ChunkSnapshot loadChunk(int chunkX, int chunkZ) throws IOException {
            WorldChunkCoord chunkCoord = new WorldChunkCoord(chunkX, chunkZ);
            int shardIndex = Math.floorMod(chunkX * 31 + chunkZ * 17, CACHE_SHARDS);
            LinkedHashMap<WorldChunkCoord, ChunkSnapshot> shard = chunkShards.get(shardIndex);
            Object lock = shardLocks[shardIndex];
            synchronized (lock) {
                ChunkSnapshot cached = shard.get(chunkCoord);
                if (cached != null) {
                    return cached;
                }
            }

            ChunkNbtReader reader = loadReader(chunkCoord.regionX(), chunkCoord.regionZ());
            ChunkSnapshot snapshot = ChunkSnapshot.EMPTY;
            if (reader != null) {
                ICompoundTag root = reader.readChunkRoot(chunkX, chunkZ);
                if (root != null) {
                    TreeMap<Integer, SectionSnapshot> sections = new TreeMap<>();
                    for (Map.Entry<Integer, ICompoundTag> entry : WorldWriter.sectionsByY(root).entrySet()) {
                        sections.put(entry.getKey(), SectionSnapshot.from(entry.getValue()));
                    }
                    snapshot = new ChunkSnapshot(sections);
                }
            }
            synchronized (lock) {
                shard.put(chunkCoord, snapshot);
            }
            return snapshot;
        }

        private ChunkNbtReader loadReader(int regionX, int regionZ) throws IOException {
            RegionCoord regionCoord = new RegionCoord(regionX, regionZ);
            synchronized (readerLock) {
                ChunkNbtReader cached = readerCache.get(regionCoord);
                if (cached != null) {
                    return cached;
                }
                if (readerCache.containsKey(regionCoord)) {
                    return null; // 之前已确认该 region 文件不存在
                }
            }

            Path regionFile = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
            ChunkNbtReader reader = null;
            if (WorldWriter.isReadableRegionFile(regionFile)) {
                reader = new ChunkNbtReader(regionFile, regionX, regionZ);
            }
            synchronized (readerLock) {
                ChunkNbtReader existing = readerCache.get(regionCoord);
                if (existing != null) {
                    if (reader != null) {
                        reader.close();
                    }
                    return existing;
                }
                readerCache.put(regionCoord, reader);
                return reader;
            }
        }

        /** 关闭预览期间打开的全部 region 文件句柄；调用后不得再采样。 */
        void close() {
            synchronized (readerLock) {
                for (ChunkNbtReader reader : readerCache.values()) {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                readerCache.clear();
            }
        }
    }

    private static final class ChunkSnapshot {
        static final ChunkSnapshot EMPTY = new ChunkSnapshot(new TreeMap<>());

        private final TreeMap<Integer, SectionSnapshot> sections;

        ChunkSnapshot(TreeMap<Integer, SectionSnapshot> sections) {
            this.sections = sections;
        }

        boolean isEmpty() {
            return sections.isEmpty();
        }

        TopBlock findTopBlock(int localX, int localZ) {
            TopBlock fluidSurface = null;
            for (Map.Entry<Integer, SectionSnapshot> entry : sections.descendingMap().entrySet()) {
                int sectionY = entry.getKey();
                SectionSnapshot section = entry.getValue();
                for (int localY = 15; localY >= 0; localY--) {
                    String blockState = section.blockStateAt(localX, localY, localZ);
                    if (isAirBlock(blockState) || isIgnoredSurfaceDecoration(blockState)) {
                        continue;
                    }

                    TopBlock candidate = new TopBlock(sectionY * 16 + localY, blockState);
                    if (isFluidBlock(blockState)) {
                        if (fluidSurface == null) {
                            fluidSurface = candidate;
                        }
                        continue;
                    }
                    return fluidSurface != null ? fluidSurface : candidate;
                }
            }
            return fluidSurface;
        }

        String blockStateAt(int localX, int worldY, int localZ) {
            int sectionY = Math.floorDiv(worldY, 16);
            SectionSnapshot section = sections.get(sectionY);
            if (section == null) {
                return "minecraft:air";
            }
            return section.blockStateAt(localX, Math.floorMod(worldY, 16), localZ);
        }

    }

    private static final class SectionSnapshot {
        /** 方块名驻留池：预览单线程使用，ConcurrentHashMap 保证并发安全。 */
        private static final java.util.concurrent.ConcurrentHashMap<String, String> PALETTE_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

        private final String[] palette;
        private final long[] data;
        private final int bitsPerBlock;

        private SectionSnapshot(String[] palette, long[] data) {
            this.palette = palette;
            this.data = data;
            this.bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
        }

        static SectionSnapshot from(ICompoundTag section) {
            if (section == null || !section.hasTag("block_states")) {
                return new SectionSnapshot(new String[]{"minecraft:air"}, new long[0]);
            }
            ICompoundTag blockStates = section.getCompound("block_states");
            if (!blockStates.hasTag("palette")) {
                return new SectionSnapshot(new String[]{"minecraft:air"}, new long[0]);
            }

            IListTag paletteList = blockStates.getList("palette");
            if (paletteList.size() == 0) {
                return new SectionSnapshot(new String[]{"minecraft:air"}, new long[0]);
            }

            String[] palette = new String[paletteList.size()];
            for (int i = 0; i < paletteList.size(); i++) {
                ICompoundTag paletteEntry = (ICompoundTag) paletteList.get(i);
                // 方块名驻留：整个存档的方块种类有限，跨 chunk 去重可避免 palette 字符串吃满堆（大跨度预览会加载数万 chunk）
                palette[i] = PALETTE_NAMES.computeIfAbsent(paletteEntry.getString("Name"), name -> name);
            }

            long[] data = blockStates.hasTag("data") ? blockStates.getLongArray("data") : new long[0];
            return new SectionSnapshot(palette, data);
        }

        String blockStateAt(int localX, int localY, int localZ) {
            if (palette.length == 1 && data.length == 0) {
                return palette[0];
            }

            int index = localY * 256 + localZ * 16 + localX;
            int paletteIndex = WorldWriter.paletteIndexAt(data, bitsPerBlock, index);
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                return "minecraft:air";
            }
            return palette[paletteIndex];
        }
    }

    private static boolean isAirBlock(String blockState) {
        return blockState == null
                || blockState.isBlank()
                || blockState.endsWith("air")
                || blockState.contains("cave_air")
                || blockState.contains("void_air");
    }

    private static boolean isFluidBlock(String blockState) {
        String name = ParsedBlockState.parse(blockState).name();
        return name.contains("water") || name.contains("lava");
    }

    private static boolean isRenderableTerrainBlock(String blockState) {
        return !isAirBlock(blockState) && !isIgnoredSurfaceDecoration(blockState);
    }

    private static boolean isIgnoredSurfaceDecoration(String blockState) {
        String name = ParsedBlockState.parse(blockState).name();
        return isExactName(name,
                "minecraft:grass",
                "minecraft:tall_grass",
                "minecraft:short_grass",
                "minecraft:fern",
                "minecraft:large_fern",
                "minecraft:dead_bush",
                "minecraft:dandelion",
                "minecraft:poppy",
                "minecraft:blue_orchid",
                "minecraft:allium",
                "minecraft:azure_bluet",
                "minecraft:red_tulip",
                "minecraft:orange_tulip",
                "minecraft:white_tulip",
                "minecraft:pink_tulip",
                "minecraft:oxeye_daisy",
                "minecraft:cornflower",
                "minecraft:lily_of_the_valley",
                "minecraft:wither_rose",
                "minecraft:sunflower",
                "minecraft:lilac",
                "minecraft:rose_bush",
                "minecraft:peony",
                "minecraft:torchflower",
                "minecraft:pitcher_crop",
                "minecraft:sugar_cane",
                "minecraft:bamboo",
                "minecraft:cactus",
                "minecraft:kelp",
                "minecraft:kelp_plant",
                "minecraft:seagrass",
                "minecraft:tall_seagrass",
                "minecraft:sea_pickle",
                "minecraft:small_dripleaf",
                "minecraft:big_dripleaf",
                "minecraft:moss_carpet",
                "minecraft:pink_petals",
                "minecraft:wildflowers",
                "minecraft:sweet_berry_bush",
                "minecraft:glow_berries",
                "minecraft:weeping_vines",
                "minecraft:twisting_vines",
                "minecraft:vine",
                "minecraft:rail",
                "minecraft:powered_rail",
                "minecraft:detector_rail",
                "minecraft:activator_rail",
                "minecraft:torch",
                "minecraft:wall_torch",
                "minecraft:redstone_torch",
                "minecraft:redstone_wall_torch")
                || endsWithAny(name,
                "_crop",
                "_carpet",
                "_leaves",
                "_sapling",
                "_coral",
                "_coral_fan",
                "_wall_coral_fan",
                "_button",
                "_pressure_plate",
                "_sign",
                "_wall_sign",
                "_hanging_sign",
                "_wall_hanging_sign",
                "_log",
                "_stem",
                "_hyphae",
                "_fence",
                "_wall",
                "_glass",
                "_door",
                "_trapdoor",
                "_lantern",
                "_chain",
                "_ladder",
                "_scaffolding",
                "_candle",
                "_flower_pot",
                "_banner",
                "_skull")
                || name.endsWith("mushroom_block");
    }

    private record ColumnCoord(int worldX, int worldZ) {
    }

    private static boolean isExactName(String name, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String name, String... suffixes) {
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
