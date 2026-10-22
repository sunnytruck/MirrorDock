package jp.mirrordock;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
// Only application-generated status messages are accepted. Never write engine output or device identifiers.
public final class LogService {
    private final Path file;
    private volatile boolean enabled;
    public LogService(Path directory) { file=directory.resolve("MirrorDock.log"); }
    public void setEnabled(boolean enabled) { this.enabled=enabled; }
    public synchronized void status(AirPlayService.State state) {
        if (!enabled) return;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file)>1_000_000) Files.move(file,file.resolveSibling("MirrorDock.previous.log"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file,Instant.now()+" "+state.name()+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch (IOException e) { System.err.println("ログを保存できません。"); }
    }
}
