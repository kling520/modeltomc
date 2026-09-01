package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把体素化结果导出为 Litematica 投影（.litematic），
 * 复用 LegacyFormats 中已有的写入器。导出只生成文件，不修改任何存档。
 */
final class ExportSupport {
    /** Litematica 单 region 最大边长，超过后自动按 region 分块。 */
    private static final int LITEMATIC_REGION_SIZE = 512;
    /** Litematica 单 region 最大高度。 */
    private static final int LITEMATIC_SLICE_HEIGHT = 256;
    /** 表面光源：检查 6 方向邻居是否为模型体素。 */
    private static final int[][] SURFACE_LIGHT_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final String SURFACE_LIGHT_BLOCK_STATE = "minecraft:light[level=15]";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ExportSupport() {
    }

    static Path export(Config config, String format, TaskProgress progress) throws IOException {
        return export(config, format, progress, null);
    }

    static Path export(Config config, String format, TaskProgress progress, Path exportDir) throws IOException {
        if (!"litematic".equals(format)) {
            throw new IllegalArgumentException("不支持的导出格式: " + format + "，仅支持 litematic");
        }
        return exportLitematic(config, progress, exportDir);
    }

    static Path exportLitematic(Config config, TaskProgress progress, Path exportDir) throws IOException {
        System.out.println("开始导出 Litematica 投影（只生成文件，不修改存档）");
        MergedOrigin merged = mergeToOrigin(config, progress);
        String baseName = baseName(config);
        SparseVoxelGrid grid = merged.grid();

        // 表面光源：与写入流程一致，在模型体素外侧（6 方向邻居不是模型体素的位置）铺一层 light[level=15]
        Map<Long, String> extraBlocks = new HashMap<>();
        if (config.addSurfaceLight) {
            grid.forEach((x, y, z, rgb) -> {
                for (int[] offset : SURFACE_LIGHT_OFFSETS) {
                    int neighborX = x + offset[0];
                    int neighborY = y + offset[1];
                    int neighborZ = z + offset[2];
                    if (grid.containsPacked(SparseVoxelGrid.pack(neighborX, neighborY, neighborZ))) {
                        continue;
                    }
                    extraBlocks.putIfAbsent(
                            SparseVoxelGrid.packWorld(neighborX, neighborY, neighborZ),
                            SURFACE_LIGHT_BLOCK_STATE);
                }
            });
            System.out.printf(Locale.ROOT, "表面光源: 模型外侧补 %d 个 light[level=15]%n", extraBlocks.size());
        }

        // 包围盒把光源体素一并纳入（最多向外扩 1 格），保证投影完整
        int[] bounds = expandedBounds(merged.bounds(), extraBlocks.keySet());
        VoxelSpace space = new VoxelSpace(
                0.0,
                0.0,
                0.0,
                1.0,
                bounds[1] - bounds[0] + 1,
                bounds[3] - bounds[2] + 1,
                bounds[5] - bounds[4] + 1);
        List<SchematicPart> parts = grid.toSchematicParts(
                space,
                baseName,
                LITEMATIC_SLICE_HEIGHT,
                LITEMATIC_REGION_SIZE,
                extraBlocks);
        Path output = outputPath(exportDir, baseName);
        LitematicWriter.write(
                output,
                parts,
                baseName,
                bounds[1] - bounds[0] + 1,
                bounds[3] - bounds[2] + 1,
                bounds[5] - bounds[4] + 1);
        logResult("Litematica 投影", output, merged, grid.size() + extraBlocks.size());
        return output;
    }

    /** 体素化全部模型，并按放置坐标合并进以「最小角为原点」的网格（导出内容用非负坐标切块）。 */
    private static MergedOrigin mergeToOrigin(Config config, TaskProgress progress) throws IOException {
        // 与写入流程保持一致：先应用用户自定义方块颜色映射与配色方案
        BlockPalette.DEFAULT.applyOverrides(config.paletteOverrides);
        BlockPalette.DEFAULT.applyMode(config.paletteMode);

        List<SparseVoxelGrid> grids = ModelVoxelizer.voxelizeAll(config.models, config, done -> {
            if (progress != null) {
                progress.done = done;
            }
        }, (phase, subDone, subTotal) -> {
            if (progress != null) {
                progress.phase = phase;
                progress.subDone = subDone;
                progress.subTotal = subTotal;
            }
        });
        if (config.generateModelPalette) {
            PaletteStore.GeneratedProfile generatedProfile = ModelVoxelizer.generateModelLoadedPaletteProfile(grids);
            System.out.printf(Locale.ROOT,
                    "导出: 已生成模型方块映射文件 %s（%d 个方块, %s）%n",
                    generatedProfile.id(),
                    generatedProfile.blockCount(),
                    generatedProfile.path());
        }

        // 先算出全部模型合并后的世界最小角，用于把导出内容平移到非负坐标
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        long totalVoxels = 0L;
        List<PlacementAnchor> anchors = new ArrayList<>(config.models.size());
        for (int i = 0; i < config.models.size(); i++) {
            PlacedModel model = config.models.get(i);
            PlacementAnchor anchor = PlacementAnchor.fromVoxelGrid(grids.get(i));
            anchors.add(anchor);
            minX = Math.min(minX, anchor.worldMinX(model.placeX()));
            minY = Math.min(minY, anchor.worldMinY(model.placeY()));
            minZ = Math.min(minZ, anchor.worldMinZ(model.placeZ()));
            totalVoxels += grids.get(i).size();
        }

        SparseVoxelGrid origin = new SparseVoxelGrid(BlockPalette.DEFAULT);
        origin.reserve((int) Math.min(totalVoxels, 200_000_000L));
        if (progress != null) {
            progress.phase = "merge";
            progress.done = 0;
            progress.total = config.models.size();
            progress.subDone = 0;
            progress.subTotal = 0;
        }
        for (int i = 0; i < config.models.size(); i++) {
            PlacedModel model = config.models.get(i);
            PlacementAnchor anchor = anchors.get(i);
            int dx = model.placeX() - anchor.centerVoxelX() - minX;
            int dy = model.placeY() - anchor.minVoxelY() - minY;
            int dz = model.placeZ() - anchor.centerVoxelZ() - minZ;
            origin.mergeLocal(grids.get(i), dx, dy, dz);
            System.out.printf(Locale.ROOT, "导出: 模型 %d 表面体素数 %,d%n", i + 1, grids.get(i).size());
            // 合并完立即逐出缓存，让网格可被回收，避免运行期同时持有全部模型网格
            ModelVoxelizer.evict(model, config);
            if (progress != null) {
                progress.done = i + 1;
            }
        }

        if (origin.isEmpty()) {
            throw new IOException("没有可写入的体素，无法导出");
        }
        PlacementAnchor originAnchor = PlacementAnchor.fromVoxelGrid(origin);
        int[] bounds = {
                originAnchor.minVoxelX(), originAnchor.maxVoxelX(),
                originAnchor.minVoxelY(), originAnchor.maxVoxelY(),
                originAnchor.minVoxelZ(), originAnchor.maxVoxelZ()
        };
        System.out.printf(Locale.ROOT,
                "导出原点: 以模型合并后的世界最小角 (x=%d y=%d z=%d) 为文件内 (0,0,0)，"
                        + "包围盒 x=%d..%d y=%d..%d z=%d..%d%n",
                minX, minY, minZ,
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        if (progress != null) {
            progress.phase = "export";
            progress.done = 1;
            progress.total = 1;
            progress.subDone = 0;
            progress.subTotal = 0;
        }
        return new MergedOrigin(origin, bounds);
    }

    /** 把额外光源体素并入包围盒（光源最多在模型边缘外扩 1 格）。 */
    private static int[] expandedBounds(int[] bounds, Set<Long> extra) {
        int[] expanded = bounds.clone();
        if (extra.isEmpty()) {
            return expanded;
        }
        int[] position = new int[3];
        for (long key : extra) {
            SparseVoxelGrid.unpackWorldInto(key, position);
            expanded[0] = Math.min(expanded[0], position[0]);
            expanded[1] = Math.max(expanded[1], position[0]);
            expanded[2] = Math.min(expanded[2], position[1]);
            expanded[3] = Math.max(expanded[3], position[1]);
            expanded[4] = Math.min(expanded[4], position[2]);
            expanded[5] = Math.max(expanded[5], position[2]);
        }
        return expanded;
    }

    private static Path outputPath(Path exportDir, String baseName) throws IOException {
        Path directory = exportDir == null
                ? Paths.get("exports").toAbsolutePath().normalize()
                : exportDir.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory.resolve(baseName + "_" + LocalDateTime.now().format(STAMP) + ".litematic");
    }

    /** 输出文件基名取第一个模型的 OBJ 文件名（去掉扩展名）。 */
    private static String baseName(Config config) {
        String fileName = config.models.get(0).objPath().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return name.isBlank() ? "model" : name;
    }

    private static void logResult(String label, Path output, MergedOrigin merged, long blockCount) {
        int[] bounds = merged.bounds();
        System.out.printf(Locale.ROOT,
                "导出完成: %s 方块 %,d，尺寸 WxHxL = %dx%dx%d%n",
                label,
                blockCount,
                bounds[1] - bounds[0] + 1,
                bounds[3] - bounds[2] + 1,
                bounds[5] - bounds[4] + 1);
        System.out.println("导出文件: " + output.toAbsolutePath().normalize());
    }
}

record MergedOrigin(SparseVoxelGrid grid, int[] bounds) {
}
