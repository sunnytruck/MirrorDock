package jp.mirrordock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Opt-in, bounded diagnostic sessions; never records native output or device data. */
public final class PerformanceDiagnosticsService implements AutoCloseable {
    public interface EngineControl {
        boolean available();
        boolean start(long sessionId, int seconds);
        default boolean start(long sessionId, int seconds, boolean detailedPresent) { return start(sessionId, seconds); }
        void stop(long sessionId);
    }
    public enum Status { IDLE, RUNNING, COMPLETE }
    public enum Completion { NONE, MANUAL, TIME_LIMIT, ENGINE_STOPPED, ENGINE_FINISHED, CLOSED }
    public enum Signal { UNASSESSED, NONE, PRESENT }
    public enum Latency { UNASSESSED, ACCEPTABLE, NOTICEABLE }
    public record Observations(Signal audioSync, Signal visualStutter, Latency latency) {
        public Observations { Objects.requireNonNull(audioSync); Objects.requireNonNull(visualStutter); Objects.requireNonNull(latency); }
        public static Observations defaults() { return new Observations(Signal.UNASSESSED, Signal.UNASSESSED, Latency.UNASSESSED); }
    }
    public record Context(int requestedFps, String windowModeName, boolean audio, boolean detailedPresent) {
        public Context(int requestedFps, String windowModeName, boolean audio) { this(requestedFps, windowModeName, audio, false); }
        public Context {
            if (requestedFps != 30 && requestedFps != 60) throw new IllegalArgumentException("Requested FPS must be 30 or 60");
            if (!List.of("ON_CHANGE", "PERIODIC", "SUSPENDED").contains(windowModeName))
                throw new IllegalArgumentException("Unknown diagnostic window mode");
        }
    }
    public record Metrics(Double sourceFps, Double decodedFps, Double renderedFps, Double presentFps,
                          Long sinkDroppedFrames,
                          Double sourceGapP95Millis, Double sourceGapMaxMillis,
                          Double decodeGapP95Millis, Double decodeGapMaxMillis,
                          Double presentGapP95Millis, Double presentGapMaxMillis,
                          String decoder) {
        public static Metrics unavailable() { return new Metrics(null, null, null, null, null, null, null, null, null, null, null, null); }
    }
    /** At most one point per native elapsed-second bucket, bounded to the most recent 64 points. */
    public record SamplePoint(long elapsedMillis, long nativeIntervalMillis, PerformanceProtocol.Sample sample, Metrics interval) {}
    public record Snapshot(Status status, Context context, long sessionId, long elapsedMillis, long limitMillis,
                           boolean engineAvailable, PerformanceProtocol.Sample lastSample,
                           Metrics current, Metrics average, int samples, boolean countersReset, Completion completion,
                           List<SamplePoint> history) {
        public Snapshot { history = List.copyOf(history); }
    }

    private static final AtomicLong SESSION_IDS = new AtomicLong(1);
    private final EngineControl engine;
    // Engine operations can cross UI/timer threads. Ordering prevents a delayed
    // START from enabling probes after the matching STOP has already finished.
    private final Object engineCommands = new Object();
    private final ScheduledExecutorService timer;
    private final LongSupplier nanoTime;
    private Consumer<Snapshot> listener = ignored -> {};
    private Status status = Status.IDLE;
    private Completion completion = Completion.NONE;
    private Context context = new Context(30, "ON_CHANGE", true);
    private long sessionId, startedNanos, elapsedAtFinish, limitMillis;
    private boolean available, nativeRequested, closed, countersReset;
    private int sampleCount;
    private PerformanceProtocol.Sample lastSample;
    private final java.util.ArrayList<SamplePoint> history = new java.util.ArrayList<>(64);
    private Metrics current = Metrics.unavailable(), average = Metrics.unavailable();
    private final Accumulator source = new Accumulator(), decoded = new Accumulator(), rendered = new Accumulator(), present = new Accumulator();
    private ScheduledFuture<?> tickTask, deadlineTask;

    public PerformanceDiagnosticsService(EngineControl engine) {
        this(engine, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "performance-diagnostics"); thread.setDaemon(true); return thread;
        }), System::nanoTime);
    }

    PerformanceDiagnosticsService(EngineControl engine, ScheduledExecutorService timer, LongSupplier nanoTime) {
        this.engine = Objects.requireNonNull(engine); this.timer = Objects.requireNonNull(timer); this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    /** Callback can run on the caller or timer thread. JavaFX callers must dispatch to FX. */
    public void setListener(Consumer<Snapshot> listener) { synchronized (this) { this.listener = Objects.requireNonNull(listener); } }

    public long start(Context context, Duration duration) {
        Objects.requireNonNull(context); Objects.requireNonNull(duration);
        if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Diagnostic duration must be at most 60 seconds");
        long milliseconds = duration.toMillis();
        if (milliseconds < 1 || milliseconds > 60_000) throw new IllegalArgumentException("Diagnostic duration must be at most 60 seconds");
        long id;
        boolean request;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Diagnostic service is closed");
            if (status == Status.RUNNING) throw new IllegalStateException("A diagnostic session is already running");
            this.context = context; this.limitMillis = milliseconds;
            id = nextSessionId(); this.sessionId = id;
            startedNanos = nanoTime.getAsLong(); elapsedAtFinish = 0;
            status = Status.RUNNING; completion = Completion.NONE; sampleCount = 0; countersReset = false;
            lastSample = null; history.clear(); current = Metrics.unavailable(); average = Metrics.unavailable();
            source.clear(); decoded.clear(); rendered.clear(); present.clear();
            available = safelyAvailable(); request = available; nativeRequested = request;
            tickTask = timer.scheduleAtFixedRate(() -> tick(id), 1, 1, TimeUnit.SECONDS);
            deadlineTask = timer.schedule(() -> finish(id, Completion.TIME_LIMIT), milliseconds, TimeUnit.MILLISECONDS);
        }
        if (request) requestNativeStart(id, seconds(milliseconds));
        publish();
        return id;
    }

    public void stop() { long id; synchronized (this) { id = sessionId; } finish(id, Completion.MANUAL); }
    public void engineStopped() { long id; synchronized (this) { id = sessionId; } finish(id, Completion.ENGINE_STOPPED); }
    public synchronized boolean running() { return status == Status.RUNNING; }
    public synchronized Snapshot snapshot() { return snapshotLocked(); }

    public void accept(PerformanceProtocol.Message message) {
        Objects.requireNonNull(message);
        if (message instanceof PerformanceProtocol.Ready) {
            long id; int seconds; boolean request;
            synchronized (this) {
                if (closed || status != Status.RUNNING || nativeRequested) return;
                available = safelyAvailable(); request = available;
                nativeRequested = request; id = sessionId; seconds = seconds(Math.max(1, limitMillis - elapsedLocked()));
            }
            if (request) requestNativeStart(id, seconds);
            publish(); return;
        }
        if (message instanceof PerformanceProtocol.Done done) { finish(done.sessionId(), Completion.ENGINE_FINISHED); return; }
        boolean changed = false;
        synchronized (this) {
            if (closed || status != Status.RUNNING || !nativeRequested) return;
            if (message instanceof PerformanceProtocol.Started started) {
                if (started.sessionId() != sessionId) return;
                available = true; changed = true;
            } else if (message instanceof PerformanceProtocol.Sample sample) {
                if (sample.sessionId() != sessionId || (lastSample != null && sample.elapsedMillis() <= lastSample.elapsedMillis())) return;
                receive(sample); changed = true;
            }
        }
        if (changed) publish();
    }

    private void receive(PerformanceProtocol.Sample sample) {
        long previousMillis = lastSample == null ? 0 : lastSample.elapsedMillis();
        boolean segmentChanged = lastSample != null && sample.segment() != lastSample.segment();
        if (segmentChanged) countersReset = true;
        long elapsed = sample.elapsedMillis() - previousMillis;
        Double sourceRate = source.add(sample.sourceFrames(), lastSample == null ? Long.valueOf(0) : lastSample.sourceFrames(), elapsed, segmentChanged);
        Double decodedRate = decoded.add(sample.decodedFrames(), lastSample == null ? Long.valueOf(0) : lastSample.decodedFrames(), elapsed, segmentChanged);
        Double renderedRate = rendered.add(sample.renderedFrames(), lastSample == null ? Long.valueOf(0) : lastSample.renderedFrames(), elapsed, segmentChanged);
        Double presentRate = present.add(sample.presentCalls(), lastSample == null ? Long.valueOf(0) : lastSample.presentCalls(), elapsed, segmentChanged);
        if (source.reset || decoded.reset || rendered.reset || present.reset) countersReset = true;
        if (lastSample != null && sample.sinkDroppedFrames() != null && lastSample.sinkDroppedFrames() != null
                && sample.sinkDroppedFrames() < lastSample.sinkDroppedFrames()) countersReset = true;
        current = metrics(sample, sourceRate, decodedRate, renderedRate, presentRate);
        average = metrics(sample, source.rate(), decoded.rate(), rendered.rate(), present.rate());
        appendHistory(sample);
        lastSample = sample; sampleCount++; available = true;
    }

    private void appendHistory(PerformanceProtocol.Sample sample) {
        int last = history.size() - 1;
        boolean replace = last >= 0 && history.get(last).sample().elapsedMillis() / 1000 == sample.elapsedMillis() / 1000;
        int previousIndex = replace ? last - 1 : last;
        PerformanceProtocol.Sample previous = previousIndex < 0 ? null : history.get(previousIndex).sample();
        long intervalMillis = sample.elapsedMillis() - (previous == null ? 0 : previous.elapsedMillis());
        boolean changed = previous != null && previous.segment() != sample.segment();
        Metrics interval = metrics(sample,
                sampleRate(sample.sourceFrames(), previous == null ? Long.valueOf(0) : previous.sourceFrames(), intervalMillis, changed),
                sampleRate(sample.decodedFrames(), previous == null ? Long.valueOf(0) : previous.decodedFrames(), intervalMillis, changed),
                sampleRate(sample.renderedFrames(), previous == null ? Long.valueOf(0) : previous.renderedFrames(), intervalMillis, changed),
                sampleRate(sample.presentCalls(), previous == null ? Long.valueOf(0) : previous.presentCalls(), intervalMillis, changed));
        SamplePoint point = new SamplePoint(elapsedLocked(), intervalMillis, sample, interval);
        if (replace) history.set(last, point);
        else {
            if (history.size() == 64) history.remove(0);
            history.add(point);
        }
    }

    private static Double sampleRate(Long count, Long previous, long elapsed, boolean segmentChanged) {
        return count == null || previous == null || elapsed <= 0 || segmentChanged || count < previous
                ? null : 1000.0 * (count - previous) / elapsed;
    }

    private static Metrics metrics(PerformanceProtocol.Sample sample, Double source, Double decoded, Double rendered, Double present) {
        return new Metrics(source, decoded, rendered, present, sample.sinkDroppedFrames(),
                sample.sourceGapP95Millis(), sample.sourceGapMaxMillis(), sample.decodeGapP95Millis(), sample.decodeGapMaxMillis(),
                sample.presentGapP95Millis(), sample.presentGapMaxMillis(), sample.decoder());
    }

    private void tick(long id) {
        synchronized (this) { if (closed || sessionId != id || status != Status.RUNNING) return; }
        publish();
    }

    private void finish(long id, Completion reason) {
        boolean sendStop;
        synchronized (this) {
            if (sessionId != id || status != Status.RUNNING) return;
            elapsedAtFinish = elapsedLocked(); status = Status.COMPLETE; completion = reason;
            sendStop = nativeRequested; nativeRequested = false;
            if (tickTask != null) tickTask.cancel(false);
            if (deadlineTask != null) deadlineTask.cancel(false);
            tickTask = null; deadlineTask = null;
        }
        if (sendStop) safelyStop(id);
        publish();
    }

    private synchronized Snapshot snapshotLocked() {
        return new Snapshot(status, context, sessionId, status == Status.RUNNING ? elapsedLocked() : elapsedAtFinish,
                limitMillis, available, lastSample, current, average, sampleCount, countersReset, completion, history);
    }
    private long elapsedLocked() { return Math.min(limitMillis, Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - startedNanos))); }
    private void publish() {
        Consumer<Snapshot> callback; Snapshot snapshot;
        synchronized (this) { callback = listener; snapshot = snapshotLocked(); }
        try { callback.accept(snapshot); } catch (RuntimeException ignored) { /* A display listener must not stop the receiver. */ }
    }
    private boolean safelyAvailable() { try { return engine.available(); } catch (RuntimeException failure) { return false; } }
    private boolean safelyStart(long id, int seconds, boolean detailedPresent) {
        try { return engine.start(id, seconds, detailedPresent); } catch (RuntimeException failure) { return false; }
    }
    private void requestNativeStart(long id, int seconds) {
        synchronized (engineCommands) {
            boolean detailedPresent;
            synchronized (this) {
                if (closed || sessionId != id || status != Status.RUNNING || !nativeRequested) return;
                detailedPresent = context.detailedPresent();
            }
            if (!safelyStart(id, seconds, detailedPresent)) {
                synchronized (this) { if (sessionId == id && status == Status.RUNNING) { available = false; nativeRequested = false; } }
            }
        }
    }
    private void safelyStop(long id) {
        synchronized (engineCommands) {
            try { engine.stop(id); } catch (RuntimeException ignored) { /* Process termination independently stops probes. */ }
        }
    }
    private static int seconds(long millis) { return (int) Math.min(60, Math.max(1, (millis + 999) / 1000)); }
    private static long nextSessionId() { return SESSION_IDS.getAndUpdate(value -> value == Long.MAX_VALUE ? 1 : value + 1); }

    /** Saves only bounded numeric measurements, fixed enums and known factory identifiers. */
    public void saveReport(Path destination, Observations observations) throws IOException {
        Objects.requireNonNull(destination); Objects.requireNonNull(observations);
        Snapshot snapshot = snapshot();
        if (snapshot.status() == Status.IDLE) throw new IllegalStateException("No diagnostic session to save");
        String report = report(snapshot, observations);
        Path target = destination.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("診断結果の保存先フォルダーが見つかりません。");
        Path temporary = Files.createTempFile(parent, ".mirrordock-performance-", ".tmp");
        try {
            Files.writeString(temporary, report, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String report(Snapshot s, Observations observations) {
        StringBuilder out = new StringBuilder("{\n  \"format\": \"MirrorDock-performance-1\",\n");
        field(out, "status", s.status().name(), true); field(out, "completion", s.completion().name(), true);
        number(out, "requested_fps_limit", s.context().requestedFps(), true);
        field(out, "window_mode", s.context().windowModeName(), true);
        out.append("  \"audio_enabled\": ").append(s.context().audio()).append(",\n");
        out.append("  \"detailed_present_enabled\": ").append(s.context().detailedPresent()).append(",\n");
        number(out, "duration_ms", s.elapsedMillis(), true); number(out, "native_sample_count", s.samples(), true);
        out.append("  \"engine_metrics_available\": ").append(s.engineAvailable()).append(",\n");
        out.append("  \"counter_reset_detected\": ").append(s.countersReset()).append(",\n");
        field(out, "average_scope", "verified measurement intervals; reset boundaries excluded", true);
        field(out, "receive_fps_definition", "parsed compressed access units; not TCP packets", true);
        field(out, "render_fps_definition", "sink processed frames; not physical monitor presentation", true);
        field(out, "present_definition", "d3d11videosink pre-Present callbacks; may include redraws; not physical display frames", true);
        field(out, "gap_scope", "p95: latest up to 512 intervals; max: latest native segment; pipeline reset clears intervals and counters", true);
        field(out, "sink_dropped_scope", "latest native segment cumulative counter; pipeline reset clears counters", true);
        field(out, "segment_change_scope", "includes first pipeline binding; not proof of a playback failure", true);
        field(out, "physical_display_fps", "計測不可", true);
        field(out, "decoder_dropped_frames", "計測不可", true);
        field(out, "network_delay_ms", "計測不可", true);
        field(out, "end_to_end_latency_ms", "計測不可", true);
        field(out, "audio_offset_ms", "計測不可", true);
        field(out, "audio_sync_observation", observations.audioSync().name(), true);
        field(out, "visual_stutter_observation", observations.visualStutter().name(), true);
        field(out, "latency_observation", observations.latency().name(), true);
        out.append("  \"average\": "); appendMetrics(out, s.average()); out.append(",\n  \"latest_interval\": "); appendMetrics(out, s.current());
        out.append(",\n  \"series\": [");
        for (int i = 0; i < s.history().size(); i++) {
            SamplePoint point = s.history().get(i);
            PerformanceProtocol.Sample sample = point.sample();
            out.append(i == 0 ? "\n" : ",\n").append("    {\n");
            number(out, "diagnostic_elapsed_ms", point.elapsedMillis(), true);
            number(out, "native_elapsed_ms", sample.elapsedMillis(), true);
            number(out, "native_interval_ms", point.nativeIntervalMillis(), true);
            number(out, "segment", sample.segment(), true);
            measurement(out, "receive_au_total", sample.sourceFrames());
            measurement(out, "decoded_total", sample.decodedFrames());
            measurement(out, "rendered_total", sample.renderedFrames());
            measurement(out, "present_calls_total", sample.presentCalls());
            out.append("      \"interval\": "); appendMetrics(out, point.interval());
            out.append("    }");
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static void appendMetrics(StringBuilder out, Metrics m) {
        out.append("{\n");
        measurement(out, "receive_au_fps", m.sourceFps()); measurement(out, "decode_fps", m.decodedFps());
        measurement(out, "sink_render_fps", m.renderedFps()); measurement(out, "present_calls_fps", m.presentFps());
        measurement(out, "sink_dropped_frames", m.sinkDroppedFrames());
        measurement(out, "receive_gap_p95_ms", m.sourceGapP95Millis()); measurement(out, "receive_gap_max_ms", m.sourceGapMaxMillis());
        measurement(out, "decode_gap_p95_ms", m.decodeGapP95Millis()); measurement(out, "decode_gap_max_ms", m.decodeGapMaxMillis());
        measurement(out, "present_gap_p95_ms", m.presentGapP95Millis()); measurement(out, "present_gap_max_ms", m.presentGapMaxMillis());
        field(out, "decoder", m.decoder() == null ? "計測不可" : m.decoder(), false);
        out.append("  }");
    }
    private static void measurement(StringBuilder out, String name, Number value) {
        out.append("    \"").append(name).append("\": ");
        if (value == null || (value instanceof Double d && !Double.isFinite(d))) out.append("\"計測不可\"");
        else out.append(value);
        out.append(",\n");
    }
    private static void number(StringBuilder out, String name, long value, boolean comma) { out.append("  \"").append(name).append("\": ").append(value).append(comma ? ",\n" : "\n"); }
    private static void field(StringBuilder out, String name, String value, boolean comma) {
        // All strings originate from fixed enums/constants or validated factory identifiers.
        out.append("  \"").append(name).append("\": \"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"').append(comma ? ",\n" : "\n");
    }

    @Override public void close() {
        long id; synchronized (this) { if (closed) return; closed = true; id = sessionId; }
        finish(id, Completion.CLOSED); timer.shutdownNow();
    }

    private static final class Accumulator {
        private long frames, millis;
        private boolean reset;
        void clear() { frames = 0; millis = 0; reset = false; }
        Double add(Long count, Long previous, long elapsed, boolean segmentChanged) {
            reset = false;
            if (count == null || previous == null || elapsed <= 0 || segmentChanged) return null;
            if (count < previous) { reset = true; return null; }
            long delta = count - previous;
            frames += delta; millis += elapsed;
            return 1000.0 * delta / elapsed;
        }
        Double rate() { return millis == 0 ? null : 1000.0 * frames / millis; }
    }
}
