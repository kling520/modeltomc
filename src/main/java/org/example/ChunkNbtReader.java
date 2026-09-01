package org.example;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import de.pauleff.jnbt.api.ICompoundTag;
import de.pauleff.jnbt.formats.binary.NBTReader;

/**
 * 轻量 Anvil (.mca) chunk 读取器：只读 region 位置表（8KB），
 * 需要某个 chunk 时才按偏移随机读取其压缩数据并解析 NBT 根。
 * 相比 jmcx 的 Region 全量解析（缓存换入换出都要重读整个 region），
 * 内存占用与 IO 都大幅降低，且能安全地多线程并发读取不同 region。
 */
final class ChunkNbtReader {
    /** 单个 chunk 数据上限（长度字节含压缩类型头），防止损坏条目撑爆内存。 */
    private static final int MAX_CHUNK_BYTES = 5 * 1024 * 1024;

    private final int regionX;
    private final int regionZ;
    private final RandomAccessFile file;
    /** 位置表：条目高 24 位为扇区偏移（×4096），低 8 位为扇区数。 */
    private final int[] locationTable = new int[1024];

    ChunkNbtReader(Path regionFile, int regionX, int regionZ) throws IOException {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.file = new RandomAccessFile(regionFile.toFile(), "r");
        readLocationTable();
    }

    private void readLocationTable() throws IOException {
        byte[] header = new byte[4096];
        file.seek(0L);
        file.readFully(header);
        for (int i = 0; i < 1024; i++) {
            int offset = i * 4;
            locationTable[i] = ((header[offset] & 0xFF) << 24)
                    | ((header[offset + 1] & 0xFF) << 16)
                    | ((header[offset + 2] & 0xFF) << 8)
                    | (header[offset + 3] & 0xFF);
        }
    }

    int regionX() {
        return regionX;
    }

    int regionZ() {
        return regionZ;
    }

    void close() throws IOException {
        file.close();
    }

    /** 读取指定世界坐标 chunk 的 NBT 根（1.18+ 根含 sections 列表）；chunk 不存在或损坏时返回 null。 */
    ICompoundTag readChunkRoot(int chunkX, int chunkZ) {
        int localIndex = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32;
        int entry = locationTable[localIndex];
        int sectorOffset = entry >>> 8;
        if (sectorOffset == 0) {
            return null;
        }
        synchronized (file) {
            try {
                file.seek(sectorOffset * 4096L);
                int length = file.readInt();
                if (length <= 1 || length > MAX_CHUNK_BYTES) {
                    return null;
                }
                int compression = file.readUnsignedByte();
                byte[] compressed = new byte[length - 1];
                file.readFully(compressed);
                byte[] nbtData = decompress(compression, compressed);
                if (nbtData == null) {
                    return null;
                }
                NBTReader reader = new NBTReader(new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbtData)));
                try {
                    return reader.read();
                } finally {
                    reader.close();
                }
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
    }

    private static byte[] decompress(int compression, byte[] compressed) throws IOException {
        return switch (compression) {
            case 2 -> inflate(compressed);
            case 1 -> gunzip(compressed);
            case 3 -> compressed;
            default -> null;
        };
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] buffer = new byte[Math.max(1024, compressed.length * 4)];
            int written = 0;
            while (!inflater.finished()) {
                if (written + 1024 > buffer.length) {
                    buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2);
                }
                int count = inflater.inflate(buffer, written, buffer.length - written);
                if (count == 0 && inflater.needsInput()) {
                    return null;
                }
                written += count;
            }
            return java.util.Arrays.copyOf(buffer, written);
        } catch (java.util.zip.DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }
}
