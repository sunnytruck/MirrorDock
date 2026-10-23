package jp.mirrordock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static jp.mirrordock.PerformanceDiagnosticsService.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceDiagnosticsServiceTest {
    @TempDir Path temporary;
    private static final Context CONTEXT = new Context(60, "ON_CHANGE", true);

    @Test void requestedFpsIsNotAReceivedFpsAndOldEnginesStillHaveABoundedTimer() {
        FakeEngine engine = new FakeEngine(); engine.available = false;
        ManualTimer timer = new ManualTimer();
        List<Snapshot> events = new ArrayList<>();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            service.setListener(events::add);
            service.start(CONTEXT, Duration.ofSeconds(60));
            assertNull(service.snapshot().current().sourceFps());
            timer.advance(30_000);
            assertEquals(30_000, service.snapshot().elapsedMillis());
            assertTrue(events.size() >= 30, "Running sessions publish progress at least once a second");
            timer.advance(30_000);
            assertFalse(service.running());
            assertEquals(Completion.TIME_LIMIT, service.snapshot().completion());
            assertNull(service.snapshot().average().decodedFps());
            assertEquals(60, service.snapshot().context().requestedFps());
            assertTrue(engine.starts.isEmpty()); assertTrue(engine.stops.isEmpty());
        }
    }

    @Test void computesMeasuredIntervalAndWeightedSessionRatesWithoutAveragingPercentiles() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            service.accept(sample(id, 1000, 1, 60, 59, 58, 2));
            service.accept(sample(id, 3000, 1, 170, 167, 160, 10));
            Snapshot s = service.snapshot();
            assertEquals(55.0, s.current().sourceFps());
            assertEquals(170_000.0 / 3000, s.average().sourceFps());
            assertEquals(51.0, s.current().renderedFps());
            assertEquals(160_000.0 / 3000, s.average().renderedFps());
            assertEquals(10, s.current().sinkDroppedFrames());
            assertEquals(10, s.average().sinkDroppedFrames());
            assertEquals(17.0, s.average().sourceGapP95Millis());
            assertEquals("d3d11h264dec", s.current().decoder());
            assertNotEquals((double) s.context().requestedFps(), s.current().sourceFps());
        }
    }

    @Test void excludesResetBoundariesAndRejectsStaleAndOutOfOrderSamples() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            service.accept(sample(id, 1000, 1, 60, 60, 60, 0));
            service.accept(sample(id + 1, 2000, 1, 120, 120, 120, 0));
            service.accept(sample(id, 500, 1, 30, 30, 30, 0));
            assertEquals(1, service.snapshot().samples());
            service.accept(sample(id, 2000, 2, 5, 5, 5, 0));
            assertTrue(service.snapshot().countersReset());
            assertNull(service.snapshot().current().sourceFps());
            assertEquals(60.0, service.snapshot().average().sourceFps());
            service.accept(sample(id, 3000, 2, 35, 35, 35, 0));
            assertEquals(30.0, service.snapshot().current().sourceFps());
            assertEquals(45.0, service.snapshot().average().sourceFps());
            service.accept(sample(id, 4000, 2, 2, 2, 2, 0));
            assertNull(service.snapshot().current().sourceFps());
            assertEquals(45.0, service.snapshot().average().sourceFps());
        }
    }

    @Test void manualStopAndEngineExitCancelTimersAndIgnoreAllOldSessionMessages() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long first = service.start(CONTEXT, Duration.ofSeconds(60));
            assertThrows(IllegalStateException.class, () -> service.start(CONTEXT, Duration.ofSeconds(60)));
            timer.advance(1200); service.stop(); service.stop();
            assertEquals(Completion.MANUAL, service.snapshot().completion());
            assertEquals(1200, service.snapshot().elapsedMillis());
            assertEquals(List.of(first), engine.stops);
            long second = service.start(new Context(30, "PERIODIC", false), Duration.ofSeconds(60));
            service.accept(new PerformanceProtocol.Done(first));
            service.accept(sample(first, 2000, 0, 60, 60, 60, 0));
            assertTrue(service.running()); assertEquals(0, service.snapshot().samples());
            service.engineStopped();
            assertEquals(Completion.ENGINE_STOPPED, service.snapshot().completion());
            timer.advance(120_000);
            assertEquals(List.of(first, second), engine.stops);
            assertEquals(Completion.ENGINE_STOPPED, service.snapshot().completion());
        }
    }

    @Test void lateReadyStartsOnlyTheRemainingDiagnosticPeriodAndUnknownFieldsRemainUnknown() {
        FakeEngine engine = new FakeEngine(); engine.available = false;
        ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            timer.advance(30_200); engine.available = true;
            service.accept(new PerformanceProtocol.Ready());
            assertEquals(List.of(new Start(id, 30)), engine.starts);
            service.accept(new PerformanceProtocol.Ready());
            assertEquals(1, engine.starts.size());
            service.accept(new PerformanceProtocol.Sample(id, 1000, 0, 50L, null, null, null, null,
                    null, null, null, null, null, null, "unknown"));
            assertEquals(50.0, service.snapshot().average().sourceFps());
            assertNull(service.snapshot().average().decodedFps());
            assertNull(service.snapshot().average().decoder());
        }
    }

    @Test void engineControlOrDisplayFailuresDoNotBreakTheDiagnosticTimer() {
        ManualTimer timer = new ManualTimer();
        EngineControl failed = new EngineControl() {
            public boolean available() { return true; }
            public boolean start(long id, int seconds) { throw new IllegalStateException("private native details"); }
            public void stop(long id) { throw new IllegalStateException("private native details"); }
        };
        try (PerformanceDiagnosticsService service = new PerformanceDiagnosticsService(failed, timer, timer.nanos::get)) {
            service.setListener(snapshot -> { throw new IllegalStateException("closed display"); });
            service.start(CONTEXT, Duration.ofSeconds(60));
            assertFalse(service.snapshot().engineAvailable());
            timer.advance(60_000);
            assertEquals(Completion.TIME_LIMIT, service.snapshot().completion());
        }
    }

    @Test void reportContainsOnlyFixedContextMeasurementsAndExplicitUnknowns() throws Exception {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        Path report = temporary.resolve("private-file-name.json");
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            assertThrows(IllegalStateException.class, () -> service.saveReport(report, Observations.defaults()));
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            service.accept(new PerformanceProtocol.Sample(id, 1000, 0, 59L, null, null, null, null,
                    null, null, null, null, null, null, "private-device-token"));
            service.stop();
            service.saveReport(report, new Observations(Signal.NONE, Signal.PRESENT, Latency.ACCEPTABLE));
            String text = Files.readString(report);
            assertTrue(text.contains("\"requested_fps_limit\": 60"));
            assertTrue(text.contains("\"detailed_present_enabled\": false"));
            assertTrue(text.contains("\"receive_au_fps\": 59.0"));
            assertTrue(text.contains("\"decode_fps\": \"計測不可\""));
            assertTrue(text.contains("\"network_delay_ms\": \"計測不可\""));
            assertTrue(text.contains("\"latency_observation\": \"ACCEPTABLE\""));
            assertTrue(text.contains("\"series\": ["));
            assertTrue(text.contains("\"receive_au_total\": 59"));
            assertTrue(text.contains("latest up to 512 intervals"));
            assertTrue(text.contains("latest native segment cumulative counter; pipeline reset clears counters"));
            for (String sensitive : List.of("private-device-token", "private-file-name", "sessionId", "receiver", "executable", "caps", "192.168", "secret-key"))
                assertFalse(text.contains(sensitive), "Unexpected report data: " + sensitive);
            try (var files = Files.list(temporary)) { assertEquals(List.of(report), files.toList()); }
            assertThrows(IllegalArgumentException.class, () -> new Context(60, "C:\\Users\\private-device", true));
            assertThrows(IllegalArgumentException.class, () -> new Context(45, "ON_CHANGE", true));
        }
    }

    @Test void timeSeriesUsesImmutableOneSecondBucketsAndDoesNotBridgePipelineReset() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            service.accept(sample(id, 1000, 1, 60, 60, 60, 0));
            Snapshot first = service.snapshot();
            service.accept(sample(id, 1500, 1, 90, 90, 90, 0));
            service.accept(sample(id, 2000, 1, 100, 100, 100, 0));
            service.accept(sample(id, 2500, 1, 140, 140, 140, 0));
            service.accept(sample(id, 2500, 1, 200, 200, 200, 0));
            List<SamplePoint> history = service.snapshot().history();
            assertEquals(2, history.size());
            assertEquals(1500, history.getFirst().sample().elapsedMillis());
            assertEquals(50.0, history.getLast().interval().sourceFps());
            assertEquals(1000, history.getLast().nativeIntervalMillis());
            assertEquals(1, first.history().size());
            assertEquals(1000, first.history().getFirst().sample().elapsedMillis());
            assertThrows(UnsupportedOperationException.class, () -> history.clear());
            service.accept(sample(id, 3000, 2, 1, 1, 1, 0));
            assertNull(service.snapshot().history().getLast().interval().sourceFps());
            service.accept(sample(id, 4000, 2, 61, 61, 61, 0));
            assertEquals(60.0, service.snapshot().history().getLast().interval().sourceFps());
        }
    }

    @Test void unexpectedNativeCadenceCannotMakeTheHistoryOrExportUnbounded() throws Exception {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        Path report = temporary.resolve("bounded.json");
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            // The local session timer is authoritative. Even malformed native timing
            // within the protocol's bounded range cannot grow its retained history.
            for (int second = 1; second <= 120; second++)
                service.accept(sample(id, second * 1000L, 1, second * 60L, second * 60L, second * 60L, 0));
            assertEquals(64, service.snapshot().history().size());
            service.stop();
            service.accept(sample(id, 120_000, 2, 0, 0, 0, 0));
            service.saveReport(report, Observations.defaults());
            String text = Files.readString(report);
            assertEquals(64, text.split("\"native_elapsed_ms\"", -1).length - 1);
            assertTrue(Files.size(report) < 100_000);
            service.start(CONTEXT, Duration.ofSeconds(60));
            assertTrue(service.snapshot().history().isEmpty());
        }
    }

    @Test void detailedPresentationHooksAreRequestedOnlyByAnExplicitOptIn() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            assertFalse(CONTEXT.detailedPresent());
            service.start(CONTEXT, Duration.ofSeconds(60)); service.stop();
            service.start(new Context(60, "ON_CHANGE", true, true), Duration.ofSeconds(60)); service.stop();
            assertEquals(List.of(false, true), engine.detailedRequests);
        }
    }

    @Test void consecutiveUnknownCountersNeverUnboxNullAndLaterValidIntervalsStillMeasure() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            long id = service.start(CONTEXT, Duration.ofSeconds(60));
            for (long millis : new long[]{0, 1000, 1500, 2000}) {
                assertDoesNotThrow(() -> service.accept(new PerformanceProtocol.Sample(id, millis, 0,
                        null, null, null, null, null, null, null, null, null, null, null, "unknown")));
            }
            assertEquals(4, service.snapshot().samples());
            assertNull(service.snapshot().average().sourceFps());
            assertNull(service.snapshot().average().decodedFps());
            assertNull(service.snapshot().average().renderedFps());
            assertNull(service.snapshot().average().presentFps());
            for (long millis : new long[]{3000, 4000, 4500, 5000}) {
                long frames = (millis - 3000) * 60 / 1000;
                assertDoesNotThrow(() -> service.accept(new PerformanceProtocol.Sample(id, millis, 1,
                        frames, frames, frames, 0L, null, null, null, null, null, null, null, "avdec_h264")));
            }
            assertEquals(60.0, service.snapshot().average().sourceFps());
            assertEquals(60.0, service.snapshot().current().renderedFps());
            assertNull(service.snapshot().average().presentFps());
            assertNull(service.snapshot().history().getLast().interval().presentFps());
        }
    }

    @Test void closeStopsOnlyOnceAndPreventsRestart() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        PerformanceDiagnosticsService service = service(engine, timer);
        long id = service.start(CONTEXT, Duration.ofSeconds(60));
        service.close(); service.close(); timer.advance(60_000);
        assertEquals(Completion.CLOSED, service.snapshot().completion());
        assertEquals(List.of(id), engine.stops);
        assertThrows(IllegalStateException.class, () -> service.start(CONTEXT, Duration.ofSeconds(60)));
    }

    @Test void timeoutStopsTheNativeProbesOnceAndLateDoneCannotReplaceTheCompletionReason() {
        FakeEngine engine = new FakeEngine(); ManualTimer timer = new ManualTimer();
        try (PerformanceDiagnosticsService service = service(engine, timer)) {
            assertThrows(IllegalArgumentException.class, () -> service.start(CONTEXT, Duration.ofSeconds(60).plusNanos(1)));
            long id = service.start(CONTEXT, Duration.ofSeconds(2));
            timer.advance(2000);
            service.accept(new PerformanceProtocol.Done(id));
            service.stop(); timer.advance(60_000);
            assertEquals(List.of(id), engine.stops);
            assertEquals(Completion.TIME_LIMIT, service.snapshot().completion());
        }
    }

    @Test void anInFlightNativeStartCannotReenableProbesAfterManualStop() throws Exception {
        CountDownLatch enteredStart = new CountDownLatch(1), releaseStart = new CountDownLatch(1), finishCanceledTimers = new CountDownLatch(1);
        List<String> commands = new CopyOnWriteArrayList<>();
        ManualTimer timer = new ManualTimer(); timer.onCancel = finishCanceledTimers::countDown;
        EngineControl blocked = new EngineControl() {
            public boolean available() { return true; }
            public boolean start(long id, int seconds) {
                enteredStart.countDown();
                try { if (!releaseStart.await(2, TimeUnit.SECONDS)) throw new AssertionError("Test did not release native START"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                commands.add("START"); return true;
            }
            public void stop(long id) { commands.add("STOP"); }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (PerformanceDiagnosticsService service = new PerformanceDiagnosticsService(blocked, timer, timer.nanos::get)) {
            Future<?> start = workers.submit(() -> service.start(CONTEXT, Duration.ofSeconds(60)));
            assertTrue(enteredStart.await(2, TimeUnit.SECONDS));
            Future<?> stop = workers.submit(service::stop);
            assertTrue(finishCanceledTimers.await(2, TimeUnit.SECONDS));
            assertEquals(Status.COMPLETE, service.snapshot().status());
            releaseStart.countDown();
            start.get(2, TimeUnit.SECONDS); stop.get(2, TimeUnit.SECONDS);
            assertEquals(List.of("START", "STOP"), commands);
            assertEquals(Completion.MANUAL, service.snapshot().completion());
        } finally { releaseStart.countDown(); workers.shutdownNow(); }
    }

    private static PerformanceDiagnosticsService service(FakeEngine engine, ManualTimer timer) {
        return new PerformanceDiagnosticsService(engine, timer, timer.nanos::get);
    }
    private static PerformanceProtocol.Sample sample(long id, long millis, long segment, long source, long decoded, long rendered, long dropped) {
        return new PerformanceProtocol.Sample(id, millis, segment, source, decoded, rendered, dropped, rendered,
                17.0, 27.0, 18.0, 28.0, 19.0, 29.0, "d3d11h264dec");
    }
    private record Start(long id, int seconds) {}
    private static final class FakeEngine implements EngineControl {
        boolean available = true;
        final List<Start> starts = new ArrayList<>();
        final List<Boolean> detailedRequests = new ArrayList<>();
        final List<Long> stops = new ArrayList<>();
        public boolean available() { return available; }
        public boolean start(long id, int seconds) { starts.add(new Start(id, seconds)); return true; }
        public boolean start(long id, int seconds, boolean detailed) { detailedRequests.add(detailed); return start(id, seconds); }
        public void stop(long id) { stops.add(id); }
    }

    /** Deterministic clock and timer: no real waits are needed to exercise minute-long sessions. */
    private static final class ManualTimer extends ScheduledThreadPoolExecutor {
        final AtomicLong nanos = new AtomicLong();
        final List<Task> tasks = new ArrayList<>();
        Runnable onCancel = () -> {};
        ManualTimer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Task task = new Task(command, nanos.get() + unit.toNanos(delay), 0); tasks.add(task); return task;
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
            Task task = new Task(command, nanos.get() + unit.toNanos(initial), unit.toNanos(period)); tasks.add(task); return task;
        }
        void advance(long millis) {
            long until = nanos.get() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                Task next = tasks.stream().filter(t -> !t.cancelled && !t.done && t.due <= until).min(Comparator.comparingLong(t -> t.due)).orElse(null);
                if (next == null) break;
                nanos.set(next.due); next.command.run();
                if (next.period == 0) next.done = true; else next.due += next.period;
            }
            nanos.set(until);
        }
        private final class Task implements ScheduledFuture<Object> {
            final Runnable command; final long period; long due; boolean cancelled, done;
            Task(Runnable command, long due, long period) { this.command = command; this.due = due; this.period = period; }
            public long getDelay(TimeUnit unit) { return unit.convert(due - nanos.get(), TimeUnit.NANOSECONDS); }
            public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
            public boolean cancel(boolean interrupt) { cancelled = true; onCancel.run(); return true; }
            public boolean isCancelled() { return cancelled; }
            public boolean isDone() { return cancelled || done; }
            public Object get() { throw new UnsupportedOperationException(); }
            public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        }
    }
}
