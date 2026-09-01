package org.example;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class Schematic {
    final int width;
    final int height;
    final int length;
    final LinkedHashMap<String, Integer> palette;
    final byte[] blockData;
    final long nonAirBlocks;
    final String name;
    final int[] offset;

    Schematic(int width,
              int height,
              int length,
              LinkedHashMap<String, Integer> palette,
              byte[] blockData,
              long nonAirBlocks,
              String name,
              int[] offset) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blockData = blockData;
        this.nonAirBlocks = nonAirBlocks;
        this.name = name;
        this.offset = offset;
    }
}

record SchematicPart(Schematic schematic) {
}

final class LitematicWriter {
    private static final int TAG_END = 0;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int DATA_VERSION = 3700;
    private static final int LITEMATIC_VERSION = 6;
    private static final int LITEMATIC_SUB_VERSION = 1;

    private LitematicWriter() {
    }

    static void write(Path outputPath,
                      List<SchematicPart> parts,
                      String name,
                      int width,
                      int height,
                      int length) throws IOException {
        long now = Instant.now().toEpochMilli();
        long totalBlocks = 0L;
        long totalVolume = 0L;
        for (SchematicPart part : parts) {
            totalBlocks += part.schematic().nonAirBlocks;
            totalVolume += (long) part.schematic().width * part.schematic().height * part.schematic().length;
        }

        try (OutputStream file = Files.newOutputStream(outputPath);
             BufferedOutputStream buffered = new BufferedOutputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(buffered);
             DataOutputStream out = new DataOutputStream(gzip)) {

            writeTagHeader(out, TAG_COMPOUND, "");
            writeInt(out, "Version", LITEMATIC_VERSION);
            writeInt(out, "SubVersion", LITEMATIC_SUB_VERSION);
            writeInt(out, "MinecraftDataVersion", DATA_VERSION);
            writeTagHeader(out, TAG_COMPOUND, "Metadata");
            writeString(out, "Name", name);
            writeString(out, "Author", "model2mc");
            writeString(out, "Description", "Converted from OBJ by model2mc");
            writeString(out, "Software", "model2mc");
            writeInt(out, "RegionCount", parts.size());
            writeLong(out, "TimeCreated", now);
            writeLong(out, "TimeModified", now);
            writeLong(out, "TotalBlocks", totalBlocks);
            writeLong(out, "TotalVolume", totalVolume);
            writeIntArray(out, "PreviewImageData", new int[0]);
            writeSizeCompound(out, "EnclosingSize", width, height, length);
            writeEnd(out);

            writeTagHeader(out, TAG_COMPOUND, "Regions");
            for (SchematicPart part : parts) {
                Schematic region = part.schematic();
                writeTagHeader(out, TAG_COMPOUND, region.name);
                writePositionCompound(out, "Position", region.offset[0], region.offset[1], region.offset[2]);
                writeSizeCompound(out, "Size", region.width, region.height, region.length);
                writePaletteList(out, region.palette);
                writeListHeader(out, "Entities", TAG_COMPOUND, 0);
                writeListHeader(out, "TileEntities", TAG_COMPOUND, 0);
                writeListHeader(out, "PendingBlockTicks", TAG_COMPOUND, 0);
                writeListHeader(out, "PendingFluidTicks", TAG_COMPOUND, 0);
                writeLongArray(out, "BlockStates", packBlockStates(region.blockData, region.palette.size()));
                writeEnd(out);
            }
            writeEnd(out);
            writeEnd(out);
        }
    }

    static long[] packBlockStates(byte[] blockData, int paletteSize) {
        int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        int longCount = Math.max(1, (int) Math.ceil((double) blockData.length * bitsPerBlock / 64.0));
        long[] packed = new long[longCount];
        long mask = (1L << bitsPerBlock) - 1L;

        for (int index = 0; index < blockData.length; index++) {
            long value = Byte.toUnsignedLong(blockData[index]) & mask;
            long bitOffset = (long) index * bitsPerBlock;
            int startLongIndex = (int) (bitOffset >>> 6);
            int startBit = (int) (bitOffset & 63L);

            packed[startLongIndex] |= value << startBit;
            if (startBit + bitsPerBlock > 64) {
                packed[startLongIndex + 1] |= value >>> (64 - startBit);
            }
        }

        return packed;
    }

    static void writePaletteList(DataOutputStream out, LinkedHashMap<String, Integer> palette) throws IOException {
        writeListHeader(out, "BlockStatePalette", TAG_COMPOUND, palette.size());
        for (String blockState : palette.keySet()) {
            ParsedBlockState parsed = ParsedBlockState.parse(blockState);
            writeString(out, "Name", parsed.name());
            if (!parsed.properties().isEmpty()) {
                writeTagHeader(out, TAG_COMPOUND, "Properties");
                for (Map.Entry<String, String> property : parsed.properties().entrySet()) {
                    writeString(out, property.getKey(), property.getValue());
                }
                writeEnd(out);
            }
            writeEnd(out);
        }
    }

    static void writePositionCompound(DataOutputStream out, String name, int x, int y, int z) throws IOException {
        writeTagHeader(out, TAG_COMPOUND, name);
        writeInt(out, "x", x);
        writeInt(out, "y", y);
        writeInt(out, "z", z);
        writeEnd(out);
    }

    static void writeSizeCompound(DataOutputStream out, String name, int x, int y, int z) throws IOException {
        writePositionCompound(out, name, x, y, z);
    }

    static void writeTagHeader(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }

    static void writeEnd(DataOutputStream out) throws IOException {
        out.writeByte(TAG_END);
    }

    static void writeInt(DataOutputStream out, String name, int value) throws IOException {
        writeTagHeader(out, TAG_INT, name);
        out.writeInt(value);
    }

    static void writeLong(DataOutputStream out, String name, long value) throws IOException {
        writeTagHeader(out, TAG_LONG, name);
        out.writeLong(value);
    }

    static void writeString(DataOutputStream out, String name, String value) throws IOException {
        writeTagHeader(out, TAG_STRING, name);
        out.writeUTF(value);
    }

    static void writeIntArray(DataOutputStream out, String name, int[] values) throws IOException {
        writeTagHeader(out, TAG_INT_ARRAY, name);
        out.writeInt(values.length);
        for (int value : values) {
            out.writeInt(value);
        }
    }

    static void writeLongArray(DataOutputStream out, String name, long[] values) throws IOException {
        writeTagHeader(out, TAG_LONG_ARRAY, name);
        out.writeInt(values.length);
        for (long value : values) {
            out.writeLong(value);
        }
    }

    static void writeListHeader(DataOutputStream out, String name, int elementType, int length) throws IOException {
        writeTagHeader(out, TAG_LIST, name);
        out.writeByte(elementType);
        out.writeInt(length);
    }
}
