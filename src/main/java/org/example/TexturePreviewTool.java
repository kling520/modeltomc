package org.example;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导出真实方块材质预览图，便于确认当前读取到的是 Minecraft 原版贴图。
 */
final class TexturePreviewTool {
    private static final List<String> SAMPLE_BLOCKS = List.of(
            "minecraft:stone",
            "minecraft:grass_block",
            "minecraft:oak_log",
            "minecraft:oak_planks",
            "minecraft:crafting_table",
            "minecraft:iron_door",
            "minecraft:bookshelf",
            "minecraft:bricks",
            "minecraft:diamond_block",
            "minecraft:obsidian",
            "minecraft:sea_lantern",
            "minecraft:tnt"
    );
    private static final int TILE_SIZE = 64;
    private static final int LABEL_HEIGHT = 38;
    private static final int TILE_GAP = 14;
    private static final int PADDING = 20;
    private static final int COLUMNS = 4;

    private TexturePreviewTool() {
    }

    static PreviewResult generate() throws IOException {
        Path outputDir = Paths.get("target", "preview").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        List<PreviewTile> tiles = new ArrayList<>();
        for (String block : SAMPLE_BLOCKS) {
            MinecraftBlockTextures.Texture texture = MinecraftBlockTextures.textureFor(block);
            BufferedImage image = null;
            if (texture != null) {
                image = ImageIO.read(new ByteArrayInputStream(texture.bytes()));
            }
            tiles.add(new PreviewTile(block, image));
        }

        Path imagePath = outputDir.resolve("texture-preview.png");
        Path reportPath = outputDir.resolve("texture-preview.txt");
        ImageIO.write(render(tiles), "png", imagePath.toFile());
        Files.writeString(reportPath, buildReport(tiles, imagePath), StandardCharsets.UTF_8);
        return new PreviewResult(imagePath, reportPath);
    }

    private static BufferedImage render(List<PreviewTile> tiles) {
        int rows = Math.max(1, (tiles.size() + COLUMNS - 1) / COLUMNS);
        int width = PADDING * 2 + COLUMNS * TILE_SIZE + (COLUMNS - 1) * TILE_GAP;
        int height = PADDING * 2 + rows * (TILE_SIZE + LABEL_HEIGHT) + (rows - 1) * TILE_GAP;
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(13, 17, 24));
            g.fillRect(0, 0, width, height);

            Font titleFont = new Font("Microsoft YaHei", Font.BOLD, 12);
            Font bodyFont = new Font("Consolas", Font.PLAIN, 11);

            for (int index = 0; index < tiles.size(); index++) {
                PreviewTile tile = tiles.get(index);
                int row = index / COLUMNS;
                int col = index % COLUMNS;
                int x = PADDING + col * (TILE_SIZE + TILE_GAP);
                int y = PADDING + row * (TILE_SIZE + LABEL_HEIGHT + TILE_GAP);

                g.setColor(new Color(32, 38, 48));
                g.fillRoundRect(x - 4, y - 4, TILE_SIZE + 8, TILE_SIZE + 8, 8, 8);

                if (tile.image() != null) {
                    g.drawImage(tile.image(), x, y, TILE_SIZE, TILE_SIZE, null);
                } else {
                    g.setColor(new Color(130, 40, 40));
                    g.fillRect(x, y, TILE_SIZE, TILE_SIZE);
                    g.setColor(Color.WHITE);
                    g.drawLine(x + 10, y + 10, x + TILE_SIZE - 10, y + TILE_SIZE - 10);
                    g.drawLine(x + TILE_SIZE - 10, y + 10, x + 10, y + TILE_SIZE - 10);
                }

                g.setColor(Color.WHITE);
                g.setFont(titleFont);
                g.drawString(shortName(tile.block()), x, y + TILE_SIZE + 14);
                g.setColor(new Color(151, 163, 184));
                g.setFont(bodyFont);
                g.drawString(tile.block(), x, y + TILE_SIZE + 28);
            }
            return canvas;
        } finally {
            g.dispose();
        }
    }

    private static String buildReport(List<PreviewTile> tiles, Path imagePath) {
        StringBuilder builder = new StringBuilder();
        builder.append("textureSource=").append(MinecraftBlockTextures.sourcePath()).append(System.lineSeparator());
        builder.append("textureAvailable=").append(MinecraftBlockTextures.available()).append(System.lineSeparator());
        builder.append("previewImage=").append(imagePath).append(System.lineSeparator());
        builder.append(System.lineSeparator());
        for (PreviewTile tile : tiles) {
            builder.append(tile.block())
                    .append('=')
                    .append(tile.image() == null ? "missing" : "ok")
                    .append(tile.image() == null ? "" : ", colors=" + uniqueColorCount(tile.image()) + ", size="
                            + tile.image().getWidth() + "x" + tile.image().getHeight())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static int uniqueColorCount(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors.size();
    }

    private static String shortName(String block) {
        int colon = block.indexOf(':');
        return colon >= 0 ? block.substring(colon + 1) : block;
    }

    record PreviewResult(Path imagePath, Path reportPath) {
    }

    private record PreviewTile(String block, BufferedImage image) {
    }
}
