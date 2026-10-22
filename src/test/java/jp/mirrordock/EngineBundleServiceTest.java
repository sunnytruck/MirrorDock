package jp.mirrordock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineBundleServiceTest {
    @TempDir Path temp;
    private Map<String,byte[]> payload() {
        Map<String,byte[]> files=new LinkedHashMap<>();
        files.put("mirrordock-engine.properties",("format=1\nplatform=windows-x64\nupstream.commit="+EngineBundleService.UPSTREAM+"\nexecutable=bin/uxplay.exe\n").getBytes(StandardCharsets.UTF_8));
        files.put("bin/uxplay.exe",new byte[]{77,90,1,2});
        files.put("lib/gstreamer-1.0/plugin.dll",new byte[]{1});
        files.put("share/licenses/UxPlay/LICENSE","test license".getBytes(StandardCharsets.UTF_8));
        return files;
    }
    private Path zip(Map<String,byte[]> files, boolean tamper) throws Exception {
        StringBuilder hashes=new StringBuilder();
        for(var entry:files.entrySet()) hashes.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))).append("  ").append(entry.getKey()).append('\n');
        Path zip=temp.resolve(UUID.randomUUID()+".zip");
        try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(var entry:files.entrySet()) {out.putNextEntry(new ZipEntry(entry.getKey()));out.write(tamper&&entry.getKey().equals("bin/uxplay.exe")?new byte[]{0}:entry.getValue());out.closeEntry();}
            out.putNextEntry(new ZipEntry("files.sha256"));out.write(hashes.toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
        }
        return zip;
    }
    @Test void importsWithoutExecutingAndPreservesExistingInstallation() throws Exception {
        Path root=temp.resolve("user-data");Path archive=zip(payload(),false);
        var first=new EngineBundleService().install(archive,root);
        var second=new EngineBundleService().install(archive,root);
        assertNotEquals(first.executable(),second.executable());
        assertArrayEquals(new byte[]{77,90,1,2},Files.readAllBytes(first.executable()));
        assertTrue(Files.isRegularFile(second.executable()));
    }
    @Test void corruptedFileRejectedAndStagingRemoved() throws Exception {
        Path root=temp.resolve("user-data");
        assertThrows(IOException.class,()->new EngineBundleService().install(zip(payload(),true),root));
        try(var paths=Files.list(root.resolve("engine/versions"))){assertEquals(0,paths.count());}
    }
    @Test void traversalAndWindowsDangerousPathsRejected() {
        for(String path:List.of("../outside","/absolute","C:/windows/file","bin\\evil","bin/../evil","bin/NUL.dll","bin/CON","bin/file.","bin/file ","bin/file:ads","bin//file"))
            assertThrows(IOException.class,()->EngineBundleService.validatePath(path),path);
    }
    @Test void caseInsensitiveDuplicateRejected() throws Exception {
        var files=payload();files.put("BIN/UXPLAY.EXE",new byte[]{7});
        assertThrows(IOException.class,()->new EngineBundleService().install(zip(files,false),temp.resolve("data")));
    }
    @Test void unexpectedPlatformRejected() throws Exception {
        var files=payload();files.put("mirrordock-engine.properties","format=1\nplatform=linux\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->new EngineBundleService().install(zip(files,false),temp.resolve("data")));
    }
    @Test void outerActionsArchiveRejectedWithUsefulMessage() throws Exception {
        Path zip=temp.resolve("outer.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("MirrorDock-Engine-Windows.zip"));out.write(new byte[]{1});out.closeEntry();}
        IOException error=assertThrows(IOException.class,()->new EngineBundleService().install(zip,temp.resolve("data")));
        assertTrue(error.getMessage().contains("内側"));
    }
    @Test void unsignedForeignMetadataCannotSelectDifferentExecutable() throws Exception {
        var files=payload();files.put("mirrordock-engine.properties",("format=1\nplatform=windows-x64\nupstream.commit="+EngineBundleService.UPSTREAM+"\nexecutable=evil.exe\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->new EngineBundleService().install(zip(files,false),temp.resolve("data")));
    }
}
