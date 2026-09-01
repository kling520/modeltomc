package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 磁盘缓存基础设施：把体素网格与预览结果按内容签名落地到工作目录 cache/ 下，
 * 重启进程或内存缓存被挤出后，相同参数的模型/预览可以直接复用，不再重复体素化与序列化。
 * 文件名 = 缓存 key 的 SHA-256 十六进制；写入走临时文件 + 原子重命名，损坏文件按未命中处理。
 */
final class DiskCache {
    /** 缓存根目录（相对工作目录，与 model2mc-palettes 同级）。 */
    private static final Path ROOT = Paths.get("cache");
    /** 体素网格缓存目录与磁盘预算。 */
    static final long VOXEL_BUDGET_BYTES = 24L * 1024 * 1024 * 1024;
    /** 预览结果 JSON 缓存目录与磁盘预算。 */
    static final long PREVIEW_BUDGET_BYTES = 8L * 1024 * 1024 * 1024;

    private DiskCache() {
    }

    /** 体素缓存文件路径（key 为 ModelVoxelizer.signature 的完整签名）。 */
    static Path voxelFile(String key) {
        return ROOT.resolve("voxel").resolve(hash(key) + ".bin");
    }

    /** 预览结果缓存文件路径。 */
    static Path previewFile(String key) {
        return ROOT.resolve("preview").resolve(hash(key) + ".bin");
    }

    /** key → SHA-256 十六进制文件名；摘要不可用时退化为 key 自身的哈希（仍保证确定性）。 */
    static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode()) + "-" + key.length();
        }
    }

    interface OutputStreamConsumer {
        void accept(OutputStream out) throws IOException;
    }

    /** 原子写入：先写临时文件再重命名，中断不会留下半个文件被当成有效缓存。 */
    static void writeAtomically(Path target, OutputStreamConsumer writer) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "tmp-", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.accept(out);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 整文件读入（预览 JSON 缓存用）；文件不存在或读取失败返回 null，按未命中处理。 */
    static byte[] readBytes(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 磁盘预算清理：超出预算时按最后修改时间从旧到新删除，直到总量回到预算内。
     * 只扫目录元数据，文件数在几十到几百级别，开销毫秒级。
     */
    static void enforceBudget(Path dir, long maxBytes) {
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            List<Path> files = new ArrayList<>();
            long total = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    try {
                        total += Files.size(file);
                        files.add(file);
                    } catch (IOException ignored) {
                        // 单个文件读不出大小就跳过，不影响其余清理
                    }
                }
            }
            if (total <= maxBytes) {
                return;
            }
            files.sort(Comparator.comparingLong(DiskCache::lastModifiedOf));
            for (Path file : files) {
                if (total <= maxBytes) {
                    break;
                }
                try {
                    long size = Files.size(file);
                    Files.deleteIfExists(file);
                    total -= size;
                } catch (IOException ignored) {
                    // 删不掉（被占用等）就留给下次清理
                }
            }
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    private static long lastModifiedOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
