package org.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 体素化入口：负责把 OBJ 模型转成体素网格、按放置点平移到世界坐标，
 * 并对重复参数（相同文件 + 相同体素参数 + 相同映射配置）做缓存，避免刷新预览时反复重建。
 */
final class ModelVoxelizer {
    /** 世界坐标缓存条目上限，按访问顺序淘汰最旧条目。 */
    private static final int CACHE_CAPACITY = 6;
    /** 缓存总体素数预算（约 1.6GB 紧凑网格），防止多个大模型缓存失控导致 OOM。 */
    private static final long CACHE_MAX_TOTAL_VOXELS = 64_000_000L;

    private static final Map<String, SparseVoxelGrid> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SparseVoxelGrid> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };
    private static long cachedTotalVoxels;
    private static final Object CACHE_LOCK = new Object();
    /** 磁盘缓存写入线程：体素网格动辄几百 MB，后台落盘不拖慢预览。 */
    private static final java.util.concurrent.ExecutorService DISK_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "model2mc-voxel-cache-writer");
                thread.setDaemon(true);
                return thread;
            });

    /** 阶段子进度回调：phase 为 parse/voxelize/merge/denoise 等阶段名，done/total 为该阶段进度（多线程并发调用）。 */
    @FunctionalInterface
    interface PhaseProgress {
        void accept(String phase, long done, long total);
    }

    private ModelVoxelizer() {
    }

    /** 依次（存在多个模型时并行）体素化全部模型，返回与 models 同序的网格列表。 */
    static List<SparseVoxelGrid> voxelizeAll(List<PlacedModel> models, Config config) throws IOException {
        return voxelizeAll(models, config, null, null);
    }

    /** 依次（存在多个模型时并行）体素化全部模型；onModelDone 每完成一个模型回调已完成数量（可为 null）。 */
    static List<SparseVoxelGrid> voxelizeAll(List<PlacedModel> models, Config config,
                                             java.util.function.IntConsumer onModelDone) throws IOException {
        return voxelizeAll(models, config, onModelDone, null);
    }

    /** 同上，并透出阶段子进度（解析/体素化/合并/去杂各阶段的百分比）。 */
    static List<SparseVoxelGrid> voxelizeAll(List<PlacedModel> models, Config config,
                                             java.util.function.IntConsumer onModelDone,
                                             PhaseProgress onPhaseProgress) throws IOException {
        if (models.size() <= 1) {
            SparseVoxelGrid grid = voxelize(models.get(0), config, onPhaseProgress);
            if (onModelDone != null) {
                onModelDone.accept(1);
            }
            return List.of(grid);
        }

        int threads = Math.min(models.size(), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SparseVoxelGrid>> futures = new ArrayList<>(models.size());
        try {
            for (PlacedModel model : models) {
                futures.add(pool.submit(() -> voxelize(model, config, onPhaseProgress)));
            }
            List<SparseVoxelGrid> grids = new ArrayList<>(models.size());
            for (Future<SparseVoxelGrid> future : futures) {
                grids.add(future.get());
                if (onModelDone != null) {
                    onModelDone.accept(grids.size());
                }
            }
            return grids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("体素化被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("体素化失败", cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 体素化单个模型；命中缓存时直接复用，否则重建并写入缓存。 */
    static SparseVoxelGrid voxelize(PlacedModel model, Config config) throws IOException {
        return voxelize(model, config, null);
    }

    static SparseVoxelGrid voxelize(PlacedModel model, Config config, PhaseProgress onPhaseProgress) throws IOException {
        String key = signature(model, config);
        String modelName = model.objPath() != null
                ? String.valueOf(model.objPath().getFileName()) : "?";
        synchronized (CACHE_LOCK) {
            SparseVoxelGrid cached = CACHE.get(key);
            if (cached != null) {
                // 内存缓存命中：同参数模型已在本次进程内体素化过，直接复用网格
                System.out.printf(Locale.ROOT, "体素化命中内存缓存[%s]: %,d 体素%n", modelName, cached.size());
                return cached;
            }
        }

        // 内存未命中先查磁盘缓存：重启进程/内存被挤出后，相同模型+参数直接恢复网格
        java.nio.file.Path diskFile = DiskCache.voxelFile(key);
        if (java.nio.file.Files.isRegularFile(diskFile)) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(diskFile)) {
                SparseVoxelGrid restored = SparseVoxelGrid.readFrom(in, BlockPalette.DEFAULT);
                // 磁盘缓存命中：网格落盘过，恢复后同时放回内存缓存供本次后续复用
                System.out.printf(Locale.ROOT, "体素化命中磁盘缓存[%s]: %,d 体素%n", modelName, restored.size());
                putCache(key, restored);
                return restored;
            } catch (IOException corruptOrFailed) {
                System.out.println("体素磁盘缓存不可用，回退重新体素化: " + corruptOrFailed.getMessage());
            }
        }

        long parseStart = System.nanoTime();
        ObjPassOneResult passOne = ObjParser.firstPass(model.objPath(), onPhaseProgress);
        int targetHeight = effectiveHeight(model, config);
        // 任意角度旋转：在顶点层应用（绕模型中心），重算包围盒后栅格化，保证体素连续无镂空
        ObjPassOneResult effectivePassOne = passOne;
        if (model.requiresRotation()) {
            FloatTriples rotated = ObjParser.rotateVertices(passOne.vertices, passOne.bounds,
                    model.rotationX(), model.rotationY(), model.rotationZ());
            effectivePassOne = new ObjPassOneResult(rotated, passOne.texCoords,
                    ObjParser.boundsOf(rotated), passOne.faceCount);
        }
        VoxelSpace voxelSpace = VoxelSpace.fromBounds(effectivePassOne.bounds, targetHeight, config.maxDimension);
        TextureSampler textureSampler = TextureSampler.load(model.texturePath());
        long parseEnd = System.nanoTime();

        // 单模型内部再按面片区间并行栅格化（16 核机器对 300 万面片能提速数倍）
        int threads = Math.max(1, Math.min(12, Runtime.getRuntime().availableProcessors() - 2));
        SparseVoxelGrid grid = ObjParser.secondPass(
                model.objPath(),
                effectivePassOne,
                voxelSpace,
                textureSampler,
                config.samplesPerVoxel,
                config.maxTriangleSamples,
                threads,
                onPhaseProgress);
        long rasterEnd = System.nanoTime();

        // 应用映射文件解析出的配色与过滤模式，再做体素去杂。
        BlockPalette.DEFAULT.applyOverrides(config.paletteOverrides);
        BlockPalette.DEFAULT.applyMode(config.paletteMode);
        grid.denoise(config.denoise, onPhaseProgress);

        System.out.printf(Locale.ROOT,
                "体素化完成: %,d 面片 → %,d 体素 (解析 %.1fs, 栅格化 %.1fs)%n",
                passOne.faceCount,
                grid.size(),
                (parseEnd - parseStart) / 1e9,
                (rasterEnd - parseEnd) / 1e9);

        putCache(key, grid);
        // 后台落盘：网格去杂后不再被修改，后台写不拖慢预览；写完按磁盘预算清理旧缓存
        final SparseVoxelGrid snapshot = grid;
        final java.nio.file.Path target = diskFile;
        DISK_WRITER.submit(() -> {
            try {
                DiskCache.writeAtomically(target, out -> snapshot.writeTo(out));
                DiskCache.enforceBudget(target.getParent(), DiskCache.VOXEL_BUDGET_BYTES);
            } catch (Throwable t) {
                System.out.println("体素磁盘缓存写入失败（不影响预览）: " + t);
            }
        });
        return grid;
    }

    /** 从缓存中逐出指定模型（写入流程合并完即可释放，降低运行期峰值内存）。 */
    static void evict(PlacedModel model, Config config) {
        synchronized (CACHE_LOCK) {
            SparseVoxelGrid removed = CACHE.remove(signature(model, config));
            if (removed != null) {
                cachedTotalVoxels -= removed.size();
            }
        }
    }

    static PaletteStore.GeneratedProfile generateModelLoadedPaletteProfile(List<SparseVoxelGrid> grids) throws IOException {
        return PaletteStore.saveModelLoadedProfile(collectGeneratedPaletteBlocks(grids));
    }

    /** 按用户指定的文件名生成模型方块映射；profileId 为空时退回固定的 model_loaded_blocks。 */
    static PaletteStore.GeneratedProfile generateNamedPaletteProfile(String profileId,
                                                                     List<SparseVoxelGrid> grids) throws IOException {
        return PaletteStore.saveGeneratedProfile(profileId, collectGeneratedPaletteBlocks(grids));
    }

    static Map<String, String> collectGeneratedPaletteEntries(List<SparseVoxelGrid> grids) {
        return PaletteStore.buildGeneratedEntries(collectGeneratedPaletteBlocks(grids));
    }

    private static LinkedHashSet<String> collectGeneratedPaletteBlocks(List<SparseVoxelGrid> grids) {
        LinkedHashSet<String> blocks = new LinkedHashSet<>();
        if (grids != null) {
            for (SparseVoxelGrid grid : grids) {
                if (grid == null) {
                    continue;
                }
                grid.forEach((x, y, z, rgb) -> blocks.add(BlockPalette.DEFAULT.closestBlock(rgb)));
            }
        }
        return blocks;
    }

    private static void putCache(String key, SparseVoxelGrid grid) {
        synchronized (CACHE_LOCK) {
            SparseVoxelGrid previous = CACHE.put(key, grid);
            if (previous != null) {
                cachedTotalVoxels -= previous.size();
            }
            cachedTotalVoxels += grid.size();
            // 超出总体素数预算时淘汰最久未用的网格
            var iterator = CACHE.entrySet().iterator();
            while (cachedTotalVoxels > CACHE_MAX_TOTAL_VOXELS && iterator.hasNext()) {
                Map.Entry<String, SparseVoxelGrid> eldest = iterator.next();
                iterator.remove();
                cachedTotalVoxels -= eldest.getValue().size();
            }
        }
    }

    /**
     * 把体素网格按放置原点平移（x/z 以模型中心对齐，y 以脚底对齐）合并进目标世界网格。
     * 直接在紧凑网格上累加，不产生整张网格的拷贝，降低大模型峰值内存。
     */
    static void mergePlaced(SparseVoxelGrid target, SparseVoxelGrid source, PlacedModel model) {
        PlacementAnchor anchor = PlacementAnchor.fromVoxelGrid(source);
        int dx = model.placeX() - anchor.centerVoxelX();
        int dy = model.placeY() - anchor.minVoxelY();
        int dz = model.placeZ() - anchor.centerVoxelZ();
        target.merge(source, dx, dy, dz);
    }

    private static String signature(PlacedModel model, Config config) {
        StringBuilder builder = new StringBuilder(192);
        // 体素平均色算法版本：mergeColor 改动的逻辑会影响缓存里的平均色，版本变了旧体素缓存全部失效
        builder.append("blend=v2|");
        appendFile(builder, model.objPath());
        appendFile(builder, model.mtlPath());
        appendFile(builder, model.texturePath());
        builder.append('|').append(effectiveHeight(model, config))
                .append('|').append(config.maxDimension)
                .append('|').append(config.samplesPerVoxel)
                .append('|').append(config.maxTriangleSamples)
                .append('|').append(config.denoise)
                .append('|').append(config.paletteProfile)
                .append('|').append(config.paletteSignature)
                .append('|').append(model.rotationX())
                .append('|').append(model.rotationY())
                .append('|').append(model.rotationZ());
        return builder.toString();
    }

    /** 每模型单独设置的目标高度；未设置（0）时继承全局 --height。 */
    private static int effectiveHeight(PlacedModel model, Config config) {
        return model.targetHeight() > 0 ? model.targetHeight() : config.targetHeight;
    }

    private static void appendFile(StringBuilder builder, java.nio.file.Path path) {
        builder.append(path.toAbsolutePath().normalize());
        try {
            builder.append('@').append(java.nio.file.Files.size(path))
                    .append(':').append(java.nio.file.Files.getLastModifiedTime(path).toMillis());
        } catch (IOException ignored) {
            builder.append("@0:0");
        }
        builder.append(';');
    }
}
