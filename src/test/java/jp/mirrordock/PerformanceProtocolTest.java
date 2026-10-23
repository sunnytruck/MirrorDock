package jp.mirrordock;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceProtocolTest {
    private static final String SAMPLE = "MDPERF1 SAMPLE 7 1000 1 60 59 58 2 58 16667 24000 17000 25000 17000 27000 d3d11h264dec";

    @Test void parsesOnlyTheBoundedNumericProtocolAndConvertsMicroseconds() {
        PerformanceProtocol.Sample sample = assertInstanceOf(PerformanceProtocol.Sample.class, PerformanceProtocol.parse(SAMPLE).orElseThrow());
        assertEquals(7, sample.sessionId());
        assertEquals(60, sample.sourceFrames());
        assertEquals(59, sample.decodedFrames());
        assertEquals(16.667, sample.sourceGapP95Millis());
        assertEquals(27.0, sample.presentGapMaxMillis());
        assertEquals("d3d11h264dec", sample.decoder());
        assertEquals(new PerformanceProtocol.Ready(), PerformanceProtocol.parse("MDPERF1 READY").orElseThrow());
        assertEquals(new PerformanceProtocol.Started(7), PerformanceProtocol.parse("MDPERF1 STARTED 7").orElseThrow());
        assertEquals(new PerformanceProtocol.Done(7), PerformanceProtocol.parse("MDPERF1 DONE 7").orElseThrow());
    }

    @Test void unavailableMeasurementsStayUnknownInsteadOfBecomingZero() {
        PerformanceProtocol.Sample sample = assertInstanceOf(PerformanceProtocol.Sample.class, PerformanceProtocol.parse(
                "MDPERF1 SAMPLE 1 1000 0 -1 -1 -1 -1 -1 -1 -1 -1 -1 -1 -1 unknown").orElseThrow());
        assertNull(sample.sourceFrames()); assertNull(sample.sinkDroppedFrames());
        assertNull(sample.presentGapP95Millis()); assertNull(sample.decoder());
    }

    @Test void discardsMalformedUnboundedOrInjectedEngineOutput() {
        for (String line : List.of("", "MDPERF1 READY ", "MDPERF1  READY", "MDPERF1 READY\n",
                "MDPERF1 STARTED 0", "MDPERF1 STARTED -1", "MDPERF1 DONE 9223372036854775808",
                SAMPLE + " extra", SAMPLE.replace(" 60 ", " -2 "), SAMPLE.replace(" 60 ", " 1000000000001 "),
                SAMPLE.replace(" 1000 ", " 120001 "), SAMPLE.replace(" 1000 ", " 01 "),
                SAMPLE.replace(" 16667 ", " 60000001 "), SAMPLE.replace(" 16667 ", " 24001 "),
                SAMPLE.replace("d3d11h264dec", "C:\\Users\\Secret"), SAMPLE.replace("d3d11h264dec", "x".repeat(65)),
                SAMPLE.replace("d3d11h264dec", "unknown\tprivate-address"), "MDPERF1 " + "9".repeat(800),
                "rtsp connection from private-device", "MDPERF1 SAMPLE secret-key personal-name")) {
            assertTrue(PerformanceProtocol.parse(line).isEmpty(), () -> "Accepted invalid schema: " + line);
        }
        assertTrue(PerformanceProtocol.parse(null).isEmpty());
    }

    @Test void arbitrarySyntacticallyValidTokensAreNeverExportableDecoderNames() {
        PerformanceProtocol.Sample sample = assertInstanceOf(PerformanceProtocol.Sample.class,
                PerformanceProtocol.parse(SAMPLE.replace("d3d11h264dec", "private-device-name")).orElseThrow());
        assertNull(sample.decoder());
        PerformanceProtocol.Sample direct = new PerformanceProtocol.Sample(1, 1000, 0,
                60L, null, null, null, null, null, null, null, null, null, null, "private-name");
        assertNull(direct.decoder());
        assertThrows(IllegalArgumentException.class, () -> new PerformanceProtocol.Sample(1, 1000, 0,
                60L, null, null, null, null, Double.NaN, null, null, null, null, null, "avdec_h264"));
    }

    @Test void commandFormattingCannotAcceptArbitraryArgumentsOrInvalidSessions() {
        assertEquals("MDPERF1 START 91 60\n", PerformanceProtocol.startCommand(91, 60));
        assertEquals("MDPERF1 START 91 60\n", PerformanceProtocol.startCommand(91, 60, false));
        assertEquals("MDPERF1 START 91 60 PRESENT\n", PerformanceProtocol.startCommand(91, 60, true));
        assertEquals("MDPERF1 STOP 91\n", PerformanceProtocol.stopCommand(91));
        assertThrows(IllegalArgumentException.class, () -> PerformanceProtocol.startCommand(0, 60));
        assertThrows(IllegalArgumentException.class, () -> PerformanceProtocol.startCommand(1, 61));
        assertThrows(IllegalArgumentException.class, () -> PerformanceProtocol.startCommand(1, 0));
        assertThrows(IllegalArgumentException.class, () -> PerformanceProtocol.stopCommand(-1));
    }
}
