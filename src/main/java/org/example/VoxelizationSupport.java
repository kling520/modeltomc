package org.example;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import javax.imageio.ImageIO;

final class ObjParser {
    private ObjParser() {
    }

    static ObjPassOneResult firstPass(Path objPath) throws IOException {
        return firstPass(objPath, null);
    }

    static ObjPassOneResult firstPass(Path objPath, ModelVoxelizer.PhaseProgress onPhaseProgress) throws IOException {
        FloatTriples vertices = new FloatTriples();
        FloatPairs texCoords = new FloatPairs();
        Bounds bounds = new Bounds();
        long faceCount = 0L;
        // 解析进度按文件字节估算（readLine 拿不到字节偏移，行字符数+换行近似，UTF-8 多字节会略超，上报时钳制）
        long totalBytes = Math.max(1L, objPath.toFile().length());
        long bytesRead = 0L;
        long reportInterval = Math.max(1L, totalBytes / 100L);
        long nextReport = reportInterval;

        try (BufferedReader reader = Files.newBufferedReader(objPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytesRead += line.length() + 1L;
                if (onPhaseProgress != null && bytesRead >= nextReport) {
                    onPhaseProgress.accept("parse", Math.min(bytesRead, totalBytes), totalBytes);
                    nextReport += reportInterval;
                }
                if (line.startsWith("v ")) {
                    StringTokenizer tokenizer = new StringTokenizer(line);
                    tokenizer.nextToken();
                    float x = Float.parseFloat(tokenizer.nextToken());
                    float y = Float.parseFloat(tokenizer.nextToken());
                    float z = Float.parseFloat(tokenizer.nextToken());
                    vertices.add(x, y, z);
                    bounds.include(x, y, z);
                } else if (line.startsWith("vt ")) {
                    StringTokenizer tokenizer = new StringTokenizer(line);
                    tokenizer.nextToken();
                    float u = Float.parseFloat(tokenizer.nextToken());
                    float v = Float.parseFloat(tokenizer.nextToken());
                    texCoords.add(u, v);
                } else if (line.startsWith("f ")) {
                    faceCount++;
                }
            }
        }
        if (onPhaseProgress != null) {
            onPhaseProgress.accept("parse", totalBytes, totalBytes);
        }

        return new ObjPassOneResult(vertices, texCoords, bounds, faceCount);
    }

    static SparseVoxelGrid secondPass(Path objPath,
                                      ObjPassOneResult passOne,
                                      VoxelSpace voxelSpace,
                                      TextureSampler textureSampler,
                                      double samplesPerVoxel,
                                      int maxTriangleSamples) throws IOException {
        return secondPass(objPath, passOne, voxelSpace, textureSampler, samplesPerVoxel, maxTriangleSamples, 1, null);
    }

    /**
     * 体素化第二遍：栅格化全部面片。threads > 1 且面片数足够时按面片区间并行，
     * 每个工作线程写自己的独立网格，结束后用 mergeRaw 把原始颜色求和值合并——
     * 数值上与单线程逐样本累加完全一致。onPhaseProgress 回报 voxelize/merge 两个阶段的
     * 子进度（可为 null，会被工作线程并发调用）。
     */
    static SparseVoxelGrid secondPass(Path objPath,
                                      ObjPassOneResult passOne,
                                      VoxelSpace voxelSpace,
                                      TextureSampler textureSampler,
                                      double samplesPerVoxel,
                                      int maxTriangleSamples,
                                      int threads,
                                      ModelVoxelizer.PhaseProgress onPhaseProgress) throws IOException {
        long faceCount = passOne.faceCount;
        int workerCount = Math.max(1, Math.min(threads, (int) Math.min(faceCount, 64)));
        if (workerCount <= 1 || faceCount < 200_000L) {
            return secondPassSerial(objPath, passOne, voxelSpace, textureSampler, samplesPerVoxel,
                    maxTriangleSamples, onPhaseProgress);
        }
        return secondPassParallel(objPath, passOne, voxelSpace, textureSampler, samplesPerVoxel,
                maxTriangleSamples, workerCount, onPhaseProgress);
    }

    private static SparseVoxelGrid secondPassSerial(Path objPath,
                                                    ObjPassOneResult passOne,
                                                    VoxelSpace voxelSpace,
                                                    TextureSampler textureSampler,
                                                    double samplesPerVoxel,
                                                    int maxTriangleSamples,
                                                    ModelVoxelizer.PhaseProgress onPhaseProgress) throws IOException {
        SparseVoxelGrid voxelGrid = new SparseVoxelGrid(BlockPalette.DEFAULT);
        int[] ref0 = new int[2];
        int[] ref1 = new int[2];
        int[] ref2 = new int[2];

        long processedFaces = 0L;
        try (BufferedReader reader = Files.newBufferedReader(objPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("f ")) {
                    continue;
                }

                StringTokenizer tokenizer = new StringTokenizer(line);
                tokenizer.nextToken();
                int vertexTokens = tokenizer.countTokens();
                if (vertexTokens >= 3) {
                    String[] tokens = new String[vertexTokens];
                    for (int i = 0; i < vertexTokens; i++) {
                        tokens[i] = tokenizer.nextToken();
                    }

                    for (int i = 1; i < vertexTokens - 1; i++) {
                        parseFaceRef(tokens[0], passOne.vertices.count(), passOne.texCoords.count(), ref0);
                        parseFaceRef(tokens[i], passOne.vertices.count(), passOne.texCoords.count(), ref1);
                        parseFaceRef(tokens[i + 1], passOne.vertices.count(), passOne.texCoords.count(), ref2);

                        rasterizeTriangle(passOne, voxelSpace, textureSampler, samplesPerVoxel, maxTriangleSamples,
                                voxelGrid, ref0, ref1, ref2);
                    }
                }

                processedFaces++;
                if (processedFaces % 100_000L == 0L && onPhaseProgress != null) {
                    onPhaseProgress.accept("voxelize", processedFaces, passOne.faceCount);
                }
                if (processedFaces % 500_000L == 0L) {
                    System.out.printf(Locale.ROOT,
                            "体素化: 面片 %,d / %,d (%,d%%)%n",
                            processedFaces,
                            passOne.faceCount,
                            processedFaces * 100 / Math.max(1, passOne.faceCount));
                }
            }
        }
        if (onPhaseProgress != null) {
            onPhaseProgress.accept("voxelize", processedFaces, passOne.faceCount);
        }

        return voxelGrid;
    }

    private static SparseVoxelGrid secondPassParallel(Path objPath,
                                                      ObjPassOneResult passOne,
                                                      VoxelSpace voxelSpace,
                                                      TextureSampler textureSampler,
                                                      double samplesPerVoxel,
                                                      int maxTriangleSamples,
                                                      int workerCount,
                                                      ModelVoxelizer.PhaseProgress onPhaseProgress) throws IOException {
        long faceCount = passOne.faceCount;
        java.util.concurrent.atomic.AtomicLong processed = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workerCount);
        List<java.util.concurrent.Future<SparseVoxelGrid>> futures = new java.util.ArrayList<>(workerCount);
        // 面片区间按文件顺序切分：相邻面片（共享体素）几乎总落在同一线程，线程间重复体素极少
        for (int w = 0; w < workerCount; w++) {
            final long start = faceCount * w / workerCount;
            final long end = faceCount * (w + 1) / workerCount;
            futures.add(pool.submit(() -> {
                SparseVoxelGrid local = new SparseVoxelGrid(BlockPalette.DEFAULT);
                int[] ref0 = new int[2];
                int[] ref1 = new int[2];
                int[] ref2 = new int[2];
                try (BufferedReader reader = Files.newBufferedReader(objPath, StandardCharsets.UTF_8)) {
                    String line;
                    long faceIndex = 0L;
                    while (faceIndex < end && (line = reader.readLine()) != null) {
                        if (!line.startsWith("f ")) {
                            continue;
                        }
                        if (faceIndex >= start) {
                            StringTokenizer tokenizer = new StringTokenizer(line);
                            tokenizer.nextToken();
                            int vertexTokens = tokenizer.countTokens();
                            if (vertexTokens >= 3) {
                                String[] tokens = new String[vertexTokens];
                                for (int i = 0; i < vertexTokens; i++) {
                                    tokens[i] = tokenizer.nextToken();
                                }

                                for (int i = 1; i < vertexTokens - 1; i++) {
                                    parseFaceRef(tokens[0], passOne.vertices.count(), passOne.texCoords.count(), ref0);
                                    parseFaceRef(tokens[i], passOne.vertices.count(), passOne.texCoords.count(), ref1);
                                    parseFaceRef(tokens[i + 1], passOne.vertices.count(), passOne.texCoords.count(), ref2);

                                    rasterizeTriangle(passOne, voxelSpace, textureSampler, samplesPerVoxel,
                                            maxTriangleSamples, local, ref0, ref1, ref2);
                                }

                                long done = processed.incrementAndGet();
                                if (done % 250_000L == 0L) {
                                    System.out.printf(Locale.ROOT,
                                            "体素化: 面片 %,d / %,d (%,d%%)%n",
                                            done,
                                            faceCount,
                                            done * 100 / Math.max(1, faceCount));
                                    if (onPhaseProgress != null) {
                                        onPhaseProgress.accept("voxelize", done, faceCount);
                                    }
                                }
                            }
                        }
                        faceIndex++;
                    }
                }
                return local;
            }));
        }

        List<SparseVoxelGrid> locals = new java.util.ArrayList<>(workerCount);
        try {
            for (java.util.concurrent.Future<SparseVoxelGrid> future : futures) {
                locals.add(future.get());
            }
            // FutureTask 完成后仍持有返回的网格引用——不清空 futures 的话，下面 locals.set(i, null)
            // 释放不了任何本地网格，12 个网格会在整个合并阶段全程存活（大模型必然 OOM）
            futures.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("体素化被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
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
        if (onPhaseProgress != null) {
            onPhaseProgress.accept("voxelize", faceCount, faceCount);
        }

        SparseVoxelGrid voxelGrid = new SparseVoxelGrid(BlockPalette.DEFAULT);
        long totalUnique = 0L;
        for (SparseVoxelGrid local : locals) {
            totalUnique += local.size();
        }
        // 不做一次性 reserve(totalUnique)：预留会在所有线程本地网格仍存活时就把目标网格
        // 直接扩到最终容量，峰值反而更高。改为 mergeRaw 内 ensureCapacity 增量扩容，
        // 大分配尽量推迟到本地网格已逐个释放之后。
        // 逐个合并并立即释放，控制峰值内存（合并的是原始求和值，颜色与单线程结果完全一致）；
        // 大模型的合并要几秒，按已合并体素数上报百分比
        long mergedVoxels = 0L;
        for (int i = 0; i < locals.size(); i++) {
            SparseVoxelGrid local = locals.get(i);
            voxelGrid.mergeRaw(local);
            mergedVoxels += local.size();
            if (onPhaseProgress != null) {
                onPhaseProgress.accept("merge", mergedVoxels, totalUnique);
            }
            locals.set(i, null);
        }
        return voxelGrid;
    }

    static void parseFaceRef(String token, int vertexCount, int texCount, int[] out) {
        int firstSlash = token.indexOf('/');
        if (firstSlash < 0) {
            out[0] = normalizeIndex(Integer.parseInt(token), vertexCount);
            out[1] = 0;
            return;
        }

        int secondSlash = token.indexOf('/', firstSlash + 1);
        String vertexPart = token.substring(0, firstSlash);
        String texPart;
        if (secondSlash < 0) {
            texPart = token.substring(firstSlash + 1);
        } else {
            texPart = token.substring(firstSlash + 1, secondSlash);
        }

        out[0] = normalizeIndex(Integer.parseInt(vertexPart), vertexCount);
        out[1] = texPart.isEmpty() ? 0 : normalizeIndex(Integer.parseInt(texPart), texCount);
    }

    static int normalizeIndex(int index, int count) {
        if (index > 0) {
            return index;
        }
        return count + index + 1;
    }

    /**
     * 绕模型包围盒中心旋转全部顶点（任意角度、按先 X 后 Y 后 Z 的顺序），返回新顶点数组。
     * 旋转在读取到内存的顶点上做一次 O(n) 变换，之后照常栅格化，天然保持体素连续无镂空。
     */
    static FloatTriples rotateVertices(FloatTriples vertices, Bounds bounds, int rotX, int rotY, int rotZ) {
        double cx = (bounds.minX + bounds.maxX) / 2.0;
        double cy = (bounds.minY + bounds.maxY) / 2.0;
        double cz = (bounds.minZ + bounds.maxZ) / 2.0;
        double cosX = Math.cos(Math.toRadians(rotX));
        double sinX = Math.sin(Math.toRadians(rotX));
        double cosY = Math.cos(Math.toRadians(rotY));
        double sinY = Math.sin(Math.toRadians(rotY));
        double cosZ = Math.cos(Math.toRadians(rotZ));
        double sinZ = Math.sin(Math.toRadians(rotZ));

        FloatTriples rotated = new FloatTriples();
        for (int i = 1; i <= vertices.count(); i++) {
            double x = vertices.getX(i) - cx;
            double y = vertices.getY(i) - cy;
            double z = vertices.getZ(i) - cz;
            // 先绕 X（y,z 平面）
            double y1 = y * cosX - z * sinX;
            double z1 = y * sinX + z * cosX;
            // 再绕 Y（x,z 平面）
            double x2 = x * cosY + z1 * sinY;
            double z2 = -x * sinY + z1 * cosY;
            // 最后绕 Z（x,y 平面）
            double x3 = x2 * cosZ - y1 * sinZ;
            double y3 = x2 * sinZ + y1 * cosZ;
            rotated.add((float) (x3 + cx), (float) (y3 + cy), (float) (z2 + cz));
        }
        return rotated;
    }

    /** 根据顶点数组重新计算轴对齐包围盒（旋转后 bounds 会改变）。 */
    static Bounds boundsOf(FloatTriples vertices) {
        Bounds bounds = new Bounds();
        for (int i = 1; i <= vertices.count(); i++) {
            bounds.include(vertices.getX(i), vertices.getY(i), vertices.getZ(i));
        }
        return bounds;
    }

    static void rasterizeTriangle(ObjPassOneResult passOne,
                                  VoxelSpace voxelSpace,
                                  TextureSampler textureSampler,
                                  double samplesPerVoxel,
                                  int maxTriangleSamples,
                                  SparseVoxelGrid voxelGrid,
                                  int[] ref0,
                                  int[] ref1,
                                  int[] ref2) {
        Vec3 a = voxelSpace.toVoxel(passOne.vertices, ref0[0]);
        Vec3 b = voxelSpace.toVoxel(passOne.vertices, ref1[0]);
        Vec3 c = voxelSpace.toVoxel(passOne.vertices, ref2[0]);

        int ax = ModelMath.clampToInt(Math.round((float) a.x), 0, voxelSpace.width - 1);
        int ay = ModelMath.clampToInt(Math.round((float) a.y), 0, voxelSpace.height - 1);
        int az = ModelMath.clampToInt(Math.round((float) a.z), 0, voxelSpace.length - 1);
        int bx = ModelMath.clampToInt(Math.round((float) b.x), 0, voxelSpace.width - 1);
        int by = ModelMath.clampToInt(Math.round((float) b.y), 0, voxelSpace.height - 1);
        int bz = ModelMath.clampToInt(Math.round((float) b.z), 0, voxelSpace.length - 1);
        int cx = ModelMath.clampToInt(Math.round((float) c.x), 0, voxelSpace.width - 1);
        int cy = ModelMath.clampToInt(Math.round((float) c.y), 0, voxelSpace.height - 1);
        int cz = ModelMath.clampToInt(Math.round((float) c.z), 0, voxelSpace.length - 1);

        if (ax == bx && bx == cx && ay == by && by == cy && az == bz && bz == cz) {
            int color = sampleTriangleColor(passOne, textureSampler, ref0[1], ref1[1], ref2[1],
                    1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0);
            voxelGrid.add(ax, ay, az, color);
            return;
        }

        double maxEdge = Math.max(a.distanceTo(b), Math.max(b.distanceTo(c), c.distanceTo(a)));
        int steps = Math.max(1, (int) Math.ceil(maxEdge * samplesPerVoxel));
        steps = Math.min(steps, maxTriangleSamples);

        for (int i = 0; i <= steps; i++) {
            for (int j = 0; j <= steps - i; j++) {
                double u = i / (double) steps;
                double v = j / (double) steps;
                double w = 1.0 - u - v;
                placeSample(passOne, voxelSpace, textureSampler, voxelGrid, ref0[1], ref1[1], ref2[1], a, b, c, u, v, w);
            }
        }

        placeSample(passOne, voxelSpace, textureSampler, voxelGrid, ref0[1], ref1[1], ref2[1],
                a, b, c, 1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0);
    }

    static void placeSample(ObjPassOneResult passOne,
                            VoxelSpace voxelSpace,
                            TextureSampler textureSampler,
                            SparseVoxelGrid voxelGrid,
                            int uv0,
                            int uv1,
                            int uv2,
                            Vec3 a,
                            Vec3 b,
                            Vec3 c,
                            double u,
                            double v,
                            double w) {
        double x = a.x * w + b.x * u + c.x * v;
        double y = a.y * w + b.y * u + c.y * v;
        double z = a.z * w + b.z * u + c.z * v;

        int color = sampleTriangleColor(passOne, textureSampler, uv0, uv1, uv2, w, u, v);
        if (((color >>> 24) & 0xFF) < 16) {
            return;
        }

        int xi = ModelMath.clampToInt(Math.round((float) x), 0, voxelSpace.width - 1);
        int yi = ModelMath.clampToInt(Math.round((float) y), 0, voxelSpace.height - 1);
        int zi = ModelMath.clampToInt(Math.round((float) z), 0, voxelSpace.length - 1);
        voxelGrid.add(xi, yi, zi, color);
    }

    static int sampleTriangleColor(ObjPassOneResult passOne,
                                   TextureSampler textureSampler,
                                   int uv0,
                                   int uv1,
                                   int uv2,
                                   double w0,
                                   double w1,
                                   double w2) {
        if (uv0 <= 0 || uv1 <= 0 || uv2 <= 0) {
            return 0xFFFFFFFF;
        }
        double u = passOne.texCoords.getU(uv0) * w0 + passOne.texCoords.getU(uv1) * w1 + passOne.texCoords.getU(uv2) * w2;
        double v = passOne.texCoords.getV(uv0) * w0 + passOne.texCoords.getV(uv1) * w1 + passOne.texCoords.getV(uv2) * w2;
        return textureSampler.sample(u, v);
    }
}

final class ObjPassOneResult {
    final FloatTriples vertices;
    final FloatPairs texCoords;
    final Bounds bounds;
    final long faceCount;

    ObjPassOneResult(FloatTriples vertices, FloatPairs texCoords, Bounds bounds, long faceCount) {
        this.vertices = vertices;
        this.texCoords = texCoords;
        this.bounds = bounds;
        this.faceCount = faceCount;
    }
}

final class FloatTriples {
    float[] data = new float[3 * 1024];
    int count = 1;

    FloatTriples() {
        ensure(1);
        data[0] = 0.0f;
        data[1] = 0.0f;
        data[2] = 0.0f;
    }

    void add(float x, float y, float z) {
        ensure(count + 1);
        int offset = count * 3;
        data[offset] = x;
        data[offset + 1] = y;
        data[offset + 2] = z;
        count++;
    }

    float getX(int index) {
        return data[index * 3];
    }

    float getY(int index) {
        return data[index * 3 + 1];
    }

    float getZ(int index) {
        return data[index * 3 + 2];
    }

    int count() {
        return count - 1;
    }

    void ensure(int requiredCount) {
        int requiredLength = requiredCount * 3;
        if (requiredLength <= data.length) {
            return;
        }
        int newLength = data.length;
        while (newLength < requiredLength) {
            newLength *= 2;
        }
        float[] replacement = new float[newLength];
        System.arraycopy(data, 0, replacement, 0, data.length);
        data = replacement;
    }
}

final class FloatPairs {
    float[] data = new float[2 * 1024];
    int count = 1;

    FloatPairs() {
        ensure(1);
        data[0] = 0.5f;
        data[1] = 0.5f;
    }

    void add(float u, float v) {
        ensure(count + 1);
        int offset = count * 2;
        data[offset] = u;
        data[offset + 1] = v;
        count++;
    }

    float getU(int index) {
        return data[index * 2];
    }

    float getV(int index) {
        return data[index * 2 + 1];
    }

    int count() {
        return count - 1;
    }

    void ensure(int requiredCount) {
        int requiredLength = requiredCount * 2;
        if (requiredLength <= data.length) {
            return;
        }
        int newLength = data.length;
        while (newLength < requiredLength) {
            newLength *= 2;
        }
        float[] replacement = new float[newLength];
        System.arraycopy(data, 0, replacement, 0, data.length);
        data = replacement;
    }
}

final class Bounds {
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double minZ = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    double maxZ = Double.NEGATIVE_INFINITY;

    void include(double x, double y, double z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    double sizeX() {
        return maxX - minX;
    }

    double sizeY() {
        return maxY - minY;
    }

    double sizeZ() {
        return maxZ - minZ;
    }
}

final class VoxelSpace {
    final double minX;
    final double minY;
    final double minZ;
    final double scale;
    final int width;
    final int height;
    final int length;

    VoxelSpace(double minX,
               double minY,
               double minZ,
               double scale,
               int width,
               int height,
               int length) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.scale = scale;
        this.width = width;
        this.height = height;
        this.length = length;
    }

    static VoxelSpace fromBounds(Bounds bounds, int targetHeight, int maxDimension) {
        if (bounds.sizeY() <= 0.0) {
            throw new IllegalArgumentException("OBJ 模型高度为 0，无法体素化");
        }

        double scale = (targetHeight - 1.0) / bounds.sizeY();
        if (bounds.sizeX() > 0.0) {
            scale = Math.min(scale, (maxDimension - 1.0) / bounds.sizeX());
        }
        if (bounds.sizeZ() > 0.0) {
            scale = Math.min(scale, (maxDimension - 1.0) / bounds.sizeZ());
        }

        int width = Math.max(1, (int) Math.ceil(bounds.sizeX() * scale) + 1);
        int height = Math.max(1, (int) Math.ceil(bounds.sizeY() * scale) + 1);
        int length = Math.max(1, (int) Math.ceil(bounds.sizeZ() * scale) + 1);

        return new VoxelSpace(bounds.minX, bounds.minY, bounds.minZ, scale, width, height, length);
    }

    Vec3 toVoxel(FloatTriples vertices, int index) {
        double x = (vertices.getX(index) - minX) * scale;
        double y = (vertices.getY(index) - minY) * scale;
        double z = (vertices.getZ(index) - minZ) * scale;
        return new Vec3(x, y, z);
    }
}

final class Vec3 {
    final double x;
    final double y;
    final double z;

    Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    double distanceTo(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

final class TextureSampler {
    final int width;
    final int height;
    /** 一次性把整张贴图拷成 int[]，采样直接下标读取，避免每像素调用 BufferedImage.getRGB（对 2km 大模型是主要热点）。 */
    final int[] pixels;

    TextureSampler(BufferedImage image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
    }

    static TextureSampler load(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("无法读取贴图文件: " + path);
        }
        return new TextureSampler(image);
    }

    int sample(double u, double v) {
        int x = ModelMath.clampToInt((int) Math.round(ModelMath.clamp(u, 0.0, 1.0) * (width - 1)), 0, width - 1);
        int y = ModelMath.clampToInt((int) Math.round((1.0 - ModelMath.clamp(v, 0.0, 1.0)) * (height - 1)), 0, height - 1);
        return pixels[y * width + x];
    }
}

/**
 * 稀疏体素网格，使用开放寻址的原始类型哈希表存储（long key + 4 个 int 累加器）。
 * 相比 HashMap<Long, ColorAccumulator>，单个体素内存从约 110-130 字节降到约 24 字节，
 * 对 2km 级大模型（数百万体素）可把峰值内存压到原来的 1/4-1/5，避免 OOM。
 */
final class SparseVoxelGrid {
    static final int MAX_SLICE_BLOCKS = 80_000_000;
    /** 空槽哨兵：pack 最多只用到 62 位，第 63 位永远不会作为有效 key 出现。 */
    private static final long EMPTY = Long.MIN_VALUE;

    final BlockPalette palette;

    private long[] keys;
    /**
     * 颜色+计数打包压缩（24B/槽 → 12B/槽，内存减半）：
     * 高 24 位 = RGB 运行均值（各 8 位），低 8 位 = 样本计数（饱和于 255）。
     * 均值按增量公式更新 avg += (sample - avg) / count，收敛后与总和/计数的
     * 数学平均误差 ≤1；下游最近色匹配的调色板粒度远大于该误差，结果不变。
     */
    private int[] packed;
    private int size;
    private int capacity;
    private int mask;
    private int threshold;

    /** packed 槽位打包：RGB 均值（各 8 位）放高 24 位。 */
    private static int packColor(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }

    /** packed 槽位打包：RGB 均值 + 计数（低 8 位，饱和于 255）。 */
    private static int packColor(int r, int g, int b, int count) {
        return (r << 24) | (g << 16) | (b << 8) | Math.min(255, count);
    }

    /** 取槽位的 RGB 均值（0xRRGGBB）。 */
    private static int rgbOf(int slotValue) {
        return slotValue >>> 8;
    }

    /** 取槽位的样本计数（未饱和前的上限即 255）。 */
    private static int countOf(int slotValue) {
        return slotValue & 0xFF;
    }

    /**
     * 增量平均更新槽位颜色：新样本合并进现有 (均值, 计数)。
     * count 饱和后（255）新样本权重 1/256 向下取整可能为 0，
     * 用 Math.max(1, ...) 保证仍有变化，避免大计数时完全停止吸收。
     * 注意：必须按 RGB 三通道分别做增量平均（mergeChannel）。旧实现对打包后的
     * 0xRRGGBB 整数整体做 delta/step，除法会产生跨通道借位——R/G 的余数
     * 会移位污染低位的 B 通道，样本一多颜色就越偏越花（8 个样本就偏差到肉眼可见）。
     */
    private static int mergeColor(int slotValue, int sample) {
        int count = countOf(slotValue);
        int step = count >= 255 ? 1 : count + 1;
        int newR = mergeChannel((slotValue >>> 24) & 0xFF, (sample >>> 16) & 0xFF, step);
        int newG = mergeChannel((slotValue >>> 16) & 0xFF, (sample >>> 8) & 0xFF, step);
        int newB = mergeChannel((slotValue >>> 8) & 0xFF, sample & 0xFF, step);
        // RGB 各 8 位移到高 24 位拼上计数，与 packColor(r,g,b,count) 布局一致
        return (newR << 24) | (newG << 16) | (newB << 8) | Math.min(255, count + 1);
    }

    /** 单通道增量平均：avg + (sample-avg)/step，保留旧实现的非零修正语义。 */
    private static int mergeChannel(int avg, int sample, int step) {
        int delta = sample - avg;
        int adjusted = delta / step;
        if (adjusted == 0 && delta != 0) {
            adjusted = delta > 0 ? 1 : -1;
        }
        return avg + adjusted;
    }

    /** 遍历体素：给出原始网格坐标与平均色。 */
    @FunctionalInterface
    interface EntryConsumer {
        void accept(int x, int y, int z, int rgb);
    }

    SparseVoxelGrid(BlockPalette palette) {
        this.palette = palette;
        init(16);
    }

    private void init(int newCapacity) {
        this.capacity = newCapacity;
        this.mask = newCapacity - 1;
        // 负载阈值 9/10：把「所需槽数取整到 2 的幂」的悬崖往后推。4064 高度模型约 4.78 亿体素，
        // 7/8 时需 546M 槽、取整到 2^30（12.9GB）；9/10 只需 531M 槽、落在 2^29（6.4GB），峰值减半。
        // long 运算防止 2^30 级容量乘法溢出；黄金分割哈希 + 线性探测在 0.9 负载下仍可接受
        this.threshold = (int) ((long) newCapacity * 9 / 10);
        this.size = 0;
        this.keys = new long[newCapacity];
        java.util.Arrays.fill(keys, EMPTY);
        this.packed = new int[newCapacity];
    }

    void add(int x, int y, int z, int argb) {
        addPacked(pack(x, y, z), argb);
    }

    void addPacked(long key, int argb) {
        int slot = locateSlot(key);
        if (slot < 0) {
            int index = ~slot;
            keys[index] = key;
            packed[index] = packColor((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, 1);
            if (++size > threshold) {
                resize(capacity * 2);
            }
        } else {
            packed[slot] = mergeColor(packed[slot], argb & 0xFFFFFF);
        }
    }

    boolean containsPacked(long key) {
        return locateSlot(key) >= 0;
    }

    /**
     * 把 other 网格合并进本网格：同 key 的 (均值, 计数) 按样本数加权平均。
     * 多线程体素化后合并线程本地网格用；与单线程逐样本累加的差异仅在整数
     * 舍入（±1），最近色匹配结果不受影响。
     */
    void mergeRaw(SparseVoxelGrid other) {
        if (other.size == 0) {
            return;
        }
        ensureCapacity(size + other.size);
        for (int i = 0; i < other.capacity; i++) {
            long key = other.keys[i];
            if (key == EMPTY) {
                continue;
            }
            int slot = locateSlot(key);
            if (slot < 0) {
                int index = ~slot;
                keys[index] = key;
                packed[index] = other.packed[i];
                if (++size > threshold) {
                    resize(capacity * 2);
                }
            } else {
                packed[slot] = mergeColor(packed[slot], rgbOf(other.packed[i]));
            }
        }
    }

    int rgbPacked(long key) {
        int slot = locateSlot(key);
        return slot < 0 ? -1 : averageRgb(slot);
    }

    int size() {
        return size;
    }

    /** 供预览构建分片并行扫描：哈希槽总数（含空槽）。 */
    int slotCapacity() {
        return capacity;
    }

    /** 供预览构建分片并行扫描：槽位 key（EMPTY 表示空槽），布局为 pack 的 x&lt;42|y&lt;21|z。 */
    long slotKey(int slot) {
        return keys[slot];
    }

    /** 供预览构建分片并行扫描：槽位平均色（RGB 打包进排序键后主循环无需再查哈希表）。 */
    int slotRgb(int slot) {
        return averageRgb(slot);
    }

    /** 供预览构建分片并行扫描：空槽哨兵 key，扫描时据此跳过。 */
    static long emptySlotKey() {
        return EMPTY;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** 遍历体素（原始网格坐标，非世界坐标）。 */
    void forEach(EntryConsumer consumer) {
        for (int i = 0; i < capacity; i++) {
            long key = keys[i];
            if (key == EMPTY) {
                continue;
            }
            int[] position = unpack(key);
            consumer.accept(position[0], position[1], position[2], averageRgb(i));
        }
    }

    /** 遍历世界坐标体素（自动还原 WORLD_SHIFT 平移，复用数组避免每次迭代分配）。 */
    void forEachWorld(EntryConsumer consumer) {
        int[] position = new int[3];
        for (int i = 0; i < capacity; i++) {
            long key = keys[i];
            if (key == EMPTY) {
                continue;
            }
            unpackWorldInto(key, position);
            consumer.accept(position[0], position[1], position[2], averageRgb(i));
        }
    }

    /** 世界坐标包围盒 {minX,maxX,minY,maxY,minZ,maxZ}。 */
    int[] unionWorldBounds() {
        if (size == 0) {
            throw new IllegalArgumentException("没有可写入的体素，无法计算包围盒");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int[] position = new int[3];
        for (int i = 0; i < capacity; i++) {
            long key = keys[i];
            if (key == EMPTY) {
                continue;
            }
            unpackWorldInto(key, position);
            minX = Math.min(minX, position[0]);
            minY = Math.min(minY, position[1]);
            minZ = Math.min(minZ, position[2]);
            maxX = Math.max(maxX, position[0]);
            maxY = Math.max(maxY, position[1]);
            maxZ = Math.max(maxZ, position[2]);
        }
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    /** 把 other 网格平移 dx/dy/dz 后合并进本网格（目标网格使用世界坐标编码）。 */
    void merge(SparseVoxelGrid other, int dx, int dy, int dz) {
        if (other.size == 0) {
            return;
        }
        ensureCapacity(size + other.size);
        int[] position = new int[3];
        for (int i = 0; i < other.capacity; i++) {
            long key = other.keys[i];
            if (key == EMPTY) {
                continue;
            }
            position[0] = (int) (key >>> 42);
            position[1] = (int) ((key >>> 21) & 0x1FFFFF);
            position[2] = (int) (key & 0x1FFFFF);
            addPacked(packWorld(position[0] + dx, position[1] + dy, position[2] + dz), other.averageRgb(i));
        }
    }

    /**
     * 把 other 网格平移后合并进本网格（目标网格使用普通 pack 坐标编码，平移后坐标必须落在 [0, 2^20) 内）。
     * 用于导出 Litematica 文件：先把放置好的世界坐标平移到以最小角为原点，再交给
     * toSchematicParts 这类按网格内非负坐标切块的逻辑。
     */
    void mergeLocal(SparseVoxelGrid other, int dx, int dy, int dz) {
        if (other.size == 0) {
            return;
        }
        ensureCapacity(size + other.size);
        int[] position = new int[3];
        for (int i = 0; i < other.capacity; i++) {
            long key = other.keys[i];
            if (key == EMPTY) {
                continue;
            }
            position[0] = (int) (key >>> 42) + dx;
            position[1] = (int) ((key >>> 21) & 0x1FFFFF) + dy;
            position[2] = (int) (key & 0x1FFFFF) + dz;
            if (position[0] < 0 || position[1] < 0 || position[2] < 0
                    || position[0] >= 1 << 20 || position[1] >= 1 << 20 || position[2] >= 1 << 20) {
                throw new IllegalArgumentException("导出坐标超出可编码范围（模型放置位置离原点过远）");
            }
            addPacked(pack(position[0], position[1], position[2]), other.averageRgb(i));
        }
    }
/** 预留容量，避免后续 add/merge 反复翻倍扩容（扩容瞬间新旧数组并存会抬高峰值内存）。 */
    void reserve(int required) {
        ensureCapacity(required);
    }

    // ===== 磁盘缓存序列化 =====
    // 缓存的是去杂后的最终网格：下游（合并/建筑提取/调色板生成）只消费每体素的平均 RGB，
    // 原始颜色和与样本数不再需要。落盘格式：keys 全量数组 + 每槽平均 RGB（空槽写 0），
    // 恢复时直接还原数组结构（counts 置 1、分量置平均值），无 rehash，读取为纯顺序 IO。

    private static final int DISK_FORMAT_VERSION = 1;

    /** 写入磁盘缓存：全量写 keys（含空槽哨兵），颜色写每槽平均 RGB，约 12 字节/槽。 */
    void writeTo(java.io.OutputStream rawOut) throws IOException {
        java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(rawOut, 1 << 20));
        try {
            out.writeInt(DISK_FORMAT_VERSION);
            out.writeInt(capacity);
            out.writeInt(size);
            for (int i = 0; i < capacity; i++) {
                out.writeLong(keys[i]);
            }
            for (int i = 0; i < capacity; i++) {
                out.writeInt(keys[i] == EMPTY ? 0 : averageRgb(i));
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    /** 从磁盘缓存恢复网格；文件不存在返回 null，内容损坏抛 IOException（调用方按未命中处理）。 */
    static SparseVoxelGrid readFrom(java.io.InputStream rawIn, BlockPalette palette) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(rawIn, 1 << 20));
        try {
            int version = in.readInt();
            if (version != DISK_FORMAT_VERSION) {
                throw new IOException("体素缓存版本不匹配: " + version);
            }
            int fileCapacity = in.readInt();
            int fileSize = in.readInt();
            if (fileCapacity < 16 || (fileCapacity & (fileCapacity - 1)) != 0
                    || fileSize < 0 || fileSize > (long) fileCapacity * 9 / 10 + 1) {
                throw new IOException("体素缓存容量字段非法");
            }
            SparseVoxelGrid grid = new SparseVoxelGrid(palette);
            grid.init(fileCapacity);
            grid.size = 0;
            int occupied = 0;
            for (int i = 0; i < fileCapacity; i++) {
                long key = in.readLong();
                grid.keys[i] = key;
                if (key != EMPTY) {
                    occupied++;
                }
            }
            for (int i = 0; i < fileCapacity; i++) {
                int rgb = in.readInt();
                if (grid.keys[i] == EMPTY) {
                    continue;
                }
                grid.packed[i] = packColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 1);
            }
            if (occupied != fileSize) {
                throw new IOException("体素缓存条目数不一致");
            }
            grid.size = fileSize;
            return grid;
        } finally {
            in.close();
        }
    }

    private void ensureCapacity(int required) {
        if (required <= threshold) {
            return;
        }
        // 按 9/10 负载阈值预估容量。必须 long 运算：required 达 4.3 亿时 int 乘法已溢出成负数，
        // 旧实现的容量会被算成 1（此前恰好被后续逐次扩容掩盖）。上限 2^30 防止位移/数组溢出
        long target = (long) required * 10 / 9 + 1;
        resize(target > (1 << 30) ? (1 << 30) : nextPowerOfTwo((int) target));
    }

    /** 绕 Y 轴旋转体素网格，quarterTurns 取 0/1/2/3（对应 0/90/180/270 度），旋转后整体平移回非负坐标。 */
    void rotateY(int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0 || size == 0) {
            return;
        }

        int minRotatedX = Integer.MAX_VALUE;
        int minRotatedZ = Integer.MAX_VALUE;
        for (int i = 0; i < capacity; i++) {
            long key = keys[i];
            if (key == EMPTY) {
                continue;
            }
            int[] position = unpack(key);
            minRotatedX = Math.min(minRotatedX, rotateX(position[0], position[2], turns));
            minRotatedZ = Math.min(minRotatedZ, rotateZ(position[0], position[2], turns));
        }
        int offsetX = -minRotatedX;
        int offsetZ = -minRotatedZ;

        long[] oldKeys = keys;
        int[] oldPacked = packed;
        int oldCapacity = capacity;

        // 旋转是坐标双射，重建不会产生碰撞；按 9/10 负载预分配避免重建中途扩容
        // （long 运算防溢出；478M 体素时 size*2 会取整到 2^30 的 12.9GB，按负载只需 2^29）
        long target = (long) size * 10 / 9 + 1;
        init(Math.max(16, target > (1 << 30) ? (1 << 30) : nextPowerOfTwo((int) target)));
        for (int i = 0; i < oldCapacity; i++) {
            long key = oldKeys[i];
            if (key == EMPTY) {
                continue;
            }
            int[] position = unpack(key);
            int rotatedX = rotateX(position[0], position[2], turns) + offsetX;
            int rotatedZ = rotateZ(position[0], position[2], turns) + offsetZ;
            // 旋转是纯平移映射，均值/计数原样搬运即可
            int slot = locateSlot(pack(rotatedX, position[1], rotatedZ));
            keys[~slot] = pack(rotatedX, position[1], rotatedZ);
            packed[~slot] = oldPacked[i];
            size++;
        }
    }

    private static int rotateX(int x, int z, int turns) {
        return switch (turns) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotateZ(int x, int z, int turns) {
        return switch (turns) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    private int averageRgb(int index) {
        return rgbOf(packed[index]);
    }

    /**
     * 去杂：迭代多数滤波，把与周围方块不一致的孤立体素替换为最常见邻居方块的调色板代表色。
     * strength=1 只清理 6 邻域全不一致的单点；strength=2 同时清理 1-2 格小簇（建筑模型推荐）。
     * 替换后的颜色直接取目标方块的调色板色，保证后续最近匹配仍落到同一方块。
     */
    void denoise(int strength) {
        denoise(strength, null);
    }

    /**
     * 带子进度的去杂：大模型 3 轮迭代每轮要遍历全部体素，按已处理体素数上报百分比。
     * 每轮迭代多线程并行：线程只读旧数组（keys/reds/...）、把结果写进 newXXX 数组的
     * 互不重叠区间，槽位间无任何依赖，无需合并步骤。
     * 邻居查找用 packed key 的位域加减（与 unpack→pack 在正常范围内等价；网格维度
     * 远小于 21 位上限，越界借位产生的 key 不会命中任何槽位）。
     */
    void denoise(int strength, ModelVoxelizer.PhaseProgress onPhaseProgress) {
        denoise(strength, onPhaseProgress, false);
    }

    /** forceSerial 为测试钩子：true 时强制单线程路径，用于验证并行路径与串行路径 bit-exact 等价。 */
    void denoise(int strength, ModelVoxelizer.PhaseProgress onPhaseProgress, boolean forceSerial) {
        if (strength <= 0 || size == 0) {
            return;
        }
        final int sameThreshold = strength >= 2 ? 1 : 0;
        final long[] neighborOffsets = {
                1L << 42, -(1L << 42),
                1L << 21, -(1L << 21),
                1L, -1L
        };
        long totalWork = (long) size * 3;
        long reportInterval = Math.max(1L, size / 50L);
        // blockColor 内部的 overrides() 每次 synchronized+复制 HashMap，热点循环里
        // 既是锁竞争又是分配风暴；取一次快照，denoise 期间 palette 不会再被修改
        final Map<String, Integer> overridesSnapshot = palette.overrides();
        final java.util.concurrent.atomic.AtomicLong processed =
                new java.util.concurrent.atomic.AtomicLong();

        int parallelism = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        if (forceSerial || size < 200_000L) {
            parallelism = 1;
        }
        java.util.concurrent.ExecutorService pool = null;
        if (parallelism > 1) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        }
        try {
            for (int iter = 0; iter < 3; iter++) {
                final int[] newPacked = packed.clone();

                int changes;
                if (pool == null) {
                    changes = denoiseRange(0, capacity, sameThreshold, neighborOffsets,
                            newPacked,
                            overridesSnapshot, processed, reportInterval, totalWork, onPhaseProgress);
                } else {
                    int stride = (capacity + parallelism - 1) / parallelism;
                    java.util.List<java.util.concurrent.Future<Integer>> futures =
                            new java.util.ArrayList<>(parallelism);
                    for (int t = 0; t < parallelism; t++) {
                        final int start = t * stride;
                        final int end = Math.min(start + stride, capacity);
                        if (start >= end) {
                            continue;
                        }
                        futures.add(pool.submit(() -> denoiseRange(start, end, sameThreshold, neighborOffsets,
                                newPacked,
                                overridesSnapshot, processed, reportInterval, totalWork, onPhaseProgress)));
                    }
                    changes = 0;
                    try {
                        for (java.util.concurrent.Future<Integer> future : futures) {
                            changes += future.get();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("去杂被中断", e);
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new RuntimeException("去杂失败", e.getCause());
                    }
                }
                if (changes == 0) {
                    break;
                }
                packed = newPacked;
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        if (onPhaseProgress != null) {
            onPhaseProgress.accept("denoise", totalWork, totalWork);
        }
    }

    /**
     * 去杂一个槽位区间（可被多线程并发调用，区间互不重叠）。
     * 邻居方块计数用固定 6 元素小数组线性扫描：邻居最多 6 个，
     * 语义与原 LinkedHashMap 完全一致（最多者胜、平手取先扫描到的），且零分配。
     */
    private int denoiseRange(int start, int end, int sameThreshold, long[] neighborOffsets,
                             int[] newPacked,
                             Map<String, Integer> overridesSnapshot,
                             java.util.concurrent.atomic.AtomicLong processed,
                             long reportInterval, long totalWork,
                             ModelVoxelizer.PhaseProgress onPhaseProgress) {
        final String[] names = new String[6];
        final int[] tallies = new int[6];
        int changes = 0;
        long sinceReport = 0L;
        for (int i = start; i < end; i++) {
            long key = keys[i];
            if (key == EMPTY) {
                continue;
            }
            sinceReport++;
            if (onPhaseProgress != null && sinceReport >= reportInterval) {
                long done = processed.addAndGet(sinceReport);
                onPhaseProgress.accept("denoise", Math.min(done, totalWork), totalWork);
                sinceReport = 0L;
            }

            String ownBlock = palette.closestBlock(averageRgb(i));
            int distinct = 0;
            int same = 0;
            int occupied = 0;
            for (long offset : neighborOffsets) {
                int slot = locateSlot(key + offset);
                if (slot < 0) {
                    continue;
                }
                occupied++;
                String neighborBlock = palette.closestBlock(averageRgb(slot));
                if (neighborBlock.equals(ownBlock)) {
                    same++;
                }
                int found = -1;
                for (int k = 0; k < distinct; k++) {
                    if (names[k].equals(neighborBlock)) {
                        found = k;
                        break;
                    }
                }
                if (found < 0) {
                    names[distinct] = neighborBlock;
                    tallies[distinct] = 1;
                    distinct++;
                } else {
                    tallies[found]++;
                }
            }
            if (occupied == 0 || same > sameThreshold) {
                continue;
            }
            int bestIndex = -1;
            int bestCount = 0;
            for (int k = 0; k < distinct; k++) {
                if (tallies[k] > bestCount) {
                    bestCount = tallies[k];
                    bestIndex = k;
                }
            }
            if (bestIndex < 0 || names[bestIndex].equals(ownBlock)) {
                continue;
            }
            int representative = palette.blockColor(names[bestIndex], overridesSnapshot);
            if (representative < 0) {
                continue;
            }
            // 颜色被整体替换为代表色，历史均值/计数不再适用；重置为 (代表色, 1)
            newPacked[i] = packColor((representative >> 16) & 0xFF,
                    (representative >> 8) & 0xFF, representative & 0xFF, 1);
            changes++;
        }
        if (onPhaseProgress != null && sinceReport > 0L) {
            long done = processed.addAndGet(sinceReport);
            onPhaseProgress.accept("denoise", Math.min(done, totalWork), totalWork);
        }
        return changes;
    }

    private int locateSlot(long key) {
        int slot = hash(key);
        while (true) {
            long existing = keys[slot];
            if (existing == EMPTY) {
                return ~slot;
            }
            if (existing == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private int hash(long key) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ (mixed >>> 32)) & mask;
    }

    private void resize(int newCapacity) {
        long[] oldKeys = keys;
        int[] oldPacked = packed;
        int oldCapacity = capacity;

        init(newCapacity);
        for (int i = 0; i < oldCapacity; i++) {
            long key = oldKeys[i];
            if (key == EMPTY) {
                continue;
            }
            int slot = locateSlot(key);
            int index = ~slot;
            keys[index] = key;
            packed[index] = oldPacked[i];
            size++;
        }
    }

    private static int nextPowerOfTwo(int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    List<SchematicPart> toSchematicParts(VoxelSpace voxelSpace,
                                         String outputBaseName,
                                         int requestedSliceHeight,
                                         int requestedRegionSize) {
        return toSchematicParts(voxelSpace, outputBaseName, requestedSliceHeight, requestedRegionSize, java.util.Map.of());
    }

    /**
     * 导出 Litematica 分块。explicitBlocks 允许注入非调色板方块（如表面光源 light[level=15]），
     * 键为 packWorld 编码的网格坐标（可为负），值为完整方块状态；越界位置会被忽略。
     */
    List<SchematicPart> toSchematicParts(VoxelSpace voxelSpace,
                                         String outputBaseName,
                                         int requestedSliceHeight,
                                         int requestedRegionSize,
                                         Map<Long, String> explicitBlocks) {
        PaletteBuilder globalPalette = new PaletteBuilder();
        int regionWidth = Math.max(1, Math.min(requestedRegionSize, voxelSpace.width));
        int regionLength = Math.max(1, Math.min(requestedRegionSize, voxelSpace.length));
        int maxSafeSliceHeight = Math.max(1, MAX_SLICE_BLOCKS / Math.max(1, regionWidth * regionLength));
        int regionHeight = Math.max(1, Math.min(Math.min(requestedSliceHeight, requestedRegionSize), maxSafeSliceHeight));

        Map<ChunkKey, Map<Long, Integer>> chunkMaps = new HashMap<>();
        forEach((x, y, z, rgb) -> {
            String blockState = palette.closestBlock(rgb);
            int globalPaletteIndex = globalPalette.indexOf(blockState);

            ChunkKey chunkKey = new ChunkKey(x / regionWidth, y / regionHeight, z / regionLength);
            int localX = x % regionWidth;
            int localY = y % regionHeight;
            int localZ = z % regionLength;
            long localKey = pack(localX, localY, localZ);

            chunkMaps.computeIfAbsent(chunkKey, ignored -> new HashMap<>()).put(localKey, globalPaletteIndex);
        });
        if (!explicitBlocks.isEmpty()) {
            int[] origin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            forEach((x, y, z, rgb) -> {
                origin[0] = Math.min(origin[0], x);
                origin[1] = Math.min(origin[1], y);
                origin[2] = Math.min(origin[2], z);
            });
            int[] originScan = new int[3];
            for (long key : explicitBlocks.keySet()) {
                unpackWorldInto(key, originScan);
                origin[0] = Math.min(origin[0], originScan[0]);
                origin[1] = Math.min(origin[1], originScan[1]);
                origin[2] = Math.min(origin[2], originScan[2]);
            }
            int[] position = new int[3];
            for (Map.Entry<Long, String> entry : explicitBlocks.entrySet()) {
                unpackWorldInto(entry.getKey(), position);
                int x = position[0];
                int y = position[1];
                int z = position[2];
                if (x < origin[0] || y < origin[1] || z < origin[2]
                        || x >= origin[0] + voxelSpace.width
                        || y >= origin[1] + voxelSpace.height
                        || z >= origin[2] + voxelSpace.length) {
                    continue;
                }
                String blockState = entry.getValue();
                int globalPaletteIndex = globalPalette.indexOf(blockState);
                ChunkKey chunkKey = new ChunkKey(
                        Math.floorDiv(x, regionWidth),
                        Math.floorDiv(y, regionHeight),
                        Math.floorDiv(z, regionLength));
                int localX = Math.floorMod(x, regionWidth);
                int localY = Math.floorMod(y, regionHeight);
                int localZ = Math.floorMod(z, regionLength);
                chunkMaps.computeIfAbsent(chunkKey, ignored -> new HashMap<>())
                        .put(pack(localX, localY, localZ), globalPaletteIndex);
            }
        }

        List<String> globalStates = globalPalette.statesByIndex();
        List<ChunkKey> sortedKeys = new java.util.ArrayList<>(chunkMaps.keySet());
        sortedKeys.sort(Comparator
                .comparingInt(ChunkKey::chunkY)
                .thenComparingInt(ChunkKey::chunkX)
                .thenComparingInt(ChunkKey::chunkZ));

        List<SchematicPart> parts = new java.util.ArrayList<>();
        for (int partIndex = 0; partIndex < sortedKeys.size(); partIndex++) {
            ChunkKey chunkKey = sortedKeys.get(partIndex);
            Map<Long, Integer> chunkMap = chunkMaps.get(chunkKey);
            int startX = chunkKey.chunkX() * regionWidth;
            int startY = chunkKey.chunkY() * regionHeight;
            int startZ = chunkKey.chunkZ() * regionLength;
            int partWidth = Math.min(regionWidth, voxelSpace.width - startX);
            int partHeight = Math.min(regionHeight, voxelSpace.height - startY);
            int partLength = Math.min(regionLength, voxelSpace.length - startZ);
            long volumeLong = (long) partWidth * partHeight * partLength;
            if (volumeLong > Integer.MAX_VALUE) {
                throw new IllegalStateException("稀疏分块体积仍然过大，请降低 --region-size 或 --slice-height");
            }

            PaletteBuilder localPalette = new PaletteBuilder();
            byte[] blockData = new byte[(int) volumeLong];
            for (Map.Entry<Long, Integer> voxelEntry : chunkMap.entrySet()) {
                int[] position = unpack(voxelEntry.getKey());
                String blockState = globalStates.get(voxelEntry.getValue());
                int localPaletteIndex = localPalette.indexOf(blockState);
                int flatIndex = position[0]
                        + position[2] * partWidth
                        + position[1] * partWidth * partLength;
                blockData[flatIndex] = (byte) localPaletteIndex;
            }

            String partName = sortedKeys.size() == 1
                    ? outputBaseName
                    : outputBaseName + String.format(Locale.ROOT, "_region_%04d", partIndex + 1);
            Schematic schematic = new Schematic(
                    partWidth,
                    partHeight,
                    partLength,
                    localPalette.palette(),
                    blockData,
                    chunkMap.size(),
                    partName,
                    new int[]{startX, startY, startZ});
            parts.add(new SchematicPart(schematic));
        }

        return parts;
    }

    static long pack(int x, int y, int z) {
        return (((long) x) << 42) | (((long) y) << 21) | (long) z;
    }

    static int[] unpack(long packed) {
        int x = (int) ((packed >>> 42) & 0x1FFFFF);
        int y = (int) ((packed >>> 21) & 0x1FFFFF);
        int z = (int) (packed & 0x1FFFFF);
        return new int[]{x, y, z};
    }

    /** 世界坐标平移量：pack 的 21 位无符号位域无法直接表达负数，统一平移使 [-2^20, 2^20) 内坐标可编码。 */
    static final int WORLD_SHIFT = 1 << 20;

    /** 编码绝对世界坐标（可含负数），与 unpackWorld 配套使用。 */
    static long packWorld(int x, int y, int z) {
        return pack(x + WORLD_SHIFT, y + WORLD_SHIFT, z + WORLD_SHIFT);
    }

    /** 解码 packWorld 编码的世界坐标。 */
    static int[] unpackWorld(long packed) {
        int[] position = new int[3];
        unpackWorldInto(packed, position);
        return position;
    }

    /** 解码 packWorld 编码的世界坐标（无分配版本，写入 out）。 */
    static void unpackWorldInto(long packed, int[] out) {
        out[0] = (int) (packed >>> 42) - WORLD_SHIFT;
        out[1] = (int) ((packed >>> 21) & 0x1FFFFF) - WORLD_SHIFT;
        out[2] = (int) (packed & 0x1FFFFF) - WORLD_SHIFT;
    }
}

record ChunkKey(int chunkX, int chunkY, int chunkZ) {
}








