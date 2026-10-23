package jp.mirrordock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
public final class UxPlayService implements AirPlayService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"uxplay-worker");t.setDaemon(true);return t; });
    private final java.util.function.BiFunction<Path,Settings,List<String>> commands;
    public UxPlayService() { this(UxPlayService::command); }
    UxPlayService(java.util.function.BiFunction<Path,Settings,List<String>> commands) { this.commands=commands; }
    private final ScheduledExecutorService control=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"uxplay-control");t.setDaemon(true);return t;});
    private volatile State state=State.STOPPED;
    private volatile Process process;
    private volatile Consumer<Event> listener=e -> {};
    private volatile Consumer<PerformanceProtocol.Message> metricsListener=message -> {};
    private volatile boolean metricsReady;
    private volatile Settings activeSettings;
    private boolean active, closed;
    private long generation;
    public void setListener(Consumer<Event> listener) { this.listener=Objects.requireNonNull(listener); }
    public void setMetricsListener(Consumer<PerformanceProtocol.Message> listener) { metricsListener=Objects.requireNonNull(listener); }
    public Settings activeSettings() { return activeSettings; }
    public boolean metricsAvailable() { Process child=process;return metricsReady && child!=null && child.isAlive(); }
    public boolean startMetrics(long sessionId,int seconds) {
        return startMetrics(sessionId,seconds,false);
    }
    public boolean startMetrics(long sessionId,int seconds,boolean detailedPresent) {
        return sendMetricsCommand(PerformanceProtocol.startCommand(sessionId,seconds,detailedPresent));
    }
    public void stopMetrics(long sessionId) { sendMetricsCommand(PerformanceProtocol.stopCommand(sessionId)); }
    private synchronized boolean sendMetricsCommand(String command) {
        if (closed || !metricsAvailable()) return false;
        Process target=process;
        long run=generation;
        // The UI never waits for pipe writes. Commands are bounded, fixed tokens.
        control.submit(() -> {
            synchronized (this) {
                if (run!=generation || target!=process || !metricsReady) return;
                try {
                    target.getOutputStream().write(command.getBytes(StandardCharsets.US_ASCII));
                    target.getOutputStream().flush();
                } catch (IOException ignored) { metricsReady=false; }
            }
        });
        return true;
    }
    public State state() { return state; }
    public long pid() { Process p=process;return p==null ? -1 : p.pid(); }
    private synchronized void emit(long run, State next,String message) {
        if (run!=generation) return;
        state=next; listener.accept(new Event(next,message));
    }
    public synchronized void start(Settings settings) {
        if (closed || active) return;
        active=true; long run=++generation;
        emit(run,State.STARTING,"受信エンジンを起動しています…");
        worker.submit(() -> launch(settings,run));
    }
    static Path validateExecutable(String value) throws IOException {
        if (value==null || value.isBlank()) throw new IOException("UxPlayが未設定です。設定からuxplay.exeを選択してください。");
        Path p;
        try { p=Path.of(value); } catch (InvalidPathException e) { throw new IOException("実行ファイルのパスが不正です。"); }
        if (!p.isAbsolute() || !Files.isRegularFile(p)) throw new IOException("UxPlay実行ファイルが見つかりません。絶対パスを確認してください。");
        p=p.toRealPath();
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        if (windows && !p.getFileName().toString().equalsIgnoreCase("uxplay.exe")) throw new IOException("uxplay.exeを指定してください。スクリプトは実行できません。");
        if (!Files.isExecutable(p)) throw new IOException("実行権限がありません。");
        return p;
    }
    static List<String> command(Path executable, Settings s) {
        List<String> args=new ArrayList<>(List.of(executable.toString(),"-n",s.receiverName(),"-nh","-d","1","-fps",Integer.toString(s.requestedFps()),"-as",s.audio()?"autoaudiosink":"0"));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) args.addAll(List.of("-vs","d3d11videosink"));
        return args;
    }
    private void launch(Settings settings,long run) {
        Process child=null;
        try {
            Path executable=validateExecutable(settings.executable());
            ProcessBuilder builder=new ProcessBuilder(commands.apply(executable,settings)).redirectErrorStream(true);
            builder.directory(executable.getParent().toFile());
            // Prevent user UxPlay startup files from enabling recording or overriding privacy-sensitive defaults.
            EngineEnvironment.configure(builder,executable,SettingsService.userDirectory());
            // Patched engines wait on the private parent/child stdin pipe. No
            // per-frame diagnostic hooks are installed until an explicit START.
            builder.environment().put("MIRRORDOCK_PERF_CONTROL","1");
            synchronized(this) {
                if (run!=generation || !active) return;
                metricsReady=false;
                child=builder.start(); process=child; activeSettings=settings;
                control.schedule(() -> {
                    synchronized(this) {
                        if(run==generation && state==State.STARTING) {
                            emit(run,State.ERROR,"サービス広告の開始を確認できません。UxPlayのバージョン・mDNS・DLLを確認して再試行してください。原因を特定できません。");
                            Process stalled=process;
                            if(stalled!=null) stalled.destroy();
                        }
                    }
                },20,TimeUnit.SECONDS);
            }
            try (BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while ((line=reader.readLine())!=null) parse(run,line);
            }
            int exit=child.waitFor();
            emit(run,State.ERROR,exit==0 ? "UxPlayが終了しました。受信開始から再試行してください。原因を特定できません。" : EngineDiagnostics.fromExitCode(exit).displayText());
        } catch (IOException e) {
            String message=e.getMessage();
            if (child!=null) message="UxPlayとの通信に失敗しました。原因を特定できません。";
            else if (message!=null && message.startsWith("Cannot run program")) message=EngineDiagnostics.launchFailure(e).displayText();
            else if (!(message!=null && (message.startsWith("UxPlay") || message.startsWith("実行") || message.startsWith("uxplay")))) message="UxPlayを起動できません。実行権限・DLL・パスを確認してください。原因を特定できません。";
            emit(run,State.ERROR,message);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            if (child!=null) terminate(child);
            synchronized(this) { if (run==generation) {process=null;activeSettings=null;metricsReady=false;active=false;} }
        }
    }
    void parse(long run,String line) {
        if (line.startsWith("MDPERF1 ")) {
            PerformanceProtocol.parse(line).ifPresent(message -> {
                synchronized (this) {
                    if (run!=generation) return;
                    if (message instanceof PerformanceProtocol.Ready) metricsReady=true;
                }
                // Diagnostics can send a control command in response. Keep its
                // lock independent from process lifecycle/pipe management.
                try { metricsListener.accept(message); }
                catch (RuntimeException ignored) { /* A failed diagnostic must not terminate video/audio reception. */ }
            });
            return;
        }
        String lower=line.toLowerCase(Locale.ROOT);
        if (line.contains("register_dnssd: advertised AirPlay service")) emit(run,State.WAITING,"接続待機中（サービス広告を開始しました）");
        else if (line.contains("Open connections: 0") || lower.contains("lost connection with client")) emit(run,State.WAITING,"接続が切れました。再接続を待っています。");
        else if (line.matches(".*Open connections: [1-9][0-9]*.*")) emit(run,State.CONNECTED,"接続中（映像の表示はUxPlayウィンドウを確認してください）");
        else if (lower.contains("error") || lower.contains("failed")) {
            String message=lower.contains("dnssd") || lower.contains("mdns") ? "mDNSサービス検出に失敗しました。ネットワークとBonjour（利用時）を確認してください。" :
                lower.contains("audio") ? "音声処理で問題が発生しました。音声をOFFにして再試行できます。" :
                lower.contains("video") || lower.contains("gstreamer") ? "映像処理で問題が発生しました。GStreamerのデコーダーとプラグインを確認してください。" :
                "受信エンジンでエラーが発生しました。原因を特定できません。";
            emit(run,state,message);
        }
    }
    public synchronized void stop() {
        if (closed) return;
        long run=++generation; active=false;
        Process old=process;process=null;activeSettings=null;metricsReady=false;
        if (old!=null) {
            var children=old.descendants().toList();
            control.submit(() -> {children.forEach(ProcessHandle::destroy);terminate(old);children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);});
        }
        emit(run,State.STOPPED,"受信を停止しました。");
        // Release the blocking output reader immediately; termination waits happen on worker.
        if (old!=null) old.destroy();
    }
    private static void terminate(Process p) {
        var descendants=p.descendants().toList(); descendants.forEach(ProcessHandle::destroy);p.destroy();
        try { if (!p.waitFor(2,TimeUnit.SECONDS)) p.destroyForcibly(); }
        catch (InterruptedException e) { p.destroyForcibly();Thread.currentThread().interrupt(); }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
    public void close() {
        Process old;
        synchronized(this) { if (closed) return; old=process; stop();closed=true;worker.shutdown();control.shutdown(); }
        if(old!=null) terminate(old);
    }
}
