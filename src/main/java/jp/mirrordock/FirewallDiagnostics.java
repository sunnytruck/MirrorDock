package jp.mirrordock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Read-only, best-effort diagnostics. Rule presence never proves a packet was blocked. */
public final class FirewallDiagnostics {
    public enum State { BLOCK_RULE, PUBLIC_NETWORK, NO_ALLOW_RULE, ALLOW_RULE, UNKNOWN }
    public record Report(State state, String message, String solution) {}

    // This script emits only fixed tokens. Engine path is data in an environment variable,
    // never PowerShell source. Never output interface names, IPs, paths or policy contents.
    // Cmdlet parameters verified against MicrosoftDocs/windows-powershell-docs,
    // docset/winserver2025-ps/NetSecurity at c3934e67de374f52fa6759f916c112c30a1240c7.
    static final String SCRIPT = """
        $ErrorActionPreference = 'Stop'
        $ProgressPreference = 'SilentlyContinue'
        try {
            $categories = @(Get-NetConnectionProfile -ErrorAction Stop | ForEach-Object {
                if ($_.NetworkCategory -eq 'DomainAuthenticated') { 'Domain' }
                elseif ($_.NetworkCategory -eq 'Private') { 'Private' }
                elseif ($_.NetworkCategory -eq 'Public') { 'Public' }
            } | Select-Object -Unique)
            if ($categories.Count -eq 0) { Write-Output 'MD_FW_UNKNOWN'; exit 0 }
            $profiles = @(Get-NetFirewallProfile -PolicyStore ActiveStore -ErrorAction Stop |
                Where-Object { $categories -contains $_.Name -and $_.Enabled -eq 'True' })
            if ($profiles.Count -eq 0) { Write-Output 'MD_FW_DISABLED'; exit 0 }
            $enabledProfiles = @($profiles | ForEach-Object { $_.Name })
            $filters = @(Get-NetFirewallApplicationFilter -PolicyStore ActiveStore -ErrorAction Stop |
                Where-Object {
                    [String]::Equals([Environment]::ExpandEnvironmentVariables($_.Program),
                        $env:MIRRORDOCK_DIAG_ENGINE, [StringComparison]::OrdinalIgnoreCase)
                })
            $rules = @($filters | ForEach-Object {
                    Get-NetFirewallRule -AssociatedNetFirewallApplicationFilter $_ -PolicyStore ActiveStore -ErrorAction Stop
                } | Where-Object {
                    if ($_.Enabled -ne 'True' -or $_.Direction -ne 'Inbound') { return $false }
                    $ruleProfiles = @($_.Profile.ToString() -split ',' | ForEach-Object { $_.Trim() })
                    return ($ruleProfiles -contains 'Any' -or @($ruleProfiles |
                        Where-Object { $enabledProfiles -contains $_ }).Count -gt 0)
                })
            if (@($rules | Where-Object { $_.Action -eq 'Block' }).Count -gt 0) {
                Write-Output 'MD_FW_BLOCK'
            } elseif ($categories -contains 'Public') {
                Write-Output 'MD_FW_PUBLIC'
            } elseif (@($rules | Where-Object { $_.Action -eq 'Allow' }).Count -gt 0) {
                Write-Output 'MD_FW_ALLOW'
            } else { Write-Output 'MD_FW_NO_ALLOW' }
        } catch { Write-Output 'MD_FW_UNKNOWN' }
        """;

    private FirewallDiagnostics() {}

    /** Blocking: invoke on a worker thread, not the JavaFX application thread. */
    public static Report inspect(Path executable, Duration timeout) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return unknown("Windows以外ではファイアウォール診断を実行しません。");
        }
        if (executable == null || !executable.isAbsolute() || !Files.isRegularFile(executable)) {
            return unknown("UxPlayを導入・選択してからファイアウォール診断を実行してください。");
        }
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) return unknown("Windowsのシステムフォルダーを確認できません。");
        Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(powershell)) return unknown("Windows PowerShellが見つかりません。");
        Process child = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command(powershell)).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("MIRRORDOCK_DIAG_ENGINE", executable.toRealPath().toString());
            builder.environment().put("PSModulePath", Path.of(root, "System32", "WindowsPowerShell", "v1.0", "Modules").toString());
            child = builder.start();
            long millis = timeout == null ? 10_000 : Math.max(1, Math.min(timeout.toMillis(), 30_000));
            if (!child.waitFor(millis, TimeUnit.MILLISECONDS)) {
                return unknown("ファイアウォール診断が時間内に終了しませんでした。遮断の有無は確認できません。");
            }
            if (child.exitValue() != 0) return unknown("ファイアウォール設定を読み取れませんでした。管理ポリシーや権限の制限が考えられます。");
            return parse(new String(child.getInputStream().readNBytes(4096), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return unknown("ファイアウォール設定を読み取れませんでした。原因を特定できません。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unknown("ファイアウォール診断を中断しました。");
        } finally {
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
        }
    }

    static List<String> command(Path powershell) {
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        return List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    static Report parse(String output) {
        String token = output == null ? "" : output.strip();
        return switch (token) {
            case "MD_FW_BLOCK" -> new Report(State.BLOCK_RULE,
                "現在有効なプロファイルに適用される、UxPlayの受信ブロックルールが見つかりました。ルールの通信条件は未検証で、接続失敗の原因とは断定できません。",
                "Windows セキュリティのファイアウォール設定で、対象UxPlayのブロックルールと適用条件を確認してください。変更は内容を確認した利用者自身が行ってください。");
            case "MD_FW_PUBLIC" -> new Report(State.PUBLIC_NETWORK,
                "パブリックとして扱われるネットワーク接続があります。これだけでは通信の遮断を断定できません。",
                "信頼できる家庭内LANかを確認し、Windowsのネットワーク設定とUxPlayの受信許可を確認してください。共有・公衆ネットワークでは設定を安易に変更しないでください。");
            case "MD_FW_ALLOW" -> new Report(State.ALLOW_RULE,
                "対象UxPlayの受信許可ルールが見つかりました。許可する通信条件の一致や、実際の到達性は未確認です。",
                "iPhoneに表示されない場合は同じLAN、AP隔離、mDNS、第三者製ファイアウォールも確認してください。");
            case "MD_FW_NO_ALLOW" -> new Report(State.NO_ALLOW_RULE,
                "対象UxPlayのプログラム別の受信許可ルールを確認できませんでした。共通ルールなどもあるため、遮断を断定できません。",
                "Windows セキュリティの「ファイアウォールによるアプリケーションの許可」で、信頼できるUxPlayをプライベートネットワークに限定して許可する必要があるか確認してください。");
            case "MD_FW_DISABLED" -> unknown("使用中のネットワークで、有効なWindows標準ファイアウォールのプロファイルを確認できませんでした。他製品やルーターによる遮断は判定できません。");
            default -> unknown("ファイアウォールによる遮断の有無を確認できませんでした。原因を特定できません。");
        };
    }

    private static Report unknown(String message) {
        return new Report(State.UNKNOWN, message,
            "Windows セキュリティのファイアウォール設定とネットワークの種類を確認してください。この診断は設定を変更しません。");
    }
}
