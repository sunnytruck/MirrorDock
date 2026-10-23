package jp.mirrordock;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Opt-in measurements only. This pane never writes raw engine output or media. */
final class PerformancePane extends TitledPane implements AutoCloseable {
    private final Window owner;
    private final UxPlayService engine;
    private final WindowManager windows;
    private final PerformanceDiagnosticsService diagnostics;
    private final ExecutorService exports = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "performance-export");
        thread.setDaemon(true);
        return thread;
    });
    private final Button begin = new Button("性能診断を開始"), stop = new Button("性能診断を停止"),
            save = new Button("診断結果を保存…");
    private final ComboBox<WindowManager.EngineWindowMode> windowMode = new ComboBox<>();
    private final CheckBox detailedPresent = new CheckBox("Present詳細計測（描画経路が変わります）");
    private final ComboBox<PerformanceDiagnosticsService.Signal> audioObservation = signalChoice(),
            visualObservation = signalChoice();
    private final ComboBox<PerformanceDiagnosticsService.Latency> latencyObservation = new ComboBox<>();
    private final Label state = wrapped("受信接続後に開始できます。診断は約60秒で自動停止します。"),
            requested = wrapped("受信中の設定上限: 停止中"), context = wrapped("診断条件: まだ診断していません"),
            elapsed = new Label("0 / 60秒"), decoder = new Label("計測不可"),
            drops = new Label("計測不可"), sourceGaps = new Label("計測不可"),
            decodeGaps = new Label("計測不可"), presentGaps = new Label("計測不可"),
            comparison = wrapped("30fps／60fpsの診断結果は、終了後にここへ表示します。"),
            feedback = wrapped("");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label[] latest = {new Label("計測不可"), new Label("計測不可"), new Label("計測不可"), new Label("計測不可")};
    private final Label[] average = {new Label("計測不可"), new Label("計測不可"), new Label("計測不可"), new Label("計測不可")};
    private final Map<Integer, PerformanceDiagnosticsService.Snapshot> comparisons = new LinkedHashMap<>();
    private AirPlayService.State engineState = AirPlayService.State.STOPPED;
    private WindowManager.EngineWindowMode previousWindowMode;
    private Consumer<Boolean> runningListener = running -> {};
    private long activeSession, displayedSession, archivedSession;
    private boolean exporting, closed, displayedRunning, displayedComplete;

    PerformancePane(Window owner, UxPlayService engine, WindowManager windows) {
        this.owner = owner;
        this.engine = engine;
        this.windows = windows;
        diagnostics = new PerformanceDiagnosticsService(new PerformanceDiagnosticsService.EngineControl() {
            @Override public boolean available() { return engine.metricsAvailable(); }
            @Override public boolean start(long sessionId, int seconds) { return engine.startMetrics(sessionId, seconds); }
            @Override public boolean start(long sessionId, int seconds, boolean detailed) { return engine.startMetrics(sessionId, seconds, detailed); }
            @Override public void stop(long sessionId) { engine.stopMetrics(sessionId); }
        });
        setText("性能診断・30／60fpsの比較");
        setExpanded(false);
        progress.setMaxWidth(Double.MAX_VALUE);
        windowMode.getItems().setAll(WindowManager.EngineWindowMode.values());
        windowMode.setValue(WindowManager.EngineWindowMode.ON_CHANGE);
        windowMode.setConverter(new StringConverter<>() {
            @Override public String toString(WindowManager.EngineWindowMode mode) { return modeLabel(mode); }
            @Override public WindowManager.EngineWindowMode fromString(String value) { return null; }
        });
        windowMode.setMaxWidth(Double.MAX_VALUE);
        windowMode.setOnAction(event -> feedback.setText("選択したウィンドウ制御は、次の性能診断中だけ適用します。終了時に元の方式へ戻します。"));
        latencyObservation.getItems().setAll(PerformanceDiagnosticsService.Latency.values());
        latencyObservation.setValue(PerformanceDiagnosticsService.Latency.UNASSESSED);
        latencyObservation.setConverter(new StringConverter<>() {
            @Override public String toString(PerformanceDiagnosticsService.Latency value) {
                if (value == null) return "未確認";
                return switch (value) { case UNASSESSED -> "未確認"; case ACCEPTABLE -> "気にならない"; case NOTICEABLE -> "気になる"; };
            }
            @Override public PerformanceDiagnosticsService.Latency fromString(String value) { return null; }
        });

        GridPane measurements = new GridPane();
        measurements.setHgap(16);
        measurements.setVgap(7);
        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setHgrow(Priority.ALWAYS);
        measurements.getColumnConstraints().addAll(nameColumn, new ColumnConstraints(120), new ColumnConstraints(140));
        measurements.addRow(0, new Label("計測項目"), new Label("最新区間"), new Label("診断区間平均"));
        String[] names = {"受信FPS（解析後AU）", "デコード出力 FPS", "sink描画 FPS", "Present直前通知/秒"};
        for (int i = 0; i < names.length; i++) measurements.addRow(i + 1, wrapped(names[i]), latest[i], average[i]);
        addFullValue(measurements, 5, "sinkドロップ（同一pipeline区間累計）", drops);
        addFullValue(measurements, 6, "実際のデコーダー", decoder);
        addFullValue(measurements, 7, "受信間隔 P95／最大", sourceGaps);
        addFullValue(measurements, 8, "デコード間隔 P95／最大", decodeGaps);
        addFullValue(measurements, 9, "Present間隔 P95／最大", presentGaps);

        GridPane observations = new GridPane();
        observations.setHgap(12);
        observations.setVgap(7);
        observations.addRow(0, new Label("手動観察：音ズレ"), audioObservation);
        observations.addRow(1, new Label("手動観察：カクつき"), visualObservation);
        observations.addRow(2, new Label("手動観察：体感遅延"), latencyObservation);
        VBox actions = new VBox(8, begin, stop, save);
        VBox content = new VBox(10, requested,
                wrapped("30／60fpsはiPhoneへ案内する上限です。以下の実測FPSとは別で、60fpsの受信を保証しません。"),
                new Label("診断中のウィンドウ制御"), windowMode,
                wrapped("停止方式では現在の最前面状態を保持します。比較中は音声・映像サイズ・モニター条件を揃えてください。"),
                detailedPresent,
                wrapped("通常診断ではPresentの項目は計測不可です。詳細計測ON時は描画経路・最適化条件が一時的に変わるため、通常診断と分けて比較してください。"),
                actions, progress, elapsed, state, context, measurements,
                wrapped("実画面の表示FPS・デコーダーのdrop・TCP再送・数値によるA/Vずれや表示遅延は計測不可です。PresentはDXGI呼出前通知で、再描画を含む場合があります。sink／Presentの処理回数は、物理モニターの表示回数とは異なります。"),
                wrapped("P95は直近512間隔、最大値とdropは現在のpipeline区間の値です。pipelineの再生成でリセットされます。受信間隔だけではネットワーク原因を断定できません。"),
                observations, comparison, feedback,
                wrapped("保存するのは数値・許可した識別子・選択式の観察結果だけです。映像・音声・鍵・端末名・アドレス・生ログは保存しません。"));
        content.setPadding(new Insets(8));
        setContent(content);
        begin.setOnAction(event -> begin());
        stop.setOnAction(event -> diagnostics.stop());
        save.setOnAction(event -> export());
        diagnostics.setListener(snapshot -> onFx(() -> render(snapshot)));
        render(diagnostics.snapshot());
    }

    void setRunningListener(Consumer<Boolean> listener) { runningListener = listener; }

    void accept(PerformanceProtocol.Message message) {
        if (!closed) diagnostics.accept(message);
    }

    void setEngineState(AirPlayService.State next) {
        boolean disconnected = engineState == AirPlayService.State.CONNECTED && next == AirPlayService.State.WAITING;
        engineState = next;
        if (next == AirPlayService.State.STOPPED || next == AirPlayService.State.ERROR || disconnected) diagnostics.engineStopped();
        render(diagnostics.snapshot());
    }

    private void begin() {
        Settings active = engine.activeSettings();
        if (active == null || engineState != AirPlayService.State.CONNECTED || diagnostics.running() || exporting) return;
        previousWindowMode = windows.getEngineWindowMode();
        WindowManager.EngineWindowMode selected = windowMode.getValue();
        audioObservation.setValue(PerformanceDiagnosticsService.Signal.UNASSESSED);
        visualObservation.setValue(PerformanceDiagnosticsService.Signal.UNASSESSED);
        latencyObservation.setValue(PerformanceDiagnosticsService.Latency.UNASSESSED);
        try {
            windows.setEngineWindowMode(selected);
            activeSession = diagnostics.start(new PerformanceDiagnosticsService.Context(
                    active.requestedFps(), selected.name(), active.audio(), detailedPresent.isSelected()), Duration.ofSeconds(60));
            feedback.setText("診断中も受信を継続します。途中停止でき、約60秒で自動停止します。");
            render(diagnostics.snapshot());
        } catch (RuntimeException failure) {
            restoreWindowMode();
            feedback.setText("性能診断を開始できません。受信状態を確認してください。原因を特定できません。");
        }
    }

    private void render(PerformanceDiagnosticsService.Snapshot snapshot) {
        if (closed || snapshot.sessionId() < displayedSession
                || (snapshot.sessionId() == displayedSession && displayedComplete
                && snapshot.status() == PerformanceDiagnosticsService.Status.RUNNING)) return;
        if (snapshot.sessionId() != displayedSession) displayedComplete = false;
        displayedSession = snapshot.sessionId();
        if (snapshot.status() == PerformanceDiagnosticsService.Status.COMPLETE) displayedComplete = true;
        boolean running = snapshot.status() == PerformanceDiagnosticsService.Status.RUNNING;
        if (!running && snapshot.sessionId() == activeSession) restoreWindowMode();
        if (displayedRunning != running) {
            displayedRunning = running;
            runningListener.accept(running);
        }
        Settings active = engine.activeSettings();
        requested.setText(active == null ? "受信中の設定上限: 停止中" : "受信中の設定上限: " + active.requestedFps() + "fps（実測値ではありません）");
        if (snapshot.context() != null && snapshot.status() != PerformanceDiagnosticsService.Status.IDLE) {
            context.setText("診断条件: 要求上限 " + snapshot.context().requestedFps() + "fps、"
                    + modeLabel(WindowManager.EngineWindowMode.valueOf(snapshot.context().windowModeName()))
                    + "、音声" + (snapshot.context().audio() ? "ON" : "OFF")
                    + "、Present詳細" + (snapshot.context().detailedPresent() ? "ON" : "OFF"));
        } else context.setText("診断条件: まだ診断していません");
        double seconds = snapshot.elapsedMillis() / 1000.0;
        double limit = snapshot.limitMillis() / 1000.0;
        elapsed.setText(String.format(Locale.ROOT, "%.0f / %.0f秒 ・ %dサンプル", seconds, limit > 0 ? limit : 60, snapshot.samples()));
        progress.setProgress(snapshot.limitMillis() > 0 ? Math.min(1, (double) snapshot.elapsedMillis() / snapshot.limitMillis()) : 0);
        if (snapshot.status() == PerformanceDiagnosticsService.Status.IDLE) state.setText("iPhoneとの接続後に開始できます。診断は約60秒で自動停止します。");
        else if (running && !snapshot.engineAvailable()) state.setText("診断中：このエンジンの計測対応を確認できません。自動値は計測不可です。手動観察と比較条件は記録できます。");
        else if (running && snapshot.lastSample() == null) state.setText("診断中：エンジンの計測値を待っています。未取得の値は計測不可と表示します。");
        else if (running) state.setText("診断中：設定上限と実測値を比較してください。数値だけでは原因を断定できません。");
        else state.setText("診断終了：" + completionLabel(snapshot.completion())
                + (snapshot.countersReset() ? "。計測区間変更・カウンター再初期化を検出し、平均は比較可能な区間だけで算出しています。" : "。未取得の項目は計測不可です。"));
        updateMetrics(snapshot.current(), latest);
        updateMetrics(snapshot.average(), average);
        var metrics = snapshot.average();
        decoder.setText(metrics.decoder() == null ? "計測不可" : metrics.decoder());
        drops.setText(metrics.sinkDroppedFrames() == null ? "計測不可" : metrics.sinkDroppedFrames().toString());
        sourceGaps.setText(gaps(metrics.sourceGapP95Millis(), metrics.sourceGapMaxMillis()));
        decodeGaps.setText(gaps(metrics.decodeGapP95Millis(), metrics.decodeGapMaxMillis()));
        presentGaps.setText(gaps(metrics.presentGapP95Millis(), metrics.presentGapMaxMillis()));
        if (snapshot.status() == PerformanceDiagnosticsService.Status.COMPLETE && snapshot.context() != null && snapshot.sessionId() != archivedSession) {
            archivedSession = snapshot.sessionId();
            comparisons.put(snapshot.context().requestedFps(), snapshot);
            comparison.setText(comparisonText());
        }
        begin.setDisable(running || exporting || active == null || engineState != AirPlayService.State.CONNECTED);
        stop.setDisable(!running);
        save.setDisable(exporting || snapshot.status() != PerformanceDiagnosticsService.Status.COMPLETE);
        windowMode.setDisable(running || exporting);
        detailedPresent.setDisable(running || exporting);
    }

    private void export() {
        if (closed || exporting || diagnostics.snapshot().status() != PerformanceDiagnosticsService.Status.COMPLETE) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("性能診断結果を保存");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("診断結果（JSON）", "*.json"));
        chooser.setInitialFileName("MirrorDock-performance.json");
        var selected = chooser.showSaveDialog(owner);
        if (selected == null) return;
        Path destination = selected.toPath();
        var observations = new PerformanceDiagnosticsService.Observations(
                audioObservation.getValue(), visualObservation.getValue(), latencyObservation.getValue());
        exporting = true;
        render(diagnostics.snapshot());
        exports.submit(() -> {
            String result;
            try {
                diagnostics.saveReport(destination, observations);
                result = "診断結果を保存しました。映像・音声・個人情報・生ログは含みません。";
            } catch (Exception failure) {
                result = "診断結果を保存できません。保存先の権限と空き容量を確認してください。";
            }
            String display = result;
            onFx(() -> { exporting = false; feedback.setText(display); render(diagnostics.snapshot()); });
        });
    }

    private String comparisonText() {
        StringBuilder text = new StringBuilder("終了した直近の診断（条件・経過時間を揃えて比較してください）");
        for (int fps : new int[]{30, 60}) {
            var value = comparisons.get(fps);
            if (value == null) { text.append('\n').append(fps).append("fps設定: 未診断"); continue; }
            var metrics = value.average();
            text.append('\n').append(fps).append("fps設定: 受信 ").append(number(metrics.sourceFps()))
                    .append(" / decode ").append(number(metrics.decodedFps())).append(" / sink ").append(number(metrics.renderedFps()))
                    .append(" FPS、drop ").append(metrics.sinkDroppedFrames() == null ? "計測不可" : metrics.sinkDroppedFrames())
                    .append("、").append(String.format(Locale.ROOT, "%.0f秒", value.elapsedMillis() / 1000.0))
                    .append("、").append(modeLabel(WindowManager.EngineWindowMode.valueOf(value.context().windowModeName())))
                    .append("、音声").append(value.context().audio() ? "ON" : "OFF")
                    .append("、Present詳細").append(value.context().detailedPresent() ? "ON" : "OFF");
        }
        return text.toString();
    }

    private void restoreWindowMode() {
        if (previousWindowMode != null) {
            windows.setEngineWindowMode(previousWindowMode);
            previousWindowMode = null;
        }
    }

    private void onFx(Runnable operation) {
        if (closed) return;
        if (Platform.isFxApplicationThread()) operation.run();
        else Platform.runLater(() -> { if (!closed) operation.run(); });
    }

    private static void updateMetrics(PerformanceDiagnosticsService.Metrics metrics, Label[] values) {
        values[0].setText(number(metrics.sourceFps()));
        values[1].setText(number(metrics.decodedFps()));
        values[2].setText(number(metrics.renderedFps()));
        values[3].setText(number(metrics.presentFps()));
    }

    private static String number(Double value) { return value == null ? "計測不可" : String.format(Locale.ROOT, "%.1f", value); }
    private static String gaps(Double p95, Double maximum) {
        return p95 == null || maximum == null ? "計測不可" : String.format(Locale.ROOT, "%.1f / %.1f ms", p95, maximum);
    }
    private static Label wrapped(String text) { Label label = new Label(text); label.setWrapText(true); return label; }
    private static void addFullValue(GridPane grid, int row, String name, Label value) {
        grid.add(wrapped(name), 0, row);
        value.setWrapText(true);
        grid.add(value, 1, row, 2, 1);
    }
    private static String modeLabel(WindowManager.EngineWindowMode mode) {
        if (mode == null) return "状態変更時のみ";
        return switch (mode) { case ON_CHANGE -> "状態変更時のみ"; case PERIODIC -> "毎秒再適用（従来方式）"; case SUSPENDED -> "診断中のウィンドウ制御を停止"; };
    }
    private static String completionLabel(PerformanceDiagnosticsService.Completion value) {
        return switch (value) { case NONE -> "未完了"; case MANUAL -> "手動停止"; case TIME_LIMIT -> "約60秒で自動停止";
            case ENGINE_STOPPED -> "受信停止・切断"; case ENGINE_FINISHED -> "エンジン側の計測終了"; case CLOSED -> "アプリ終了"; };
    }
    private static ComboBox<PerformanceDiagnosticsService.Signal> signalChoice() {
        ComboBox<PerformanceDiagnosticsService.Signal> result = new ComboBox<>();
        result.getItems().setAll(PerformanceDiagnosticsService.Signal.values());
        result.setValue(PerformanceDiagnosticsService.Signal.UNASSESSED);
        result.setConverter(new StringConverter<>() {
            @Override public String toString(PerformanceDiagnosticsService.Signal value) {
                if (value == null) return "未確認";
                return switch (value) { case UNASSESSED -> "未確認"; case NONE -> "なし"; case PRESENT -> "あり"; };
            }
            @Override public PerformanceDiagnosticsService.Signal fromString(String value) { return null; }
        });
        return result;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        diagnostics.close();
        restoreWindowMode();
        runningListener.accept(false);
        exports.shutdownNow();
    }
}
