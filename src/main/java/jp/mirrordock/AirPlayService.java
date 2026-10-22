package jp.mirrordock;
import java.util.function.Consumer;
public interface AirPlayService extends AutoCloseable {
    enum State { STOPPED, STARTING, WAITING, CONNECTED, ERROR }
    record Event(State state, String message) {}
    void setListener(Consumer<Event> listener);
    void start(Settings settings);
    void stop();
    State state();
    long pid();
    void close();
}
