package jp.mirrordock;

import java.io.IOException;
import java.nio.file.*;

/** Configures only a child process; never changes the user's PATH or UxPlay configuration. */
final class EngineEnvironment {
    private EngineEnvironment() {}
    static void configure(ProcessBuilder builder, Path executable, Path userDirectory) throws IOException {
        Files.createDirectories(userDirectory);
        Path empty = userDirectory.resolve("uxplay-empty.conf");
        Files.writeString(empty, "# MirrorDock: no recording and no custom startup options.\n");
        var environment = builder.environment();
        environment.put("UXPLAYRC", empty.toString());
        Path bin = executable.toAbsolutePath().getParent();
        String pathKey = environment.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        environment.put(pathKey, bin + java.io.File.pathSeparator + environment.getOrDefault(pathKey, ""));
        Path prefix = bin.getParent();
        if (prefix == null) return;
        Path plugins = prefix.resolve("lib/gstreamer-1.0");
        if (Files.isDirectory(plugins)) {
            environment.put("GST_PLUGIN_PATH_1_0", plugins.toString());
            environment.put("GST_PLUGIN_SYSTEM_PATH_1_0", plugins.toString());
            environment.put("GST_REGISTRY_1_0", userDirectory.resolve("gst-registry.bin").toString());
        }
        Path scanner = prefix.resolve("libexec/gstreamer-1.0/gst-plugin-scanner.exe");
        if (Files.isRegularFile(scanner)) environment.put("GST_PLUGIN_SCANNER_1_0", scanner.toString());
    }
}
