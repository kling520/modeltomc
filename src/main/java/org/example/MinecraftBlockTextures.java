package org.example;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 resources/textures/block 读取原版材质，并生成 3D 立体方块图标。
 */
final class MinecraftBlockTextures {
    private static final Path SOURCE_TEXTURE_DIR = Paths.get("src", "main", "resources", "textures", "block")
            .toAbsolutePath().normalize();
    private static final Path COMPILED_TEXTURE_DIR = Paths.get("target", "classes", "textures", "block")
            .toAbsolutePath().normalize();
    private static final int ICON_SIZE = 96;

    private static final ConcurrentHashMap<String, String> RESOLVED_NAME_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BufferedImage> SOURCE_IMAGE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, byte[]> ICON_BYTES_CACHE = new ConcurrentHashMap<>();

    private MinecraftBlockTextures() {
    }

    static boolean available() {
        return Files.isDirectory(SOURCE_TEXTURE_DIR)
                || Files.isDirectory(COMPILED_TEXTURE_DIR)
                || MinecraftBlockTextures.class.getResource("/textures/block") != null;
    }

    static String sourcePath() {
        if (Files.isDirectory(SOURCE_TEXTURE_DIR)) {
            return SOURCE_TEXTURE_DIR.toString();
        }
        if (Files.isDirectory(COMPILED_TEXTURE_DIR)) {
            return COMPILED_TEXTURE_DIR.toString();
        }
        return "classpath:/textures/block";
    }

    static List<String> buildBlockOptions(Collection<String> extraBlocks) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        for (PaletteEntry entry : BlockPalette.DEFAULT.entries) {
            options.add(entry.blockState());
        }
        if (extraBlocks != null) {
            for (String block : extraBlocks) {
                if (block != null && !block.isBlank()) {
                    options.add(ParsedBlockState.parse(block.trim()).name());
                }
            }
        }
        return new ArrayList<>(options);
    }

    static Texture textureFor(String blockState) throws IOException {
        String name = ParsedBlockState.parse(blockState == null ? "" : blockState.trim()).name();
        if (name.isBlank() || !available()) {
            return null;
        }

        byte[] cached = ICON_BYTES_CACHE.get(name);
        if (cached != null) {
            return new Texture(cached, "image/png");
        }

        BufferedImage top = sourceTextureFor(name);
        if (top == null) {
            return null;
        }

        BufferedImage left = chooseLeftTexture(name, top);
        BufferedImage right = chooseRightTexture(name, left);
        top = applyBlockTint(name, top);
        left = applyBlockTint(name, left);
        right = applyBlockTint(name, right);
        byte[] iconBytes = renderBlockIcon(top, left, right);
        byte[] previous = ICON_BYTES_CACHE.putIfAbsent(name, iconBytes);
        return new Texture(previous == null ? iconBytes : previous, "image/png");
    }

    static Texture flatTextureFor(String blockState) throws IOException {
        String name = ParsedBlockState.parse(blockState == null ? "" : blockState.trim()).name();
        if (name.isBlank() || !available()) {
            return null;
        }

        BufferedImage source = sourceTextureFor(name);
        if (source == null) {
            return null;
        }

        BufferedImage tinted = applyBlockTint(name, source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(tinted, "png", output);
        return new Texture(output.toByteArray(), "image/png");
    }

    private static BufferedImage chooseLeftTexture(String blockName, BufferedImage fallback) throws IOException {
        BufferedImage side = sourceTextureFor(candidateName(blockName, SideKind.LEFT));
        return side != null ? side : fallback;
    }

    private static BufferedImage chooseRightTexture(String blockName, BufferedImage fallback) throws IOException {
        BufferedImage side = sourceTextureFor(candidateName(blockName, SideKind.RIGHT));
        return side != null ? side : fallback;
    }

    private static String candidateName(String blockName, SideKind kind) {
        return switch (kind) {
            case LEFT -> switch (blockName) {
                case "minecraft:grass_block" -> "minecraft:grass_block_side";
                case "minecraft:mycelium" -> "minecraft:mycelium_side";
                case "minecraft:podzol" -> "minecraft:podzol_side";
                case "minecraft:smooth_quartz" -> "minecraft:quartz_block_side";
                case "minecraft:dried_kelp_block" -> "minecraft:dried_kelp_side";
                case "minecraft:snow_block" -> "minecraft:snow";
                case "minecraft:crafting_table" -> "minecraft:crafting_table_front";
                case "minecraft:furnace" -> "minecraft:furnace_front";
                case "minecraft:smoker" -> "minecraft:smoker_front";
                case "minecraft:blast_furnace" -> "minecraft:blast_furnace_front";
                case "minecraft:melon" -> "minecraft:melon_side";
                case "minecraft:pumpkin" -> "minecraft:pumpkin_side";
                case "minecraft:jack_o_lantern" -> "minecraft:jack_o_lantern";
                default -> blockName;
            };
            case RIGHT -> switch (blockName) {
                case "minecraft:smooth_quartz" -> "minecraft:quartz_block_side";
                case "minecraft:dried_kelp_block" -> "minecraft:dried_kelp_side";
                case "minecraft:snow_block" -> "minecraft:snow";
                case "minecraft:crafting_table" -> "minecraft:crafting_table_side";
                case "minecraft:furnace" -> "minecraft:furnace_side";
                case "minecraft:smoker" -> "minecraft:smoker_side";
                case "minecraft:blast_furnace" -> "minecraft:blast_furnace_side";
                default -> candidateName(blockName, SideKind.LEFT);
            };
        };
    }

    private static BufferedImage sourceTextureFor(String blockState) throws IOException {
        String name = ParsedBlockState.parse(blockState).name();
        if (name.isBlank()) {
            return null;
        }

        String resolvedName = RESOLVED_NAME_CACHE.computeIfAbsent(name, MinecraftBlockTextures::resolveTextureName);
        if (resolvedName.isEmpty()) {
            return null;
        }

        BufferedImage cached = SOURCE_IMAGE_CACHE.get(resolvedName);
        if (cached != null) {
            return cached;
        }

        BufferedImage image = loadTextureImage(resolvedName);
        if (image == null) {
            return null;
        }
        BufferedImage previous = SOURCE_IMAGE_CACHE.putIfAbsent(resolvedName, image);
        return previous == null ? image : previous;
    }

    private static String resolveTextureName(String blockState) {
        String localName = blockState.contains(":") ? blockState.substring(blockState.indexOf(':') + 1) : blockState;
        for (String candidate : textureCandidates(localName)) {
            if (textureExists(candidate + ".png")) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean textureExists(String fileName) {
        return Files.isRegularFile(SOURCE_TEXTURE_DIR.resolve(fileName))
                || Files.isRegularFile(COMPILED_TEXTURE_DIR.resolve(fileName))
                || MinecraftBlockTextures.class.getResource("/textures/block/" + fileName) != null;
    }

    private static BufferedImage loadTextureImage(String textureName) throws IOException {
        String fileName = textureName + ".png";
        Path sourceFile = SOURCE_TEXTURE_DIR.resolve(fileName);
        if (Files.isRegularFile(sourceFile)) {
            return ImageIO.read(sourceFile.toFile());
        }

        Path compiledFile = COMPILED_TEXTURE_DIR.resolve(fileName);
        if (Files.isRegularFile(compiledFile)) {
            return ImageIO.read(compiledFile.toFile());
        }

        try (InputStream input = MinecraftBlockTextures.class.getResourceAsStream("/textures/block/" + fileName)) {
            return input == null ? null : ImageIO.read(input);
        }
    }

    private static List<String> textureCandidates(String blockName) {
        String name = blockName.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(name);
        switch (name) {
            case "grass_block" -> candidates.add("grass_block_top");
            case "mycelium" -> candidates.add("mycelium_top");
            case "podzol" -> candidates.add("podzol_top");
            case "hay_block" -> candidates.add("hay_block_top");
            case "smooth_quartz" -> {
                candidates.add("quartz_block_top");
                candidates.add("quartz_block_side");
            }
            case "dried_kelp_block" -> {
                candidates.add("dried_kelp_top");
                candidates.add("dried_kelp_side");
            }
            case "snow_block" -> candidates.add("snow");
            case "crafting_table" -> candidates.add("crafting_table_top");
            case "furnace" -> {
                candidates.add("furnace_front");
                candidates.add("furnace_top");
            }
            case "smoker" -> {
                candidates.add("smoker_front");
                candidates.add("smoker_top");
            }
            case "blast_furnace" -> {
                candidates.add("blast_furnace_front");
                candidates.add("blast_furnace_top");
            }
            case "melon" -> candidates.add("melon_side");
            case "pumpkin" -> candidates.add("pumpkin_side");
            case "carved_pumpkin" -> candidates.add("carved_pumpkin");
            case "jack_o_lantern" -> candidates.add("jack_o_lantern");
            case "iron_door" -> candidates.add("iron_door_bottom");
            case "oak_door" -> candidates.add("oak_door_bottom");
            case "spruce_door" -> candidates.add("spruce_door_bottom");
            case "birch_door" -> candidates.add("birch_door_bottom");
            case "jungle_door" -> candidates.add("jungle_door_bottom");
            case "acacia_door" -> candidates.add("acacia_door_bottom");
            case "dark_oak_door" -> candidates.add("dark_oak_door_bottom");
            case "mangrove_door" -> candidates.add("mangrove_door_bottom");
            case "cherry_door" -> candidates.add("cherry_door_bottom");
            default -> {
            }
        }
        candidates.add(name + "_top");
        candidates.add(name + "_side");
        candidates.add(name + "_front");
        candidates.add(name + "_front_on");
        candidates.add(name + "_bottom");
        candidates.add(name + "_end");
        candidates.add(name + "_on");
        candidates.add(name + "_lit");
        return new ArrayList<>(candidates);
    }

    private static byte[] renderBlockIcon(BufferedImage top, BufferedImage left, BufferedImage right) throws IOException {
        BufferedImage canvas = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        rasterFace(canvas, tint(right, 0.72f), 80, 24, -32, 16, 0, 32);
        rasterFace(canvas, tint(left, 0.86f), 16, 24, 32, 16, 0, 32);
        rasterFace(canvas, top, 48, 8, 32, 16, -32, 16);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", output);
        return output.toByteArray();
    }

    private static void rasterFace(BufferedImage canvas,
                                   BufferedImage texture,
                                   double originX,
                                   double originY,
                                   double uX,
                                   double uY,
                                   double vX,
                                   double vY) {
        double det = uX * vY - uY * vX;
        if (Math.abs(det) < 1e-6) {
            return;
        }

        double x0 = originX;
        double y0 = originY;
        double x1 = originX + uX;
        double y1 = originY + uY;
        double x2 = originX + uX + vX;
        double y2 = originY + uY + vY;
        double x3 = originX + vX;
        double y3 = originY + vY;

        int minX = Math.max(0, (int) Math.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3))));
        int maxX = Math.min(canvas.getWidth() - 1, (int) Math.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3))));
        int minY = Math.max(0, (int) Math.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3))));
        int maxY = Math.min(canvas.getHeight() - 1, (int) Math.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3))));

        int textureWidth = texture.getWidth();
        int textureHeight = texture.getHeight();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double relativeX = x + 0.5 - originX;
                double relativeY = y + 0.5 - originY;
                double u = (relativeX * vY - relativeY * vX) / det;
                double v = (relativeY * uX - relativeX * uY) / det;
                if (u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) {
                    continue;
                }

                int sourceX = Math.max(0, Math.min(textureWidth - 1, (int) (u * textureWidth)));
                int sourceY = Math.max(0, Math.min(textureHeight - 1, (int) (v * textureHeight)));
                int argb = texture.getRGB(sourceX, sourceY);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                canvas.setRGB(x, y, argb);
            }
        }
    }

    private static BufferedImage applyBlockTint(String blockName, BufferedImage image) {
        if (image == null || !needsBiomeTint(blockName)) {
            return image;
        }
        int tintColor = BlockPalette.DEFAULT.blockColor(blockName);
        if (tintColor < 0) {
            tintColor = BlockPalette.DEFAULT.previewColor(blockName);
        }
        return multiplyTint(image, tintColor);
    }

    private static boolean needsBiomeTint(String blockName) {
        String name = ParsedBlockState.parse(blockName).name();
        return name.contains("leaves");
    }

    private static BufferedImage multiplyTint(BufferedImage image, int tintColor) {
        float redFactor = ((tintColor >> 16) & 0xFF) / 255f;
        float greenFactor = ((tintColor >> 8) & 0xFF) / 255f;
        float blueFactor = (tintColor & 0xFF) / 255f;
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int red = Math.min(255, Math.round(((argb >> 16) & 0xFF) * redFactor));
                int green = Math.min(255, Math.round(((argb >> 8) & 0xFF) * greenFactor));
                int blue = Math.min(255, Math.round((argb & 0xFF) * blueFactor));
                copy.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return copy;
    }

    private static BufferedImage tint(BufferedImage image, float factor) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return new RescaleOp(
                new float[]{factor, factor, factor, 1f},
                new float[]{0f, 0f, 0f, 0f},
                null
        ).filter(copy, null);
    }

    record Texture(byte[] bytes, String contentType) {
    }

    private enum SideKind {
        LEFT, RIGHT
    }
}
