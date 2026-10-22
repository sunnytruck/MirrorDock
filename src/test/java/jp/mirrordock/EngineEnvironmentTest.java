package jp.mirrordock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class EngineEnvironmentTest {
    @TempDir Path temp;
    @Test void portablePathsAndPrivateStartupConfiguration() throws Exception {
        Path prefix=temp.resolve("portable");Files.createDirectories(prefix.resolve("bin"));Files.createDirectories(prefix.resolve("lib/gstreamer-1.0"));Files.createDirectories(prefix.resolve("libexec/gstreamer-1.0"));Files.write(prefix.resolve("libexec/gstreamer-1.0/gst-plugin-scanner.exe"),new byte[]{0});
        ProcessBuilder builder=new ProcessBuilder("not-executed");
        EngineEnvironment.configure(builder,prefix.resolve("bin/uxplay.exe"),temp.resolve("user"));
        assertEquals(prefix.resolve("lib/gstreamer-1.0").toString(),builder.environment().get("GST_PLUGIN_SYSTEM_PATH_1_0"));
        assertTrue(Files.readString(Path.of(builder.environment().get("UXPLAYRC"))).startsWith("# MirrorDock"));
        String key=builder.environment().keySet().stream().filter(k->k.equalsIgnoreCase("PATH")).findFirst().orElseThrow();
        assertTrue(builder.environment().get(key).startsWith(prefix.resolve("bin").toString()));
    }
}
