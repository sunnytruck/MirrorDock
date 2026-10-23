package jp.mirrordock;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
public final class SettingsService {
    private final Path directory;
    public SettingsService(Path directory) { this.directory = directory; }
    public static Path userDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return local != null && !local.isBlank() ? Path.of(local, "MirrorDock") : Path.of(System.getProperty("user.home"), ".mirrordock");
    }
    public Settings load() throws IOException {
        Path file = directory.resolve("settings.properties");
        if (!Files.exists(file)) return Settings.defaults();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) { p.load(in); }
        catch (IllegalArgumentException e) { throw new IOException("設定ファイルの形式が不正です。", e); }
        return new Settings(p.getProperty("name", "MirrorDock"), p.getProperty("executable", ""),
            flag(p,"onTop",false),flag(p,"audio",true),flag(p,"autoStart",false),flag(p,"logging",false),
            number(p,"x",100),number(p,"y",100),number(p,"width",900),number(p,"height",660),
            requestedFps(p));
    }
    private static boolean flag(Properties p, String k, boolean fallback) {
        String s=p.getProperty(k); return "true".equals(s) ? true : "false".equals(s) ? false : fallback;
    }
    private static double number(Properties p,String k,double fallback) {
        try { return Double.parseDouble(p.getProperty(k)); } catch (RuntimeException e) { return fallback; }
    }
    private static int requestedFps(Properties p) {
        try { return Integer.parseInt(p.getProperty("requestedFps", "30")); }
        catch (NumberFormatException e) { return 30; }
    }
    public void save(Settings s) throws IOException {
        Files.createDirectories(directory);
        Properties p = new Properties();
        p.setProperty("name",s.receiverName()); p.setProperty("executable",s.executable());
        p.setProperty("onTop",""+s.onTop()); p.setProperty("audio",""+s.audio());
        p.setProperty("autoStart",""+s.autoStart()); p.setProperty("logging",""+s.logging());
        p.setProperty("requestedFps",Integer.toString(s.requestedFps()));
        p.setProperty("x",""+s.x());p.setProperty("y",""+s.y());p.setProperty("width",""+s.width());p.setProperty("height",""+s.height());
        Path temp = Files.createTempFile(directory,"settings-",".tmp");
        try {
            try (Writer out=Files.newBufferedWriter(temp)) { p.store(out,"MirrorDock"); }
            try { Files.move(temp,directory.resolve("settings.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,directory.resolve("settings.properties"),StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
