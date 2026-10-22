package jp.mirrordock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class EnvironmentTest {
    @TempDir Path temp;
    @Test void settingsRoundTrip() throws Exception {
        var service=new SettingsService(temp);
        var s=new Settings("日本語の受信機","C:\\msys64\\ucrt64\\bin\\uxplay.exe",true,false,true,true,-1200,42,1000,700);
        service.save(s);assertEquals(s,service.load());
        service.save(s);assertEquals(s,service.load());
    }
    @Test void invalidSettingsRecover() throws Exception {
        Files.writeString(temp.resolve("settings.properties"),"name=\nx=NaN\ny=Infinity\nwidth=-1\nheight=no\naudio=garbage\n");
        var s=new SettingsService(temp).load();assertEquals("MirrorDock",s.receiverName());assertEquals(100,s.x());assertEquals(100,s.y());assertEquals(900,s.width());assertEquals(660,s.height());assertTrue(s.audio());
    }
    @Test void invalidUnicodeReported() throws Exception {
        Files.writeString(temp.resolve("settings.properties"),"name=\\uXXYZ");assertThrows(java.io.IOException.class,()->new SettingsService(temp).load());
    }
    @Test void missingExecutable() throws Exception {
        try(var service=new UxPlayService()) {
            var events=new LinkedBlockingQueue<AirPlayService.Event>();service.setListener(events::add);service.start(Settings.defaults());
            assertEquals(AirPlayService.State.STARTING,events.poll(2,TimeUnit.SECONDS).state());
            assertEquals(AirPlayService.State.ERROR,events.poll(2,TimeUnit.SECONDS).state());
        }
    }
    @Test void nonexistentAndRelativePathsRejected() {
        assertThrows(java.io.IOException.class,()->UxPlayService.validateExecutable("uxplay.exe"));
        assertThrows(java.io.IOException.class,()->UxPlayService.validateExecutable(temp.resolve("uxplay.exe").toString()));
    }
    @Test void correctRemovedMonitor() {
        var b=WindowManager.correct(new WindowManager.Bounds(-2000,100,900,660),List.of(new WindowManager.Bounds(0,0,1920,1080)));
        assertEquals(0,b.x());assertEquals(100,b.y());
    }
    @Test void correctOversizeAndNegativeMonitor() {
        var b=WindowManager.correct(new WindowManager.Bounds(-1000,-500,3000,2000),List.of(new WindowManager.Bounds(-1920,0,1920,1080)));
        assertEquals(new WindowManager.Bounds(-1920,0,1920,1080),b);
    }
    @Test void commandIsArgumentVectorAndDisablesAudio() {
        var args=UxPlayService.command(Path.of("/some path/uxplay"),new Settings("name & calc.exe","",false,false,false,false,0,0,900,660));
        assertEquals("name & calc.exe",args.get(2));assertTrue(args.contains("-nh"));assertEquals("0",args.get(args.indexOf("-as")+1));assertFalse(args.contains("-mp4"));
    }
    private UxPlayService fixture(String mode) throws Exception {
        String classPath=Path.of(FakeEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new UxPlayService((path,s)->List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classPath,FakeEngine.class.getName(),mode));
    }
    private Settings fixtureSettings() throws Exception {
        Path binary=temp.resolve("uxplay.exe");
        if(!Files.exists(binary)) {Files.copy(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java"),binary);binary.toFile().setExecutable(true);}
        return new Settings("MirrorDock",binary.toString(),false,true,false,false,0,0,900,660);
    }
    @Test void startsStopsAndPreventsDoubleStart() throws Exception {
        try(var service=fixture("wait")) {
            var events=new LinkedBlockingQueue<AirPlayService.Event>();service.setListener(events::add);service.start(fixtureSettings());
            awaitState(events,AirPlayService.State.WAITING);long pid=service.pid();assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            service.start(fixtureSettings());assertEquals(pid,service.pid());
            service.stop();awaitState(events,AirPlayService.State.STOPPED);
            ProcessHandle.of(pid).ifPresent(p -> {try{p.onExit().get(5,TimeUnit.SECONDS);}catch(Exception e){fail(e);}});
            service.start(fixtureSettings());awaitState(events,AirPlayService.State.WAITING);assertNotEquals(pid,service.pid());
        }
    }
    @Test void detectsAbnormalExit() throws Exception {
        try(var service=fixture("crash")) {
            var events=new LinkedBlockingQueue<AirPlayService.Event>();service.setListener(events::add);service.start(fixtureSettings());
            var event=awaitState(events,AirPlayService.State.ERROR);assertTrue(event.message().contains("17"));
        }
    }
    @Test void connectionDisconnectAndSanitizedErrors() {
        try(var service=new UxPlayService()) {
            var events=new ArrayList<AirPlayService.Event>();service.setListener(events::add);
            service.parse(0,"Open connections: 1");assertEquals(AirPlayService.State.CONNECTED,service.state());
            service.parse(0,"Open connections: 0");assertEquals(AirPlayService.State.WAITING,service.state());
            service.parse(0,"audio failed secret-device-address");assertFalse(events.getLast().message().contains("secret"));assertTrue(events.getLast().message().contains("音声"));
        }
    }
    private static AirPlayService.Event awaitState(BlockingQueue<AirPlayService.Event> events,AirPlayService.State state) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {var e=events.poll(200,TimeUnit.MILLISECONDS);if(e!=null&&e.state()==state)return e;}
        throw new AssertionError("Missing state "+state);
    }
    public static class FakeEngine {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("crash"))System.exit(17);
            System.out.println("register_dnssd: advertised AirPlay service");System.out.flush();Thread.sleep(60000);
        }
    }
}
