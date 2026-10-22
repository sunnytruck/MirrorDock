package jp.mirrordock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EngineDiagnosticsTest {
    @TempDir Path temporary;

    @Test void identifiesWindowsLoaderExitCodes() {
        assertEquals(EngineDiagnostics.Code.MISSING_DLL, EngineDiagnostics.fromExitCode((int) 0xC0000135L).code());
        assertEquals(EngineDiagnostics.Code.WRONG_ARCHITECTURE, EngineDiagnostics.fromExitCode((int) 0xC000007BL).code());
        assertEquals(EngineDiagnostics.Code.DLL_INITIALIZATION_FAILED, EngineDiagnostics.fromExitCode((int) 0xC0000142L).code());
        assertEquals(EngineDiagnostics.Code.PERMISSION_DENIED, EngineDiagnostics.fromExitCode((int) 0xC0000022L).code());
    }

    @Test void unknownExitIsNotDiagnosedAsFirewallOrDllFailure() {
        var diagnostic = EngineDiagnostics.fromExitCode(17);
        assertEquals(EngineDiagnostics.Code.NONZERO_EXIT, diagnostic.code());
        assertTrue(diagnostic.message().contains("原因を特定できません"));
        assertTrue(diagnostic.message().contains("17"));
    }

    @Test void mapsLaunchErrorsWithoutLeakingExceptionPaths() {
        var diagnostic = EngineDiagnostics.launchFailure(new IOException("Cannot run secret-user-path: error=126, DLL not found"));
        assertEquals(EngineDiagnostics.Code.MISSING_DLL, diagnostic.code());
        assertFalse(diagnostic.displayText().contains("secret-user-path"));
        assertEquals(EngineDiagnostics.Code.WRONG_ARCHITECTURE, EngineDiagnostics.launchFailure(new IOException("error=193, bad image")).code());
        assertEquals(EngineDiagnostics.Code.DLL_INITIALIZATION_FAILED, EngineDiagnostics.launchFailure(new IOException("error=1114, init")).code());
        assertEquals(EngineDiagnostics.Code.PERMISSION_DENIED, EngineDiagnostics.launchFailure(new IOException("error=5, denied")).code());
        assertEquals(EngineDiagnostics.Code.EXECUTABLE_MISSING, EngineDiagnostics.launchFailure(new IOException("error=2, missing")).code());
        assertEquals(EngineDiagnostics.Code.START_FAILED, EngineDiagnostics.launchFailure(new IOException("error=206, path too long")).code());
    }

    @Test void discoversApplicationAndUserInstallLocationsWithoutExecutingThem() throws Exception {
        Path app = temporary.resolve("application");
        Path local = temporary.resolve("user");
        Path executable = local.resolve("MirrorDock/engine/bin/uxplay.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "not executable yet");
        var paths = EngineDiagnostics.candidatePaths(app, local.toString(), "C:");
        assertEquals(app.resolve("engine/bin/uxplay.exe"), paths.getFirst());
        assertEquals(executable, EngineDiagnostics.findInstalled(paths).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> paths.add(temporary));
    }

    @Test void discoversManagedVersionAfterSettingsAreLost() throws Exception {
        Path local=temporary.resolve("local");
        Path executable=local.resolve("MirrorDock/engine/versions/old-install/bin/uxplay.exe");
        Files.createDirectories(executable.getParent());Files.writeString(executable,"not executed");
        var candidates=EngineDiagnostics.candidatePaths(null,local.toString(),"C:");
        assertEquals(executable,EngineDiagnostics.findInstalled(candidates).orElseThrow());
    }

    @Test void missingCandidateDoesNotMasqueradeAsInstalled() {
        assertTrue(EngineDiagnostics.findInstalled(List.of(temporary.resolve("missing.exe"))).isEmpty());
        assertEquals(EngineDiagnostics.Code.EXECUTABLE_MISSING, EngineDiagnostics.inspectExecutable(null, Duration.ofSeconds(1)).code());
    }

    @Test void firewallGuidanceExplicitlyStatesUncertaintyAndUserControl() {
        var diagnostic = EngineDiagnostics.firewallGuidance();
        assertEquals(EngineDiagnostics.Code.FIREWALL_UNKNOWN, diagnostic.code());
        assertTrue(diagnostic.message().contains("自動判定できません"));
        assertTrue(diagnostic.solution().contains("自動変更しません"));
    }

    @Test void helpProbeUsesPrivateConfigAndDoesNotClaimAirPlayReadiness() throws Exception {
        var diagnostic = EngineDiagnostics.inspectExecutable(fixtureExecutable(), Duration.ofSeconds(5), ignored -> fixtureBuilder("help"));
        assertEquals(EngineDiagnostics.Code.HELP_OK, diagnostic.code());
        assertTrue(diagnostic.solution().contains("未確認"));
    }

    @Test void helpProbeReportsNonzeroExit() throws Exception {
        var diagnostic = EngineDiagnostics.inspectExecutable(fixtureExecutable(), Duration.ofSeconds(5), ignored -> fixtureBuilder("crash"));
        assertEquals(EngineDiagnostics.Code.NONZERO_EXIT, diagnostic.code());
        assertTrue(diagnostic.message().contains("23"));
    }

    @Test void timedOutDiagnosticProcessIsStopped() throws Exception {
        // Record the launched PID from a process helper file, rather than observing stdout.
        Path pidFile = temporary.resolve("pid");
        var diagnostic = EngineDiagnostics.inspectExecutable(fixtureExecutable(), Duration.ofSeconds(3), ignored -> fixtureBuilder("wait", pidFile.toString()));
        assertEquals(EngineDiagnostics.Code.TIMEOUT, diagnostic.code());
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void probeRejectsInvalidTimeout() {
        assertThrows(IllegalArgumentException.class, () -> EngineDiagnostics.inspectExecutable(null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> EngineDiagnostics.inspectExecutable(null, Duration.ofSeconds(31)));
    }

    private Path fixtureExecutable() throws IOException {
        Path binary = temporary.resolve("uxplay.exe");
        Files.copy(javaExecutable(), binary);
        binary.toFile().setExecutable(true);
        return binary;
    }
    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
    }
    private static ProcessBuilder fixtureBuilder(String... args) {
        try {
            var command = new java.util.ArrayList<>(List.of(javaExecutable().toString(), "-cp",
                    Path.of(FakeDiagnosticEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), FakeDiagnosticEngine.class.getName()));
            command.addAll(List.of(args));
            return new ProcessBuilder(command);
        } catch (java.net.URISyntaxException e) { throw new IllegalStateException(e); }
    }
    public static class FakeDiagnosticEngine {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("crash")) System.exit(23);
            if (args[0].equals("wait")) {
                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60000);
            }
            String config = System.getenv("UXPLAYRC");
            if (config == null || !Files.readString(Path.of(config)).contains("no recording")) System.exit(24);
        }
    }
}
