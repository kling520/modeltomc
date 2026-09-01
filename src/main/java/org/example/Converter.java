package org.example;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

final class Converter {
    final Config config;

    Converter(Config config) {
        this.config = config;
    }

    void run() throws IOException {
        run(null);
    }

    void run(TaskProgress progress) throws IOException {
        System.out.println("开始转换 OBJ -> Minecraft 存档");
        System.out.println("模型数量: " + config.models.size());
        for (int i = 0; i < config.models.size(); i++) {
            System.out.println("模型 " + (i + 1) + " OBJ: " + config.models.get(i).objPath());
        }
        System.out.println("存档: " + config.worldPath);
        System.out.println("目标 region: " + config.regionDirectory());
        System.out.println("维度: " + config.dimension);
        verifyRegionDirectoryWritable();

        // 应用映射文件的全部条目：体素颜色 -> 方块的最近色匹配只使用映射文件里列出的方块
        BlockPalette.DEFAULT.applyOverrides(config.paletteOverrides);
        BlockPalette.DEFAULT.applyMode(config.paletteMode);
        if (BlockPalette.DEFAULT.usesProfilePalette()) {
            System.out.println("已加载映射文件调色板: " + config.paletteProfile
                    + " (" + BlockPalette.DEFAULT.activeEntryCount() + " 个方块参与匹配)");
        }

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
                    "已生成模型方块映射文件: %s (%d 个方块, %s)%n",
                    generatedProfile.id(),
                    generatedProfile.blockCount(),
                    generatedProfile.path());
        }
        long totalVoxels = 0;
        for (SparseVoxelGrid grid : grids) {
            totalVoxels += grid.size();
        }
        SparseVoxelGrid worldGrid = new SparseVoxelGrid(BlockPalette.DEFAULT);
        worldGrid.reserve((int) Math.min(totalVoxels, 200_000_000L));
        if (progress != null) {
            progress.phase = "merge";
            progress.done = 0;
            progress.total = config.models.size();
            progress.subDone = 0;
            progress.subTotal = 0;
        }
        for (int i = 0; i < config.models.size(); i++) {
            PlacedModel model = config.models.get(i);
            SparseVoxelGrid grid = grids.get(i);
            PlacementAnchor anchor = PlacementAnchor.fromVoxelGrid(grid);
            System.out.println("模型 " + (i + 1) + " 表面体素数: " + grid.size());
            System.out.printf(
                    Locale.ROOT,
                    "模型 %d 放置原点: x=%d y=%d z=%d%n",
                    i + 1,
                    model.placeX(),
                    model.placeY(),
                    model.placeZ());
            if (model.requiresRotation()) {
                System.out.println("模型 " + (i + 1) + " 朝向: 绕 X=" + model.rotationX()
                        + "° Y=" + model.rotationY() + "° Z=" + model.rotationZ() + "°");
            }
            System.out.printf(
                    Locale.ROOT,
                    "模型 %d 实际写入范围: x=%d..%d y=%d..%d z=%d..%d%n",
                    i + 1,
                    anchor.worldMinX(model.placeX()),
                    anchor.worldMaxX(model.placeX()),
                    anchor.worldMinY(model.placeY()),
                    anchor.worldMaxY(model.placeY()),
                    anchor.worldMinZ(model.placeZ()),
                    anchor.worldMaxZ(model.placeZ()));
            ModelVoxelizer.mergePlaced(worldGrid, grid, model);
            // 合并完立即逐出缓存，让网格可被回收，避免运行期同时持有全部模型网格
            ModelVoxelizer.evict(model, config);
            if (progress != null) {
                progress.done = i + 1;
            }
        }
        grids = null;

        int[] bounds = worldGrid.unionWorldBounds();
        System.out.printf(
                Locale.ROOT,
                "放置规则: x/z 以模型中心对齐，y 以脚底对齐；全部模型合并范围: x=%d..%d y=%d..%d z=%d..%d%n",
                bounds[0],
                bounds[1],
                bounds[2],
                bounds[3],
                bounds[4],
                bounds[5]);
        if (bounds[2] < -64 || bounds[3] > 319) {
            System.out.printf(
                    Locale.ROOT,
                    "警告: 当前写入范围 y=%d..%d 已超出原版默认高度，只有目标存档本身支持更高世界时才会正常显示。%n",
                    bounds[2],
                    bounds[3]);
        }

        WorldWriteStats stats = WorldWriter.write(config, worldGrid, progress);
        System.out.println("写入完成: " + config.regionDirectory());
        System.out.printf(Locale.ROOT, "写入 region 数量: %d%n", stats.regionCount());
        System.out.printf(Locale.ROOT, "写入 chunk 数量: %d%n", stats.chunkCount());
        System.out.printf(Locale.ROOT, "写入 section 数量: %d%n", stats.sectionCount());
        if (progress != null) {
            progress.phase = "done";
            progress.done = progress.total;
            progress.subDone = 0;
            progress.subTotal = 0;
            progress.finished = true;
        }
    }

    private void verifyRegionDirectoryWritable() throws IOException {
        Path regionDirectory = config.regionDirectory();
        if (!Files.isDirectory(regionDirectory)) {
            throw new IOException("未找到目标 region 目录: " + regionDirectory);
        }

        Path probeFile = regionDirectory.resolve(".__model2mc_write_probe_" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
        try {
            Files.write(probeFile, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.deleteIfExists(probeFile);
            System.out.println("region 写入探针: 可创建临时文件");
        } catch (AccessDeniedException e) {
            throw new IOException(
                    "当前 Web UI 进程无法向目标 region 目录创建临时文件: " + regionDirectory
                            + "。这通常说明服务是从受限环境启动的，不是模型或光源参数的问题。"
                            + "请先关闭当前 8080 服务，再在本机手动双击 run.bat 启动后重试。",
                    e);
        } catch (IOException e) {
            throw new IOException("目标 region 目录写入探针失败: " + regionDirectory + " (" + e.getMessage() + ")", e);
        } finally {
            Files.deleteIfExists(probeFile);
        }
    }
}
