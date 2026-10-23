package jp.mirrordock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UxPlayPerformanceTest {
    @TempDir Path temporary;

    @Test void advertisedFpsIsAnExplicitArgumentAndDoesNotDisableSynchronization() {
        for (int fps : List.of(30,60)) {
            Settings s=new Settings("MirrorDock","",false,true,false,false,0,0,900,660,fps);
            List<String> command=UxPlayService.command(Path.of("/engine path/uxplay.exe"),s);
            assertEquals(Integer.toString(fps),command.get(command.indexOf("-fps")+1));
            assertFalse(command.contains("-vsync"));
            assertFalse(command.stream().anyMatch(arg->arg.contains("max-lateness") || arg.contains("sync=false") || arg.contains("leaky=")));
        }
    }

    @Test void telemetryUsesThePrivatePipeAndKeepsTheAppliedSettings() throws Exception {
        Path selected=temporary.resolve("uxplay.exe");
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Files.copy(Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java"),selected);
        selected.toFile().setExecutable(true);
        String classPath=Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(UxPlayService service=new UxPlayService((exe,s)->List.of(
                Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString(),
                "-cp",classPath,Fixture.class.getName()))) {
            BlockingQueue<PerformanceProtocol.Message> messages=new LinkedBlockingQueue<>();
            service.setMetricsListener(message -> {
                messages.add(message);
                if (message instanceof PerformanceProtocol.Sample sample && sample.sessionId()==13)
                    throw new IllegalStateException("Synthetic diagnostic listener failure");
            });
            Settings applied=new Settings("Private receiver name",selected.toString(),false,true,false,false,0,0,900,660,60);
            service.start(applied);
            assertInstanceOf(PerformanceProtocol.Ready.class,messages.poll(5,TimeUnit.SECONDS));
            assertTrue(service.metricsAvailable());
            assertEquals(60,service.activeSettings().requestedFps());
            assertTrue(service.startMetrics(12,60));
            assertEquals(new PerformanceProtocol.Started(12),messages.poll(5,TimeUnit.SECONDS));
            PerformanceProtocol.Sample sample=assertInstanceOf(PerformanceProtocol.Sample.class,messages.poll(5,TimeUnit.SECONDS));
            assertEquals(12,sample.sessionId());
            assertEquals(60,sample.decodedFrames());
            assertNull(sample.presentCalls());
            service.stopMetrics(12);
            assertEquals(new PerformanceProtocol.Done(12),messages.poll(5,TimeUnit.SECONDS));
            assertTrue(service.startMetrics(13,1,true));
            assertEquals(new PerformanceProtocol.Started(13),messages.poll(5,TimeUnit.SECONDS));
            PerformanceProtocol.Sample detailed=assertInstanceOf(PerformanceProtocol.Sample.class,messages.poll(5,TimeUnit.SECONDS));
            assertEquals(59,detailed.presentCalls());
            service.stopMetrics(13);
            assertEquals(new PerformanceProtocol.Done(13),messages.poll(5,TimeUnit.SECONDS));
            service.stop();
            assertFalse(service.metricsAvailable());
            assertNull(service.activeSettings());
        }
    }

    @Test void malformedOrUnrelatedOutputNeverBecomesTelemetry() {
        try(UxPlayService service=new UxPlayService()) {
            List<PerformanceProtocol.Message> messages=new ArrayList<>();
            service.setMetricsListener(messages::add);
            service.parse(0,"MDPERF1 SAMPLE private-device-address secret-key");
            service.parse(0,"Open connections: 1");
            service.parse(99,"MDPERF1 READY");
            assertTrue(messages.isEmpty());
            assertFalse(service.metricsAvailable());
        }
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if(!"1".equals(System.getenv("MIRRORDOCK_PERF_CONTROL"))) System.exit(24);
            System.out.println("register_dnssd: advertised AirPlay service");
            System.out.println("MDPERF1 READY");System.out.flush();
            try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in))) {
                String command;
                while((command=input.readLine())!=null) {
                    if(command.equals("MDPERF1 START 12 60")) {
                        System.out.println("MDPERF1 STARTED 12");
                        System.out.println("MDPERF1 SAMPLE 12 1000 1 60 60 59 1 -1 17000 22000 17000 22000 -1 -1 avdec_h264");
                    } else if(command.equals("MDPERF1 STOP 12")) System.out.println("MDPERF1 DONE 12");
                    else if(command.equals("MDPERF1 START 13 1 PRESENT")) {
                        System.out.println("MDPERF1 STARTED 13");
                        System.out.println("MDPERF1 SAMPLE 13 1000 1 60 60 59 1 59 17000 22000 17000 22000 17000 22000 avdec_h264");
                    } else if(command.equals("MDPERF1 STOP 13")) System.out.println("MDPERF1 DONE 13");
                    System.out.flush();
                }
            }
        }
    }
}
