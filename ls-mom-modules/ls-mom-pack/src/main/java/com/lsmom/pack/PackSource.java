package com.lsmom.pack;

import com.lsmom.core.error.LsBizException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pack 源：目录或 zip 中的全部文件（相对路径 → 内容）。
 * zip 根目录必须有 {@code MANIFEST.sha256}，每行 {@code <sha256>  <相对路径>}（与 sha256sum 输出格式相同），
 * 读取时逐个核对，多出、缺少或内容不符的文件都会被拒。
 */
public final class PackSource {

    public static final String CHECKSUMS = "MANIFEST.sha256";
    public static final String MANIFEST = "pack.yaml";
    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;

    private final Map<String, byte[]> files;

    private PackSource(Map<String, byte[]> files) {
        this.files = files;
    }

    public static PackSource of(Map<String, byte[]> files) {
        Map<String, byte[]> m = new TreeMap<>();
        files.forEach((k, v) -> {
            if (!k.equals(CHECKSUMS)) m.put(normalize(k), v);
        });
        if (!m.containsKey(MANIFEST)) throw invalid("缺少 " + MANIFEST);
        return new PackSource(m);
    }

    public static PackSource fromDirectory(Path dir) {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.equals(CHECKSUMS) || rel.startsWith(".")) continue;
                m.put(rel, Files.readAllBytes(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return of(m);
    }

    public static PackSource fromZip(byte[] zip) {
        if (zip == null || zip.length == 0) throw invalid("Pack 文件为空");
        if (zip.length > MAX_ZIP_BYTES) throw invalid("Pack 文件超过 50MB");
        Map<String, byte[]> m = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = normalize(e.getName());
                if (m.put(name, in.readAllBytes()) != null) throw invalid("zip 中文件重复：" + name);
            }
        } catch (IOException e) {
            throw invalid("不是有效的 zip 文件：" + e.getMessage());
        }
        byte[] sums = m.remove(CHECKSUMS);
        if (sums == null) throw invalid("缺少校验清单 " + CHECKSUMS);
        Map<String, String> declared = new LinkedHashMap<>();
        for (String line : new String(sums, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")) throw invalid("校验清单格式不正确：" + line);
            declared.put(normalize(parts[1]), parts[0]);
        }
        List<String> problems = new ArrayList<>();
        for (var e : m.entrySet()) {
            String want = declared.get(e.getKey());
            if (want == null) problems.add("清单未列出：" + e.getKey());
            else if (!want.equals(sha256(e.getValue()))) problems.add("校验和不符：" + e.getKey());
        }
        for (String k : declared.keySet()) if (!m.containsKey(k)) problems.add("清单列出但文件缺失：" + k);
        if (!problems.isEmpty()) throw new LsBizException("PACK_CHECKSUM_MISMATCH", "Pack 完整性校验失败：" + problems.get(0), Map.of("problems", problems));
        return of(m);
    }

    /** 打包为 zip（文件按路径排序、时间戳固定），同样内容得到同样的字节与 sha256。 */
    public byte[] toZip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            StringBuilder sums = new StringBuilder();
            for (var e : files.entrySet()) {
                sums.append(sha256(e.getValue())).append("  ").append(e.getKey()).append('\n');
                put(z, e.getKey(), e.getValue());
            }
            put(z, CHECKSUMS, sums.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(315532800000L);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /** 内容摘要：校验清单（排序后的"哈希 路径"）的 sha256，与打包时间无关。 */
    public String digest() {
        StringBuilder sb = new StringBuilder();
        files.forEach((k, v) -> sb.append(sha256(v)).append("  ").append(k).append('\n'));
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, byte[]> files() {
        return java.util.Collections.unmodifiableMap(files);
    }

    public byte[] file(String path) {
        return files.get(path);
    }

    public String text(String path) {
        byte[] b = files.get(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** 目录下的文件（按路径排序），如 {@code list("views/", ".json")}。 */
    public List<String> list(String dir, String... suffixes) {
        return files.keySet().stream().filter(k -> k.startsWith(dir) && k.indexOf('/', dir.length()) < 0)
            .filter(k -> suffixes.length == 0 || Stream.of(suffixes).anyMatch(k::endsWith)).toList();
    }

    /** 替换（或新增）若干文件，返回新的 Pack 源。 */
    public PackSource with(Map<String, byte[]> changes) {
        Map<String, byte[]> m = new TreeMap<>(files);
        changes.forEach((k, v) -> m.put(normalize(k), v));
        return new PackSource(m);
    }

    private static String normalize(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.startsWith("/") || n.contains("../") || n.equals("..") || n.contains("\0")) throw invalid("非法文件路径：" + name);
        return n;
    }

    static LsBizException invalid(String msg) {
        return new LsBizException("PACK_INVALID", msg, Map.of());
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }
}
