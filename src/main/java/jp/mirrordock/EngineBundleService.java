package jp.mirrordock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Imports a user-selected CI bundle. Checksums detect corruption, not publisher identity. */
public final class EngineBundleService {
    static final String UPSTREAM = "f0cd2c363786af7d3ddf3fc60d523c8ed8c3b967";
    private static final long MAX_TOTAL = 1_500_000_000L, MAX_FILE = 250_000_000L;
    private static final int MAX_ENTRIES = 5000;
    public record Installation(Path executable, String upstreamCommit) {}

    public Installation install(Path archive, Path userDirectory) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IOException("エンジンZIPが見つかりません。");
        Path engines = userDirectory.resolve("engine/versions");
        Files.createDirectories(engines);
        Path staging = Files.createTempDirectory(engines, ".import-");
        boolean installed = false;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Map<String,ZipEntry> entries = entries(zip);
            byte[] manifest = readSmall(zip, entries.get("files.sha256"), 2_000_000);
            Map<String,String> hashes = parseManifest(new String(manifest, StandardCharsets.UTF_8));
            Set<String> required = new HashSet<>(entries.keySet());
            required.remove("files.sha256");
            if (!required.equals(hashes.keySet())) throw new IOException("ZIPのファイル一覧とチェックサム一覧が一致しません。");
            Properties metadata = new Properties();
            try (Reader reader = new StringReader(new String(readSmall(zip, entries.get("mirrordock-engine.properties"), 8192), StandardCharsets.UTF_8))) { metadata.load(reader); }
            if (!"1".equals(metadata.getProperty("format")) || !"windows-x64".equals(metadata.getProperty("platform")) ||
                !UPSTREAM.equals(metadata.getProperty("upstream.commit")) || !"bin/uxplay.exe".equals(metadata.getProperty("executable")))
                throw new IOException("対応していないエンジンZIPです。MirrorDockのWindows CI成果物を選択してください。");
            for (String path : List.of("bin/uxplay.exe", "mirrordock-engine.properties"))
                if (!entries.containsKey(path)) throw new IOException("エンジンZIPに必要なファイルがありません。");
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            for (var item : entries.entrySet()) {
                String name = item.getKey();
                Path destination = staging.resolve(name).normalize();
                if (!destination.startsWith(staging)) throw new IOException("ZIP内のパスが不正です。");
                Files.createDirectories(destination.getParent());
                MessageDigest digest = sha256(); long size = 0;
                try (InputStream in = zip.getInputStream(item.getValue()); OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        size += count; total += count;
                        if (size > MAX_FILE || total > MAX_TOTAL) throw new IOException("エンジンZIPの展開サイズが上限を超えています。");
                        digest.update(buffer, 0, count); out.write(buffer, 0, count);
                    }
                }
                if (!name.equals("files.sha256") && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(hashes.get(name)))
                    throw new IOException("ファイルのチェックサムが一致しません。CI成果物を再ダウンロードしてください。");
            }
            Path destination = engines.resolve(UUID.randomUUID().toString());
            try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staging, destination); }
            installed = true;
            return new Installation(destination.resolve("bin/uxplay.exe"), UPSTREAM);
        } catch (IllegalArgumentException e) { throw new IOException("エンジンZIPの形式が不正です。", e); }
        finally {
            if (!installed) {
                try (var files = Files.walk(staging)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
    private static Map<String,ZipEntry> entries(ZipFile zip) throws IOException {
        Map<String,ZipEntry> result = new LinkedHashMap<>(); Set<String> canonical = new HashSet<>();
        var all = zip.entries(); int count=0; long advertised=0;
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            if (++count > MAX_ENTRIES) throw new IOException("ZIP内のファイル数が上限を超えています。");
            String name = entry.getName();
            if (entry.isDirectory()) { validatePath(name.substring(0,name.length()-1)); continue; }
            validatePath(name);
            if (!canonical.add(name.toLowerCase(Locale.ROOT))) throw new IOException("ZIPに重複したパスがあります。");
            if (entry.getSize() > MAX_FILE || (advertised += Math.max(0,entry.getSize())) > MAX_TOTAL) throw new IOException("ZIPサイズが上限を超えています。");
            result.put(name,entry);
        }
        return result;
    }
    static void validatePath(String name) throws IOException {
        if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/") || name.chars().anyMatch(c -> c < 32))
            throw new IOException("ZIPに安全でないパスが含まれています。");
        for (String part : name.split("/",-1)) {
            String stem = part.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") ||
                part.matches(".*[<>\"|?*].*") || stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))
                throw new IOException("ZIPに安全でないWindowsパスが含まれています。");
        }
    }
    private static Map<String,String> parseManifest(String value) throws IOException {
        Map<String,String> result = new LinkedHashMap<>();
        for (String line : value.lines().toList()) {
            if (line.isBlank()) continue;
            if (line.length()<67 || !line.substring(0,64).matches("[0-9a-fA-F]{64}") || !line.substring(64,66).equals("  ")) throw new IOException("チェックサム一覧が不正です。");
            String name=line.substring(66); validatePath(name);
            if (name.equals("files.sha256") || result.put(name,line.substring(0,64))!=null) throw new IOException("チェックサム一覧に重複があります。");
        }
        return result;
    }
    private static byte[] readSmall(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        if (entry==null) throw new IOException("MirrorDock用エンジンZIPではありません。Actionsの外側のZIPを展開してから内側のエンジンZIPを選んでください。");
        try (InputStream in=zip.getInputStream(entry)) {byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("エンジン情報が大きすぎます。");return bytes;}
    }
    private static MessageDigest sha256() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
