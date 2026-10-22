package jp.mirrordock;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class FirewallDiagnosticsTest {
    @Test void blockRuleIsReportedWithoutClaimingConnectionCause() {
        var report = FirewallDiagnostics.parse("MD_FW_BLOCK\r\n");
        assertEquals(FirewallDiagnostics.State.BLOCK_RULE, report.state());
        assertTrue(report.message().contains("断定できません"));
        assertTrue(report.solution().contains("利用者自身"));
    }
    @Test void publicNetworkIsAWarningNotProof() {
        var report = FirewallDiagnostics.parse("MD_FW_PUBLIC");
        assertEquals(FirewallDiagnostics.State.PUBLIC_NETWORK, report.state());
        assertTrue(report.message().contains("断定できません"));
    }
    @Test void noAllowRuleDoesNotAssertBlock() {
        var report = FirewallDiagnostics.parse("MD_FW_NO_ALLOW");
        assertEquals(FirewallDiagnostics.State.NO_ALLOW_RULE, report.state());
        assertTrue(report.message().contains("共通ルール"));
    }
    @Test void allowRuleDoesNotAssertReachability() {
        var report = FirewallDiagnostics.parse("MD_FW_ALLOW");
        assertEquals(FirewallDiagnostics.State.ALLOW_RULE, report.state());
        assertTrue(report.message().contains("未確認"));
    }
    @Test void unrecognizedAndUntrustedOutputIsNotDisplayed() {
        var report = FirewallDiagnostics.parse("private address\nMD_FW_ALLOW");
        assertEquals(FirewallDiagnostics.State.UNKNOWN, report.state());
        assertFalse(report.message().contains("private address"));
        assertEquals(FirewallDiagnostics.State.UNKNOWN, FirewallDiagnostics.parse(null).state());
        assertEquals(FirewallDiagnostics.State.UNKNOWN, FirewallDiagnostics.parse("MD_FW_DISABLED").state());
    }
    @Test void fixedEncodedReadOnlyCommandCannotInterpolateExecutable() {
        var command = FirewallDiagnostics.command(Path.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"));
        assertEquals("-EncodedCommand", command.get(command.size()-2));
        String script = new String(Base64.getDecoder().decode(command.getLast()), StandardCharsets.UTF_16LE);
        assertEquals(FirewallDiagnostics.SCRIPT, script);
        assertTrue(script.contains("$env:MIRRORDOCK_DIAG_ENGINE"));
        assertFalse(script.contains("Set-Net"));
        assertFalse(script.contains("New-Net"));
        assertFalse(script.contains("Remove-Net"));
        assertTrue(script.contains("-PolicyStore ActiveStore"));
        assertTrue(command.contains("-NoProfile"));
        assertTrue(command.contains("-NonInteractive"));
    }
}
