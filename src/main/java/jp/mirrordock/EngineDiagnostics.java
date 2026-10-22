package jp.mirrordock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Read-only checks. Call process checks from a worker, never from the JavaFX thread. */
public final class EngineDiagnostics {
    public enum Severity { INFO, WARNING, ERROR }
    public enum Code {
        EXECUTABLE_MISSING, INVALID_EXECUTABLE, HELP_OK, MISSING_DLL, WRONG_ARCHITECTURE,
        DLL_INITIALIZATION_FAILED, PERMISSION_DENIED, START_FAILED, NONZERO_EXIT,
        TIMEOUT, INTERRUPTED, FIREWALL_UNKNOWN
    }
    public record Diagnostic(Code code, Severity severity, String message, String solution) {
        public String displayText() { return message + "\n" + solution; }
    }
    private EngineDiagnostics() {}

    /** The return value is only a suggested path. It is never executed by discovery. */
    public static List<Path> candidatePaths(Path appDirectory, String localAppData, String systemDrive) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        if (appDirectory != null) {
            paths.add(appDirectory.resolve("engine/bin/uxplay.exe"));
            paths.add(appDirectory.resolve("engine/uxplay.exe"));
        }
        if (localAppData != null && !localAppData.isBlank()) {
            try {
                Path base = Path.of(localAppData).resolve("MirrorDock/engine");
                paths.add(base.resolve("bin/uxplay.exe"));
                paths.add(base.resolve("uxplay.exe"));
                Path versions=base.resolve("versions");
                if(Files.isDirectory(versions)) {
                    try(var managed=Files.list(versions)) {
                        managed.filter(Files::isDirectory).filter(p->!p.getFileName().toString().startsWith("."))
                            .sorted(java.util.Comparator.reverseOrder()).map(p->p.resolve("bin/uxplay.exe")).forEach(paths::add);
                    } catch(IOException ignored) { /* Keep conventional locations if managed installs cannot be read. */ }
                }
            } catch (InvalidPathException ignored) { /* An unusable environment path is not a match. */ }
        }
        try {
            String drive = systemDrive == null || systemDrive.isBlank() ? "C:" : systemDrive;
            paths.add(Path.of(drive + "/msys64/ucrt64/bin/uxplay.exe"));
        } catch (InvalidPathException ignored) { /* Keep any other valid suggestions. */ }
        return List.copyOf(paths);
    }

    public static Optional<Path> findInstalled(List<Path> candidates) {
        return candidates.stream().filter(path -> Files.isRegularFile(path)
                && path.getFileName().toString().equalsIgnoreCase("uxplay.exe")).findFirst();
    }

    public static Diagnostic missingExecutable() {
        return new Diagnostic(Code.EXECUTABLE_MISSING, Severity.ERROR,
                "UxPlayが見つかりません。受信エンジンの導入が必要です。",
                "「受信エンジンの導入」から案内を開くか、導入済みのuxplay.exeを選択してください。実行ファイルだけをコピーすると必要なDLLが不足します。");
    }

    public static Diagnostic fromExitCode(int exit) {
        return switch (exit) {
            case 0 -> new Diagnostic(Code.HELP_OK, Severity.INFO,
                    "UxPlayの実行を確認しました。",
                    "ヘルプ表示までの確認です。映像・音声・iPhoneからの検出は未確認です。「受信開始」で接続を確認してください。");
            case (int) 0xC0000135L -> missingDll();
            case (int) 0xC000007BL -> wrongArchitecture();
            case (int) 0xC0000142L -> dllInitializationFailed();
            case (int) 0xC0000022L -> permissionDenied();
            default -> new Diagnostic(Code.NONZERO_EXIT, Severity.ERROR,
                    "UxPlayが終了しました（終了コード " + exit + " / " + String.format(Locale.ROOT, "0x%08X", exit) + "）。原因を特定できません。",
                    "「受信エンジンを診断」で導入状態を確認してください。GStreamerプラグイン、mDNS、必要DLLを含む同一バージョンの完全な構成が必要です。");
        };
    }

    /** Map only Windows loader errors with known meaning; do not show exception contents. */
    public static Diagnostic launchFailure(IOException failure) {
        String text = failure.getMessage() == null ? "" : failure.getMessage();
        if (text.matches("(?s).*\\berror=126\\b.*")) return missingDll();
        if (text.matches("(?s).*\\berror=193\\b.*")) return wrongArchitecture();
        if (text.matches("(?s).*\\berror=1114\\b.*")) return dllInitializationFailed();
        if (text.matches("(?s).*\\berror=5\\b.*")) return permissionDenied();
        if (text.matches("(?s).*\\berror=2\\b.*")) return missingExecutable();
        return new Diagnostic(Code.START_FAILED, Severity.ERROR,
                "UxPlayを起動できません。原因を特定できません。",
                "uxplay.exeの場所、実行権限、Windowsのセキュリティ履歴、同梱DLLの有無を確認してください。保護機能を無効にせず、信頼できる配布元の構成を使ってください。");
    }

    private static Diagnostic missingDll() {
        return new Diagnostic(Code.MISSING_DLL, Severity.ERROR,
                "Windowsが必要なDLLを読み込めませんでした（DLL不足）。",
                "uxplay.exeだけでなく、同じ配布物のDLL・GStreamerプラグインをまとめて配置し直してください。MSYS2版では同じUCRT64環境の依存パッケージを修復します。DLL単体の配布サイトから追加しないでください。");
    }
    private static Diagnostic wrongArchitecture() {
        return new Diagnostic(Code.WRONG_ARCHITECTURE, Severity.ERROR,
                "実行ファイルまたはDLLの形式が適合しません。",
                "Windows x64用の実行ファイルと、同じ構成の64bit DLLを使ってください。x86/ARM64、MSVC/MINGW64/UCRT64のファイルを混在させないでください。");
    }
    private static Diagnostic dllInitializationFailed() {
        return new Diagnostic(Code.DLL_INITIALIZATION_FAILED, Severity.ERROR,
                "DLLの初期化に失敗しました。",
                "同じ配布物のDLL一式を配置し直してください。Windowsのイベントビューアーで対象モジュールを確認できます。初期化失敗の具体的な原因は特定できません。");
    }
    private static Diagnostic permissionDenied() {
        return new Diagnostic(Code.PERMISSION_DENIED, Severity.ERROR,
                "UxPlayの実行がアクセス拒否されました。",
                "ファイルの実行権限とWindowsのセキュリティ履歴を確認してください。管理者としての常時実行やセキュリティ保護の無効化は必要ありません。");
    }

    public static Diagnostic firewallGuidance() {
        return new Diagnostic(Code.FIREWALL_UNKNOWN, Severity.WARNING,
                "ファイアウォールの遮断有無は自動判定できません。",
                "同一LAN、プライベートネットワーク、ゲストWi-Fiの端末隔離、mDNS（UDP 5353）を確認してください。Windows セキュリティの「アプリの許可」で信頼できるUxPlayをプライベートネットワークに許可します。設定変更は利用者が行い、アプリは自動変更しません。");
    }

    /** Runs only the selected executable's documented -h command; performs no LAN probe. */
    public static Diagnostic inspectExecutable(Path executable, Duration timeout) {
        return inspectExecutable(executable, timeout, path -> new ProcessBuilder(path.toString(), "-h"));
    }

    static Diagnostic inspectExecutable(Path executable, Duration timeout, Function<Path, ProcessBuilder> builderFactory) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Timeout must be between 1 millisecond and 30 seconds.");
        if (executable == null || !Files.isRegularFile(executable)) return missingExecutable();
        Path validated;
        try { validated = UxPlayService.validateExecutable(executable.toString()); }
        catch (IOException e) {
            return new Diagnostic(Code.INVALID_EXECUTABLE, Severity.ERROR,
                    "実行ファイルのパスまたは実行権限が不正です。",
                    "信頼できるuxplay.exeを絶対パスで指定してください。スクリプトやショートカットは使用できません。");
        }
        Process process = null;
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("mirrordock-diagnostic-");
            ProcessBuilder builder = builderFactory.apply(validated);
            builder.directory(validated.getParent().toFile());
            EngineEnvironment.configure(builder, validated, temporary);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                return new Diagnostic(Code.TIMEOUT, Severity.ERROR,
                        "UxPlayの応答を時間内に確認できませんでした。",
                        "別ウィンドウにWindowsのエラーが表示されていないか確認し、依存DLLと配布物の構成を確認してください。診断のために起動したプロセスは停止しました。");
            }
            return fromExitCode(process.exitValue());
        } catch (IOException e) { return launchFailure(e); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Diagnostic(Code.INTERRUPTED, Severity.WARNING, "受信エンジンの診断を中断しました。", "必要な場合は診断を再実行してください。");
        } finally {
            if (process != null && process.isAlive()) {
                var children = process.descendants().toList();
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (temporary != null) {
                try (var files = Files.walk(temporary)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException ignored) { /* Temporary cleanup failure must not hide diagnostic result. */ }
            }
        }
    }
}
