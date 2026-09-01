package org.example;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.DeflaterOutputStream;

import de.pauleff.jmcx.api.IChunk;
import de.pauleff.jmcx.api.IRegion;
import de.pauleff.jmcx.formats.anvil.AnvilReader;
import de.pauleff.jnbt.api.ICompoundTag;
import de.pauleff.jnbt.api.IListTag;
import de.pauleff.jnbt.api.ITag;
import de.pauleff.jnbt.api.NBTFileFactory;
import de.pauleff.jnbt.core.Tag;
import de.pauleff.jnbt.core.Tag_Byte;
import de.pauleff.jnbt.core.Tag_Byte_Array;
import de.pauleff.jnbt.core.Tag_Compound;
import de.pauleff.jnbt.core.Tag_Double;
import de.pauleff.jnbt.core.Tag_Float;
import de.pauleff.jnbt.core.Tag_Int;
import de.pauleff.jnbt.core.Tag_Int_Array;
import de.pauleff.jnbt.core.Tag_List;
import de.pauleff.jnbt.core.Tag_Long;
import de.pauleff.jnbt.core.Tag_Long_Array;
import de.pauleff.jnbt.core.Tag_Short;
import de.pauleff.jnbt.core.Tag_String;

record RegionCoord(int regionX, int regionZ) {
}

record WorldChunkCoord(int chunkX, int chunkZ) {
    int regionX() {
        return Math.floorDiv(chunkX, 32);
    }

    int regionZ() {
        return Math.floorDiv(chunkZ, 32);
    }
}

record WorldWriteStats(int regionCount, int chunkCount, int sectionCount) {
}

record ChunkWriteData(int chunkX, int chunkZ, int timestamp, ICompoundTag root) {
    int index() {
        return Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
    }
}

record PlacementAnchor(int minVoxelX,
                       int maxVoxelX,
                       int minVoxelY,
                       int maxVoxelY,
                       int minVoxelZ,
                       int maxVoxelZ,
                       int centerVoxelX,
                       int centerVoxelZ) {
    static PlacementAnchor fromVoxelGrid(SparseVoxelGrid voxelGrid) {
        if (voxelGrid.isEmpty()) {
            throw new IllegalArgumentException("没有可写入的体素，无法计算放置位置");
        }

        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        voxelGrid.forEach((x, y, z, rgb) -> {
            min[0] = Math.min(min[0], x);
            min[1] = Math.min(min[1], y);
            min[2] = Math.min(min[2], z);
            max[0] = Math.max(max[0], x);
            max[1] = Math.max(max[1], y);
            max[2] = Math.max(max[2], z);
        });

        return new PlacementAnchor(
                min[0],
                max[0],
                min[1],
                max[1],
                min[2],
                max[2],
                Math.floorDiv(min[0] + max[0], 2),
                Math.floorDiv(min[2] + max[2], 2));
    }

    int worldMinX(int placeX) {
        return placeX + minVoxelX - centerVoxelX;
    }

    int worldMaxX(int placeX) {
        return placeX + maxVoxelX - centerVoxelX;
    }

    int worldMinY(int placeY) {
        return placeY;
    }

    int worldMaxY(int placeY) {
        return placeY + (maxVoxelY - minVoxelY);
    }

    int worldMinZ(int placeZ) {
        return placeZ + minVoxelZ - centerVoxelZ;
    }

    int worldMaxZ(int placeZ) {
        return placeZ + maxVoxelZ - centerVoxelZ;
    }
}

final class SectionBuilder {
    final java.util.LinkedHashMap<String, Integer> palette = new java.util.LinkedHashMap<>();
    final int[] blockStates = new int[4096];

    SectionBuilder() {
        palette.put("minecraft:air", 0);
    }

    void set(int localX, int localY, int localZ, String blockState) {
        int paletteIndex = palette.computeIfAbsent(blockState, ignored -> palette.size());
        int flatIndex = localY * 256 + localZ * 16 + localX;
        blockStates[flatIndex] = paletteIndex;
    }

    ICompoundTag build(int sectionY, ICompoundTag biomeSource) {
        Tag_Compound section = new Tag_Compound("");
        section.setByte("Y", (byte) sectionY);
        section.setTag(buildBlockStatesTag());
        section.setTag(WorldWriter.copyBiomeTag(biomeSource));
        return section;
    }

    ICompoundTag buildBlockStatesTag() {
        // 挖空行为：重建 section 时只填模型块，其余位置为空气
        String[] merged = new String[4096];
        java.util.Arrays.fill(merged, "minecraft:air");

        String[] modelStates = palette.keySet().toArray(new String[0]);
        for (int i = 0; i < 4096; i++) {
            int modelIndex = blockStates[i];
            if (modelIndex != 0) {
                merged[i] = modelStates[modelIndex];
            }
        }

        // 3) 重建合并后的 palette + data
        java.util.LinkedHashMap<String, Integer> newPalette = new java.util.LinkedHashMap<>();
        newPalette.put("minecraft:air", 0);
        int[] packed = new int[4096];
        for (int i = 0; i < 4096; i++) {
            String state = merged[i];
            if (state == null || state.isBlank()) {
                state = "minecraft:air";
            }
            int paletteIndex = newPalette.computeIfAbsent(state, ignored -> newPalette.size());
            packed[i] = paletteIndex;
        }

        Tag_Compound blockStatesTag = new Tag_Compound("block_states");
        Tag_List paletteTag = new Tag_List("palette", 10);
        for (String blockState : newPalette.keySet()) {
            paletteTag.getData().add((Tag<?>) WorldWriter.createPaletteEntry(blockState));
        }
        blockStatesTag.setTag(paletteTag);
        if (newPalette.size() > 1) {
            blockStatesTag.setLongArray("data", WorldWriter.packBlockStates(packed, newPalette.size()));
        }
        return blockStatesTag;
    }
}

final class WorldWriter {
    private static final long REGION_SECTOR_BYTES = 4096L;
    private static final long MIN_REGION_BYTES = REGION_SECTOR_BYTES * 2L;
    // 完全没有源区块可继承 DataVersion 时的安全兜底（1.21.4 对应版本）。
    // 宁可写一个较新的版本让游戏走正向 DataFixer 升级，也不能写 0（会触发古老格式升级而失败）。
    private static final int FALLBACK_DATA_VERSION = 4903;
    /** 并行写 region 的线程数上限：region 间独立可并行，但每个 region 的 chunk NBT 会占内存，需与堆大小权衡。 */
    private static final int MAX_WRITE_REGION_THREADS = 4;
    private static final DateTimeFormatter INVALID_REGION_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final HttpClient DEBUG_HTTP = HttpClient.newHttpClient();

    private WorldWriter() {
    }

    // #region debug-point shared:region-tmp-access
    static void reportDebug(String hypothesisId, String location, String msg, Map<String, String> data) {
        try {
            Path envPath = Path.of(".dbg", "region-tmp-access.env");
            String url = "http://127.0.0.1:7777/event";
            String sessionId = "region-tmp-access";
            if (Files.exists(envPath)) {
                for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    if (line.startsWith("DEBUG_SERVER_URL=")) {
                        url = line.substring("DEBUG_SERVER_URL=".length()).trim();
                    } else if (line.startsWith("DEBUG_SESSION_ID=")) {
                        sessionId = line.substring("DEBUG_SESSION_ID=".length()).trim();
                    }
                }
            }
            StringBuilder payload = new StringBuilder();
            payload.append("{")
                    .append("\"sessionId\":\"").append(debugEscape(sessionId)).append("\",")
                    .append("\"runId\":\"pre-fix\",")
                    .append("\"hypothesisId\":\"").append(debugEscape(hypothesisId)).append("\",")
                    .append("\"location\":\"").append(debugEscape(location)).append("\",")
                    .append("\"msg\":\"").append(debugEscape(msg)).append("\",")
                    .append("\"data\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (!first) {
                    payload.append(",");
                }
                first = false;
                payload.append("\"").append(debugEscape(entry.getKey())).append("\":")
                        .append("\"").append(debugEscape(String.valueOf(entry.getValue()))).append("\"");
            }
            payload.append("}}");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
            DEBUG_HTTP.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    static String debugEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
    // #endregion

    static WorldWriteStats write(Config config, SparseVoxelGrid worldGrid) throws IOException {
        return write(config, worldGrid, null);
    }

    static WorldWriteStats write(Config config, SparseVoxelGrid worldGrid, TaskProgress progress) throws IOException {
        Files.createDirectories(config.regionDirectory());

        ICompoundTag templateRoot = findTemplateRoot(config.regionDirectory());
        // 自动继承写入用的 DataVersion：优先 level.dat，再退回模板区块，最后使用安全兜底。
        int effectiveDataVersion;
        Integer fromLevel = readLevelDataVersion(config.worldPath);
        if (fromLevel != null) {
            effectiveDataVersion = fromLevel;
        } else if (templateRoot != null && templateRoot.hasTag("DataVersion")) {
            effectiveDataVersion = templateRoot.getInt("DataVersion");
        } else {
            effectiveDataVersion = FALLBACK_DATA_VERSION;
        }

        List<RegionCoord> sortedRegions = collectPresentRegions(worldGrid);
        // 表面光源若落在相邻 region，先把跨 region 的光源体素预收集，写目标 region 时补上
        Map<RegionCoord, java.util.Set<Long>> pendingLight = config.addSurfaceLight
                ? collectCrossRegionLight(worldGrid)
                : Map.of();

        if (progress != null) {
            progress.phase = "write";
            progress.done = 0;
            progress.total = sortedRegions.size();
            progress.subDone = 0;
            progress.subTotal = 0;
        }

        // region 间相互独立（各自读旧 region、合并、序列化、写自己的文件），并行处理能同时打满 CPU 与磁盘 IO。
        // 每个 region 的工作集（SectionBuilder + chunk NBT）随处理完即释放，并行内存峰值仍可控。
        final int writeDataVersion = effectiveDataVersion;
        int threads = Math.min(MAX_WRITE_REGION_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sectionCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger processedRegions = new java.util.concurrent.atomic.AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(sortedRegions.size());
            for (RegionCoord regionCoord : sortedRegions) {
                java.util.Set<Long> regionLight = pendingLight.getOrDefault(regionCoord, java.util.Set.of());
                futures.add(pool.submit(() -> {
                    int[] counts = writeOneRegion(
                            config,
                            worldGrid,
                            regionCoord,
                            regionLight,
                            writeDataVersion,
                            templateRoot);
                    chunkCount.addAndGet(counts[0]);
                    sectionCount.addAndGet(counts[1]);
                    int done = processedRegions.incrementAndGet();
                    if (progress != null) {
                        progress.done = done;
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("写入被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("写入失败", cause);
        } finally {
            pool.shutdownNow();
        }

        return new WorldWriteStats(sortedRegions.size(), chunkCount.get(), sectionCount.get());
    }

    /** 写入单个 region：收集该 region 的体素、合并进现有 chunk、序列化并落盘。返回 {chunk数, section数}。 */
    private static int[] writeOneRegion(Config config,
                                        SparseVoxelGrid worldGrid,
                                        RegionCoord regionCoord,
                                        java.util.Set<Long> regionLight,
                                        int effectiveDataVersion,
                                        ICompoundTag templateRoot) throws IOException {
        // 只收集当前 region 的体素，写完即释放，避免 2km 大模型把所有 SectionBuilder 一次性堆在内存里 OOM
        Map<WorldChunkCoord, Map<Integer, SectionBuilder>> regionChunks = collectRegionPlacements(
                config,
                worldGrid,
                regionCoord,
                regionLight);

        Path regionFile = config.regionDirectory().resolve(
                "r." + regionCoord.regionX() + "." + regionCoord.regionZ() + ".mca");
        IRegion existingRegion = null;
        if (isReadableRegionFile(regionFile)) {
            existingRegion = readRegion(regionFile.toFile());
            if (!isRegionCoordinateConsistent(existingRegion, regionCoord)) {
                quarantineMisplacedRegionFile(regionFile);
                existingRegion = null;
            }
        }
        Map<Integer, ChunkWriteData> chunksToWrite = new HashMap<>();
        if (existingRegion != null) {
            for (IChunk existingChunk : existingRegion.getChunks()) {
                if (existingChunk == null || existingChunk.isEmpty()) {
                    continue;
                }
                ICompoundTag existingRoot = existingChunk.getNBTData();
                if (existingRoot == null) {
                    continue;
                }
                ICompoundTag copiedRoot = deepCopyCompound(existingRoot);
                ChunkWriteData writeData = new ChunkWriteData(
                        existingChunk.getX(),
                        existingChunk.getZ(),
                        existingChunk.getTimestamp(),
                        copiedRoot);
                chunksToWrite.put(writeData.index(), writeData);
            }
        }

        List<WorldChunkCoord> sortedChunks = new ArrayList<>(regionChunks.keySet());
        sortedChunks.sort(Comparator
                .comparingInt(WorldChunkCoord::chunkX)
                .thenComparingInt(WorldChunkCoord::chunkZ));

        int chunkCount = 0;
        int sectionCount = 0;
        for (WorldChunkCoord chunkCoord : sortedChunks) {
            Map<Integer, SectionBuilder> sections = regionChunks.get(chunkCoord);
            boolean chunkExists = existingRegion != null && existingRegion.containsChunk(chunkCoord.chunkX(), chunkCoord.chunkZ());
            ICompoundTag sourceRoot = chunkExists
                    ? existingRegion.getChunk(chunkCoord.chunkX(), chunkCoord.chunkZ()).orElseThrow().getNBTData()
                    : templateRoot;
            ICompoundTag chunkRoot = buildChunkRoot(
                    sourceRoot,
                    templateRoot,
                    sections,
                    chunkExists,
                    chunkCoord.chunkX(),
                    chunkCoord.chunkZ(),
                    effectiveDataVersion);

            ChunkWriteData writeData = new ChunkWriteData(
                    chunkCoord.chunkX(),
                    chunkCoord.chunkZ(),
                    currentUnixTimestamp(),
                    chunkRoot);
            chunksToWrite.put(writeData.index(), writeData);
            chunkCount++;
            sectionCount += sections.size();
        }

        writeRegionFile(regionFile, chunksToWrite, config.createRegionBackups);
        return new int[]{chunkCount, sectionCount};
    }

    /** 扫描一次世界网格，返回有体素的 region 坐标（有序）。 */
    static List<RegionCoord> collectPresentRegions(SparseVoxelGrid worldGrid) {
        Map<RegionCoord, Boolean> present = new HashMap<>();
        worldGrid.forEachWorld((worldX, worldY, worldZ, rgb) ->
                present.putIfAbsent(regionOf(worldX, worldZ), Boolean.TRUE));
        List<RegionCoord> sorted = new ArrayList<>(present.keySet());
        sorted.sort(Comparator
                .comparingInt(RegionCoord::regionX)
                .thenComparingInt(RegionCoord::regionZ));
        return sorted;
    }

    /** 收集落在相邻 region 的跨 region 光源体素（按目标 region 分组），供写入对应 region 时补上。 */
    static Map<RegionCoord, java.util.Set<Long>> collectCrossRegionLight(SparseVoxelGrid worldGrid) {
        Map<RegionCoord, java.util.Set<Long>> pending = new HashMap<>();
        worldGrid.forEachWorld((worldX, worldY, worldZ, rgb) -> {
            RegionCoord sourceRegion = regionOf(worldX, worldZ);
            for (int[] offset : SURFACE_LIGHT_OFFSETS) {
                int neighborX = worldX + offset[0];
                int neighborY = worldY + offset[1];
                int neighborZ = worldZ + offset[2];
                if (worldGrid.containsPacked(SparseVoxelGrid.packWorld(neighborX, neighborY, neighborZ))) {
                    continue;
                }
                RegionCoord targetRegion = regionOf(neighborX, neighborZ);
                if (!targetRegion.equals(sourceRegion)) {
                    pending.computeIfAbsent(targetRegion, ignored -> new java.util.HashSet<>())
                            .add(SparseVoxelGrid.packWorld(neighborX, neighborY, neighborZ));
                }
            }
        });
        return pending;
    }

    static RegionCoord regionOf(int worldX, int worldZ) {
        return new RegionCoord(Math.floorDiv(Math.floorDiv(worldX, 16), 32), Math.floorDiv(Math.floorDiv(worldZ, 16), 32));
    }

    /** 收集指定 region 内的体素放置与表面光源（含预收集的跨 region 光源）。 */
    static Map<WorldChunkCoord, Map<Integer, SectionBuilder>> collectRegionPlacements(
            Config config,
            SparseVoxelGrid worldGrid,
            RegionCoord regionCoord,
            java.util.Set<Long> pendingLight) {
        Map<WorldChunkCoord, Map<Integer, SectionBuilder>> regionChunks = new HashMap<>();
        boolean addSurfaceLight = config.addSurfaceLight;
        worldGrid.forEachWorld((worldX, worldY, worldZ, rgb) -> {
            if (!regionOf(worldX, worldZ).equals(regionCoord)) {
                return;
            }
            String blockState = BlockPalette.DEFAULT.closestBlock(rgb);
            addSectionBlock(regionChunks, worldX, worldY, worldZ, blockState);

            // 表面光源：在模型体素外侧（6 方向邻居不是模型体素的位置）铺一层 light[level=15]
            if (addSurfaceLight) {
                for (int[] offset : SURFACE_LIGHT_OFFSETS) {
                    int neighborX = worldX + offset[0];
                    int neighborY = worldY + offset[1];
                    int neighborZ = worldZ + offset[2];
                    if (worldGrid.containsPacked(SparseVoxelGrid.packWorld(neighborX, neighborY, neighborZ))) {
                        continue;
                    }
                    // 跨 region 的光源已预收集到 pendingLight，这里只处理本 region 内的
                    if (regionOf(neighborX, neighborZ).equals(regionCoord)) {
                        addSectionBlock(regionChunks, neighborX, neighborY, neighborZ, SURFACE_LIGHT_BLOCK_STATE);
                    }
                }
            }
        });
        if (addSurfaceLight) {
            for (long lightKey : pendingLight) {
                int[] position = SparseVoxelGrid.unpackWorld(lightKey);
                addSectionBlock(regionChunks, position[0], position[1], position[2], SURFACE_LIGHT_BLOCK_STATE);
            }
        }
        return regionChunks;
    }

    private static void addSectionBlock(Map<WorldChunkCoord, Map<Integer, SectionBuilder>> regionChunks,
                                        int worldX,
                                        int worldY,
                                        int worldZ,
                                        String blockState) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        int sectionY = Math.floorDiv(worldY, 16);
        int localX = Math.floorMod(worldX, 16);
        int localY = Math.floorMod(worldY, 16);
        int localZ = Math.floorMod(worldZ, 16);

        WorldChunkCoord chunkCoord = new WorldChunkCoord(chunkX, chunkZ);
        SectionBuilder sectionBuilder = regionChunks
                .computeIfAbsent(chunkCoord, ignored -> new HashMap<>())
                .computeIfAbsent(sectionY, ignored -> new SectionBuilder());
        sectionBuilder.set(localX, localY, localZ, blockState);
    }

    private static final int[][] SURFACE_LIGHT_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final String SURFACE_LIGHT_BLOCK_STATE = "minecraft:light[level=15]";

    static ICompoundTag findTemplateRoot(Path regionDirectory) throws IOException {
        if (!Files.isDirectory(regionDirectory)) {
            return null;
        }

        try (var stream = Files.list(regionDirectory)) {
            List<Path> regionFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".mca"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path regionFile : regionFiles) {
                if (!isReadableRegionFile(regionFile)) {
                    continue;
                }
                IRegion region = readRegion(regionFile.toFile());
                for (IChunk chunk : region.getChunks()) {
                    ICompoundTag root = chunk.getNBTData();
                    if (root != null) {
                        return deepCopyCompound(root);
                    }
                }
            }
        }
        return null;
    }

    /** 从存档 level.dat 读取世界 DataVersion；不可读时返回 null。 */
    static Integer readLevelDataVersion(Path worldPath) {
        try {
            Path levelDat = worldPath.resolve("level.dat");
            if (!Files.exists(levelDat)) {
                return null;
            }
            ICompoundTag root = NBTFileFactory.readNBTFile(levelDat.toFile());
            if (root == null || !root.hasTag("Data")) {
                return null;
            }
            ICompoundTag data = root.getCompound("Data");
            return data.hasTag("DataVersion") ? data.getInt("DataVersion") : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static IRegion readRegion(File regionFile) throws IOException {
        AnvilReader reader = new AnvilReader(regionFile);
        try {
            return reader.readRegion();
        } finally {
            reader.close();
        }
    }

    static boolean isReadableRegionFile(Path regionFile) throws IOException {
        return Files.exists(regionFile)
                && Files.size(regionFile) >= MIN_REGION_BYTES
                && Files.size(regionFile) % REGION_SECTOR_BYTES == 0;
    }

    static void writeRegionFile(Path regionFile,
                                Map<Integer, ChunkWriteData> chunks,
                                boolean createBackup) throws IOException {
        quarantineInvalidRegionFile(regionFile);
        Path parent = regionFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (createBackup && Files.exists(regionFile)) {
            Path backup = regionFile.resolveSibling(regionFile.getFileName() + ".bak");
            // #region debug-point A:backup-start
            reportDebug("A", "WorldWriter.writeRegionFile:backup-start", "[DEBUG] backup-start", Map.of(
                    "regionFile", regionFile.toString(),
                    "backupFile", backup.toString(),
                    "parent", String.valueOf(parent),
                    "parentExists", String.valueOf(parent != null && Files.exists(parent)),
                    "parentWritable", String.valueOf(parent != null && Files.isWritable(parent)),
                    "regionExists", String.valueOf(Files.exists(regionFile)),
                    "regionWritable", String.valueOf(Files.isWritable(regionFile))));
            // #endregion
            // Windows 上 REPLACE_EXISTING 遇到只读目标会抛 AccessDenied，先清掉旧备份的只读属性
            try {
                backup.toFile().setWritable(true, false);
            } catch (Exception ignored) {
            }
            // 源文件可能被 Minecraft 短暂占用，重试几次；仍失败则跳过备份继续写入
            IOException backupError = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Files.deleteIfExists(backup);
                    Files.copy(regionFile, backup);
                    backupError = null;
                    break;
                } catch (IOException e) {
                    // #region debug-point A:backup-failed
                    reportDebug("A", "WorldWriter.writeRegionFile:backup-failed", "[DEBUG] backup-failed", Map.of(
                            "attempt", String.valueOf(attempt),
                            "exception", e.getClass().getName(),
                            "message", String.valueOf(e.getMessage()),
                            "backupExists", String.valueOf(Files.exists(backup)),
                            "parentWritable", String.valueOf(parent != null && Files.isWritable(parent))));
                    // #endregion
                    backupError = e;
                    try {
                        Thread.sleep(300L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (backupError != null) {
                System.err.println("警告: 备份失败，跳过备份继续写入 (" + backupError.getMessage() + ")");
            }
        }

        byte[] locationTable = new byte[4096];
        byte[] timestampTable = new byte[4096];
        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
        int sectorOffset = 2;

        List<Integer> sortedIndexes = new ArrayList<>(chunks.keySet());
        sortedIndexes.sort(Integer::compareTo);
        for (Integer index : sortedIndexes) {
            ChunkWriteData chunk = chunks.get(index);
            byte[] payload = createChunkPayload(chunk.root());
            int sectorCount = payload.length / (int) REGION_SECTOR_BYTES;
            if (sectorCount <= 0 || sectorCount > 255) {
                throw new IOException("Chunk 扇区数量非法: index=" + index + ", sectors=" + sectorCount);
            }

            int headerOffset = index * 4;
            locationTable[headerOffset] = (byte) ((sectorOffset >>> 16) & 0xFF);
            locationTable[headerOffset + 1] = (byte) ((sectorOffset >>> 8) & 0xFF);
            locationTable[headerOffset + 2] = (byte) (sectorOffset & 0xFF);
            locationTable[headerOffset + 3] = (byte) (sectorCount & 0xFF);

            writeIntToArray(timestampTable, headerOffset, chunk.timestamp());
            payloadStream.write(payload);
            sectorOffset += sectorCount;
        }

        Path tempFile = regionFile.resolveSibling(regionFile.getFileName() + ".tmp");
        // #region debug-point B:temp-create-start
        reportDebug("B", "WorldWriter.writeRegionFile:temp-create-start", "[DEBUG] temp-create-start", Map.of(
                "tempFile", tempFile.toString(),
                "tempParent", String.valueOf(tempFile.getParent()),
                "tempParentExists", String.valueOf(tempFile.getParent() != null && Files.exists(tempFile.getParent())),
                "tempParentWritable", String.valueOf(tempFile.getParent() != null && Files.isWritable(tempFile.getParent())),
                "tempExistsBefore", String.valueOf(Files.exists(tempFile)),
                "regionWritable", String.valueOf(Files.isWritable(regionFile))));
        // #endregion
        try (BufferedOutputStream output = new BufferedOutputStream(
                Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            output.write(locationTable);
            output.write(timestampTable);
            payloadStream.writeTo(output);
        } catch (IOException e) {
            // #region debug-point B:temp-create-failed
            reportDebug("B", "WorldWriter.writeRegionFile:temp-create-failed", "[DEBUG] temp-create-failed", Map.of(
                    "tempFile", tempFile.toString(),
                    "exception", e.getClass().getName(),
                    "message", String.valueOf(e.getMessage()),
                    "tempExistsAfter", String.valueOf(Files.exists(tempFile)),
                    "tempParentWritable", String.valueOf(tempFile.getParent() != null && Files.isWritable(tempFile.getParent()))));
            // #endregion
            throw e;
        }
        // 目标文件被独占打开时替换会失败（Minecraft、资源管理器预览、杀毒/索引服务等），重试几次后给出明确提示
        IOException moveError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(tempFile, regionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                // #region debug-point C:move-failed
                reportDebug("C", "WorldWriter.writeRegionFile:move-failed", "[DEBUG] move-failed", Map.of(
                        "attempt", String.valueOf(attempt),
                        "tempFile", tempFile.toString(),
                        "regionFile", regionFile.toString(),
                        "exception", e.getClass().getName(),
                        "message", String.valueOf(e.getMessage()),
                        "tempExists", String.valueOf(Files.exists(tempFile))));
                // #endregion
                moveError = e;
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 清理临时文件，避免下次写入时残留旧内容
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
        }
        throw new IOException("无法写入 region 文件，目标文件可能正被占用（请确认 Minecraft 已完全退出；若未开游戏，"
                + "可能是资源管理器、杀毒/索引服务暂时锁定该文件，稍等重试即可）: "
                + regionFile + " (" + moveError.getMessage() + ")", moveError);
    }

    static void quarantineInvalidRegionFile(Path regionFile) throws IOException {
        if (!Files.exists(regionFile)) {
            return;
        }

        long size = Files.size(regionFile);
        if (size >= MIN_REGION_BYTES && size % REGION_SECTOR_BYTES == 0) {
            return;
        }

        String originalName = regionFile.getFileName().toString();
        String movedName = originalName + ".invalid_" + LocalDateTime.now().format(INVALID_REGION_SUFFIX) + ".bak";
        Path movedPath = regionFile.resolveSibling(movedName);
        Files.move(regionFile, movedPath, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("发现损坏 region，已移走: %s -> %s%n", regionFile, movedPath);
    }

    static void quarantineMisplacedRegionFile(Path regionFile) throws IOException {
        if (!Files.exists(regionFile)) {
            return;
        }

        String originalName = regionFile.getFileName().toString();
        String movedName = originalName + ".misplaced_" + LocalDateTime.now().format(INVALID_REGION_SUFFIX) + ".bak";
        Path movedPath = regionFile.resolveSibling(movedName);
        Files.move(regionFile, movedPath, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("发现坐标错位 region，已移走: %s -> %s%n", regionFile, movedPath);
    }

    static boolean isRegionCoordinateConsistent(IRegion region, RegionCoord regionCoord) {
        if (region == null) {
            return true;
        }

        for (IChunk chunk : region.getChunks()) {
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }

            int index = chunk.getIndex();
            int expectedChunkX = regionCoord.regionX() * 32 + (index % 32);
            int expectedChunkZ = regionCoord.regionZ() * 32 + (index / 32);
            if (chunk.getX() != expectedChunkX || chunk.getZ() != expectedChunkZ) {
                return false;
            }
        }
        return true;
    }

    static int currentUnixTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    static byte[] createChunkPayload(ICompoundTag root) throws IOException {
        byte[] nbtData = writeCompoundNbt(root);
        byte[] compressed = compressZlib(nbtData);

        ByteArrayOutputStream chunkStream = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(chunkStream)) {
            output.writeInt(compressed.length + 1);
            output.writeByte(2);
            output.write(compressed);
        }

        byte[] rawChunk = chunkStream.toByteArray();
        int remainder = rawChunk.length % (int) REGION_SECTOR_BYTES;
        if (remainder == 0) {
            return rawChunk;
        }
        return Arrays.copyOf(rawChunk, rawChunk.length + ((int) REGION_SECTOR_BYTES - remainder));
    }

    static byte[] writeCompoundNbt(ICompoundTag root) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DataOutputStream dataOutput = new DataOutputStream(outputStream);
             de.pauleff.jnbt.formats.binary.NBTWriter writer = new de.pauleff.jnbt.formats.binary.NBTWriter(dataOutput)) {
            writer.write(root);
        }
        return outputStream.toByteArray();
    }

    static byte[] compressZlib(byte[] input) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(outputStream)) {
            deflater.write(input);
        }
        return outputStream.toByteArray();
    }

    static void writeIntToArray(byte[] array, int offset, int value) {
        array[offset] = (byte) ((value >>> 24) & 0xFF);
        array[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        array[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        array[offset + 3] = (byte) (value & 0xFF);
    }

    static ICompoundTag buildChunkRoot(ICompoundTag sourceRoot,
                                       ICompoundTag templateRoot,
                                       Map<Integer, SectionBuilder> generatedSections,
                                       boolean preserveUntouchedSections,
                                       int chunkX,
                                       int chunkZ,
                                       int requestedDataVersion) {
        int minSectionY = generatedSections.keySet().stream().min(Integer::compareTo).orElse(0);
        // 优先继承当前世界实际区块的 DataVersion，避免把旧版本号写进高版本世界导致升级失败
        int dataVersion = sourceRoot != null && sourceRoot.hasTag("DataVersion")
                ? sourceRoot.getInt("DataVersion")
                : requestedDataVersion > 0
                ? requestedDataVersion
                : FALLBACK_DATA_VERSION;
        ICompoundTag seedRoot = sourceRoot != null ? sourceRoot : createMinimalChunkRoot(minSectionY, dataVersion, chunkX, chunkZ);
        ICompoundTag root = deepCopyCompound(seedRoot);

        root.setInt("DataVersion", dataVersion);
        root.setInt("xPos", chunkX);
        root.setInt("zPos", chunkZ);
        int chunkBaseSectionY = root.hasTag("yPos") ? root.getInt("yPos") : minSectionY;
        root.setInt("yPos", Math.min(chunkBaseSectionY, minSectionY));
        root.setString("Status", "minecraft:full");
        root.setLong("LastUpdate", 0L);
        root.setLong("InhabitedTime", 0L);
        root.setByte("isLightOn", (byte) 0);

        Map<Integer, ICompoundTag> currentSections = sectionsByY(root);
        Map<Integer, ICompoundTag> templateSections = templateRoot == null ? Map.of() : sectionsByY(templateRoot);
        ICompoundTag fallbackBiomeSource = firstBiomeSource(currentSections, templateSections);
        TreeMap<Integer, ICompoundTag> mergedSections = new TreeMap<>();

        if (preserveUntouchedSections) {
            for (Map.Entry<Integer, ICompoundTag> entry : currentSections.entrySet()) {
                if (!generatedSections.containsKey(entry.getKey())) {
                    mergedSections.put(entry.getKey(), deepCopyCompound(entry.getValue()));
                }
            }
        }

        List<Integer> sortedSectionYs = new ArrayList<>(generatedSections.keySet());
        sortedSectionYs.sort(Integer::compareTo);
        for (Integer sectionY : sortedSectionYs) {
            ICompoundTag biomeSource = currentSections.get(sectionY);
            if (biomeSource == null) {
                biomeSource = templateSections.get(sectionY);
            }
            if (biomeSource == null) {
                biomeSource = fallbackBiomeSource;
            }
            // 挖空行为：重建 section 时只填模型块，其余位置为空气（不保留原方块）
            mergedSections.put(sectionY, generatedSections.get(sectionY).build(sectionY, biomeSource));
        }

        applyChunkLighting(mergedSections);

        Tag_List sectionList = new Tag_List("sections", 10);
        for (ICompoundTag section : mergedSections.values()) {
            sectionList.getData().add((Tag<?>) section);
        }
        root.setTag(sectionList);
        // 保留源区块已有的 Heightmaps（不覆盖为空），缺失时才补空；isLightOn 保持 0 让游戏重新计算光照
        if (!root.hasTag("Heightmaps")) {
            root.setTag(new Tag_Compound("Heightmaps"));
        }
        root.setTag(new Tag_List("block_entities", 10));
        root.setTag(new Tag_List("entities", 10));
        root.setTag(new Tag_List("block_ticks", 10));
        root.setTag(new Tag_List("fluid_ticks", 10));
        root.setTag(new Tag_List("PostProcessing", 9));
        root.removeTag("structures");

        return root;
    }

    static Map<Integer, ICompoundTag> sectionsByY(ICompoundTag root) {
        Map<Integer, ICompoundTag> sections = new HashMap<>();
        if (root == null || !root.hasTag("sections")) {
            return sections;
        }
        IListTag sectionList = root.getList("sections");
        for (int i = 0; i < sectionList.size(); i++) {
            ICompoundTag section = (ICompoundTag) sectionList.get(i);
            sections.put((int) section.getByte("Y"), section);
        }
        return sections;
    }

    static ICompoundTag firstBiomeSource(Map<Integer, ICompoundTag> currentSections,
                                         Map<Integer, ICompoundTag> templateSections) {
        for (ICompoundTag section : currentSections.values()) {
            if (section.hasTag("biomes")) {
                return section;
            }
        }
        for (ICompoundTag section : templateSections.values()) {
            if (section.hasTag("biomes")) {
                return section;
            }
        }
        return null;
    }

    static ICompoundTag copyBiomeTag(ICompoundTag sectionSource) {
        if (sectionSource != null && sectionSource.hasTag("biomes")) {
            ITag<?> biomeTag = sectionSource.getTag("biomes");
            Tag<?> copied = deepCopyTag(biomeTag);
            copied.setName("biomes");
            return (ICompoundTag) copied;
        }

        Tag_Compound biomes = new Tag_Compound("biomes");
        Tag_List palette = new Tag_List("palette", 8);
        palette.getData().add(new Tag_String("", "minecraft:plains"));
        biomes.setTag(palette);
        return biomes;
    }

    static ICompoundTag createPaletteEntry(String blockState) {
        ParsedBlockState parsed = ParsedBlockState.parse(blockState);
        Tag_Compound tag = new Tag_Compound("");
        tag.setString("Name", parsed.name());
        if (!parsed.properties().isEmpty()) {
            Tag_Compound properties = new Tag_Compound("Properties");
            for (Map.Entry<String, String> property : parsed.properties().entrySet()) {
                properties.setString(property.getKey(), property.getValue());
            }
            tag.setTag(properties);
        }
        return tag;
    }

    static void applyChunkLighting(TreeMap<Integer, ICompoundTag> mergedSections) {
        if (mergedSections.isEmpty()) {
            return;
        }

        Map<Integer, boolean[]> opaqueBlocksBySection = new HashMap<>();
        for (Map.Entry<Integer, ICompoundTag> entry : mergedSections.entrySet()) {
            opaqueBlocksBySection.put(entry.getKey(), extractOpaqueBlocks(entry.getValue()));
        }

        Map<Integer, byte[]> skyLightBySection = new HashMap<>();
        Map<Integer, byte[]> blockLightBySection = new HashMap<>();
        for (Integer sectionY : mergedSections.keySet()) {
            skyLightBySection.put(sectionY, new byte[2048]);
            blockLightBySection.put(sectionY, new byte[2048]);
        }

        List<Integer> sectionYs = new ArrayList<>(mergedSections.keySet());
        sectionYs.sort(Comparator.reverseOrder());

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int skyLight = 15;
                for (Integer sectionY : sectionYs) {
                    boolean[] opaqueBlocks = opaqueBlocksBySection.get(sectionY);
                    byte[] skyArray = skyLightBySection.get(sectionY);
                    for (int localY = 15; localY >= 0; localY--) {
                        int flatIndex = localY * 256 + localZ * 16 + localX;
                        if (opaqueBlocks[flatIndex]) {
                            setNibble(skyArray, flatIndex, 0);
                            skyLight = 0;
                        } else {
                            setNibble(skyArray, flatIndex, skyLight);
                        }
                    }
                }
            }
        }

        for (Map.Entry<Integer, ICompoundTag> entry : mergedSections.entrySet()) {
            ICompoundTag section = entry.getValue();
            section.removeTag("SkyLight");
            section.removeTag("BlockLight");
            section.setTag(new Tag_Byte_Array("SkyLight", skyLightBySection.get(entry.getKey())));
            section.setTag(new Tag_Byte_Array("BlockLight", blockLightBySection.get(entry.getKey())));
        }
    }

    static boolean[] extractOpaqueBlocks(ICompoundTag section) {
        boolean[] opaque = new boolean[4096];
        if (section == null || !section.hasTag("block_states")) {
            return opaque;
        }

        ICompoundTag blockStates = section.getCompound("block_states");
        if (!blockStates.hasTag("palette")) {
            return opaque;
        }

        IListTag palette = blockStates.getList("palette");
        if (palette.size() == 0) {
            return opaque;
        }

        boolean[] opaquePalette = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            ICompoundTag paletteEntry = (ICompoundTag) palette.get(i);
            opaquePalette[i] = isOpaqueBlock(paletteEntry.getString("Name"));
        }

        if (palette.size() == 1 && !blockStates.hasTag("data")) {
            if (opaquePalette[0]) {
                java.util.Arrays.fill(opaque, true);
            }
            return opaque;
        }

        long[] packed = blockStates.hasTag("data") ? blockStates.getLongArray("data") : new long[0];
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));

        for (int index = 0; index < 4096; index++) {
            int paletteIndex = paletteIndexAt(packed, bitsPerBlock, index);
            if (paletteIndex >= 0 && paletteIndex < opaquePalette.length) {
                opaque[index] = opaquePalette[paletteIndex];
            }
        }
        return opaque;
    }

    static boolean isOpaqueBlock(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return false;
        }
        return !blockName.endsWith("air")
                && !blockName.contains("water")
                && !blockName.contains("glass")
                && !blockName.contains("leaves");
    }

    static void setNibble(byte[] array, int index, int value) {
        int byteIndex = index >> 1;
        int nibble = value & 0xF;
        if ((index & 1) == 0) {
            array[byteIndex] = (byte) ((array[byteIndex] & 0xF0) | nibble);
        } else {
            array[byteIndex] = (byte) ((array[byteIndex] & 0x0F) | (nibble << 4));
        }
    }

    /**
     * 按 1.20.5+ 的 PalettedContainer 格式打包 block_states。
     * 新格式：每个 long 固定存放 64/bits 个条目（bits=4 -> 16 条/long=256，
     * bits=5 -> 12 条/long=342，bits=6 -> 10 条/long=410），条目不跨 long，低位在前连续排列。
     */
    static long[] packBlockStates(int[] blockData, int paletteSize) {
        int bitsPerBlock = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        int entriesPerLong = 64 / bitsPerBlock;
        int longCount = (int) Math.ceil(blockData.length / (double) entriesPerLong);
        long[] packed = new long[longCount];
        long mask = (1L << bitsPerBlock) - 1L;

        for (int index = 0; index < blockData.length; index++) {
            int longIndex = index / entriesPerLong;
            int offset = index % entriesPerLong;
            packed[longIndex] |= ((long) blockData[index] & mask) << (offset * bitsPerBlock);
        }
        return packed;
    }

    /** 按 1.20.5+ 格式读取指定下标的 palette 索引；越界/空数据返回 0（air）。 */
    static int paletteIndexAt(long[] data, int bitsPerBlock, int index) {
        if (data == null || data.length == 0) {
            return 0;
        }
        int entriesPerLong = 64 / bitsPerBlock;
        int longIndex = index / entriesPerLong;
        if (longIndex >= data.length) {
            return 0;
        }
        int offset = index % entriesPerLong;
        long mask = (1L << bitsPerBlock) - 1L;
        return (int) ((data[longIndex] >>> (offset * bitsPerBlock)) & mask);
    }

    static ICompoundTag createMinimalChunkRoot(int minSectionY, int dataVersion, int chunkX, int chunkZ) {
        Tag_Compound root = new Tag_Compound("");
        root.setInt("DataVersion", dataVersion);
        root.setInt("xPos", chunkX);
        root.setInt("yPos", minSectionY);
        root.setInt("zPos", chunkZ);
        root.setString("Status", "minecraft:full");
        root.setLong("LastUpdate", 0L);
        root.setLong("InhabitedTime", 0L);
        root.setByte("isLightOn", (byte) 0);
        root.setTag(new Tag_Compound("Heightmaps"));
        root.setTag(new Tag_List("sections", 10));
        root.setTag(new Tag_List("block_entities", 10));
        root.setTag(new Tag_List("entities", 10));
        root.setTag(new Tag_List("block_ticks", 10));
        root.setTag(new Tag_List("fluid_ticks", 10));
        root.setTag(new Tag_List("PostProcessing", 9));
        return root;
    }

    static ICompoundTag deepCopyCompound(ICompoundTag compound) {
        return (ICompoundTag) deepCopyTag(compound);
    }

    static Tag<?> deepCopyTag(ITag<?> tag) {
        String name = tag.getName();
        return switch (tag.getId()) {
            case 1 -> new Tag_Byte(name, (Byte) tag.getData());
            case 2 -> new Tag_Short(name, (Short) tag.getData());
            case 3 -> new Tag_Int(name, (Integer) tag.getData());
            case 4 -> new Tag_Long(name, (Long) tag.getData());
            case 5 -> new Tag_Float(name, (Float) tag.getData());
            case 6 -> new Tag_Double(name, (Double) tag.getData());
            case 7 -> new Tag_Byte_Array(name, ((byte[]) tag.getData()).clone());
            case 8 -> new Tag_String(name, (String) tag.getData());
            case 9 -> {
                IListTag listTag = (IListTag) tag;
                Tag_List copy = new Tag_List(name, listTag.getListTypeID());
                for (int i = 0; i < listTag.size(); i++) {
                    copy.getData().add(deepCopyTag(listTag.get(i)));
                }
                yield copy;
            }
            case 10 -> {
                ICompoundTag compoundTag = (ICompoundTag) tag;
                Tag_Compound copy = new Tag_Compound(name);
                for (Tag<?> child : compoundTag.getData()) {
                    copy.setTag(deepCopyTag(child));
                }
                yield copy;
            }
            case 11 -> new Tag_Int_Array(name, ((int[]) tag.getData()).clone());
            case 12 -> new Tag_Long_Array(name, ((long[]) tag.getData()).clone());
            default -> throw new IllegalArgumentException("不支持复制的 NBT tag id: " + tag.getId());
        };
    }
}
