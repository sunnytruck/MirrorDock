package jp.mirrordock;

import java.util.Optional;
import java.util.regex.Pattern;

/** Numeric-only, versioned messages from the private engine control pipe. */
public final class PerformanceProtocol {
    private static final int MAX_LINE = 768;
    private static final long MAX_COUNTER = 1_000_000_000_000L;
    private static final long MAX_GAP_MICROS = 60_000_000L;
    private static final Pattern NUMBER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern FACTORY = Pattern.compile("[a-z][a-z0-9_+\\-]{0,63}");
    // Export only factory identifiers supported by this Windows distribution.
    // A token which happens to look like a factory is not arbitrary report text.
    private static final Pattern WINDOWS_DECODER = Pattern.compile(
            "avdec_h26[45]|d3d1[12]h26[45](?:device[0-9]{1,3})?dec|nv(?:d3d11)?h26[45]dec|openh264dec");

    private PerformanceProtocol() {}

    public sealed interface Message permits Ready, Started, Sample, Done {}
    public record Ready() implements Message {}
    public record Started(long sessionId) implements Message { public Started { positiveSession(sessionId); } }
    public record Done(long sessionId) implements Message { public Done { positiveSession(sessionId); } }
    public record Sample(long sessionId, long elapsedMillis, long segment,
                         Long sourceFrames, Long decodedFrames, Long renderedFrames,
                         Long sinkDroppedFrames, Long presentCalls,
                         Double sourceGapP95Millis, Double sourceGapMaxMillis,
                         Double decodeGapP95Millis, Double decodeGapMaxMillis,
                         Double presentGapP95Millis, Double presentGapMaxMillis,
                         String decoder) implements Message {
        public Sample {
            positiveSession(sessionId);
            if (elapsedMillis < 0 || elapsedMillis > 120_000 || segment < 0 || segment > 1_000_000)
                throw new IllegalArgumentException("Invalid diagnostic sample bounds");
            validCounter(sourceFrames); validCounter(decodedFrames); validCounter(renderedFrames);
            validCounter(sinkDroppedFrames); validCounter(presentCalls);
            validGap(sourceGapP95Millis); validGap(sourceGapMaxMillis);
            validGap(decodeGapP95Millis); validGap(decodeGapMaxMillis);
            validGap(presentGapP95Millis); validGap(presentGapMaxMillis);
            ordered(sourceGapP95Millis, sourceGapMaxMillis); ordered(decodeGapP95Millis, decodeGapMaxMillis);
            ordered(presentGapP95Millis, presentGapMaxMillis);
            if (decoder != null) {
                if (!FACTORY.matcher(decoder).matches()) throw new IllegalArgumentException("Invalid decoder factory identifier");
                decoder = WINDOWS_DECODER.matcher(decoder).matches() ? decoder : null;
            }
        }
    }

    /** Invalid and unrelated output is discarded without keeping any raw text. */
    public static Optional<Message> parse(String line) {
        if (line == null || line.length() > MAX_LINE || !line.startsWith("MDPERF1 ")) return Optional.empty();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 32 || c > 126) return Optional.empty();
        }
        String[] values = line.split(" ", -1);
        try {
            if (values.length == 2 && values[1].equals("READY")) return Optional.of(new Ready());
            if (values.length == 3 && values[1].equals("STARTED")) return Optional.of(new Started(session(values[2])));
            if (values.length == 3 && values[1].equals("DONE")) return Optional.of(new Done(session(values[2])));
            if (values.length != 17 || !values[1].equals("SAMPLE")) return Optional.empty();
            long id = session(values[2]);
            long elapsed = unsigned(values[3], 120_000);
            long segment = unsigned(values[4], 1_000_000);
            Long source = counter(values[5]);
            Long decoded = counter(values[6]);
            Long rendered = counter(values[7]);
            Long dropped = counter(values[8]);
            Long present = counter(values[9]);
            Double sourceP95 = gap(values[10]), sourceMax = gap(values[11]);
            Double decodeP95 = gap(values[12]), decodeMax = gap(values[13]);
            Double presentP95 = gap(values[14]), presentMax = gap(values[15]);
            ordered(sourceP95, sourceMax); ordered(decodeP95, decodeMax); ordered(presentP95, presentMax);
            String token = values[16];
            if (!FACTORY.matcher(token).matches()) return Optional.empty();
            String decoder = WINDOWS_DECODER.matcher(token).matches() ? token : null;
            return Optional.of(new Sample(id, elapsed, segment, source, decoded, rendered, dropped, present,
                    sourceP95, sourceMax, decodeP95, decodeMax, presentP95, presentMax, decoder));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static String startCommand(long sessionId, int seconds) {
        return startCommand(sessionId, seconds, false);
    }

    public static String startCommand(long sessionId, int seconds, boolean detailedPresent) {
        if (sessionId <= 0 || seconds < 1 || seconds > 60) throw new IllegalArgumentException("Invalid diagnostic start");
        return "MDPERF1 START " + sessionId + " " + seconds + (detailedPresent ? " PRESENT\n" : "\n");
    }

    public static String stopCommand(long sessionId) {
        if (sessionId <= 0) throw new IllegalArgumentException("Invalid diagnostic stop");
        return "MDPERF1 STOP " + sessionId + "\n";
    }

    private static long session(String value) {
        long id = unsigned(value, Long.MAX_VALUE);
        if (id == 0) throw new IllegalArgumentException();
        return id;
    }
    private static long unsigned(String value, long maximum) {
        if (value.length() > 19 || !NUMBER.matcher(value).matches()) throw new IllegalArgumentException();
        long number = Long.parseLong(value);
        if (number > maximum) throw new IllegalArgumentException();
        return number;
    }
    private static Long counter(String value) { return value.equals("-1") ? null : unsigned(value, MAX_COUNTER); }
    private static Double gap(String value) {
        return value.equals("-1") ? null : unsigned(value, MAX_GAP_MICROS) / 1_000.0;
    }
    private static void ordered(Double p95, Double maximum) {
        if (p95 != null && maximum != null && p95 > maximum) throw new IllegalArgumentException();
    }
    private static void positiveSession(long id) { if (id <= 0) throw new IllegalArgumentException("Invalid diagnostic session"); }
    private static void validCounter(Long value) {
        if (value != null && (value < 0 || value > MAX_COUNTER)) throw new IllegalArgumentException("Invalid diagnostic counter");
    }
    private static void validGap(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > MAX_GAP_MICROS / 1_000.0))
            throw new IllegalArgumentException("Invalid diagnostic gap");
    }
}
