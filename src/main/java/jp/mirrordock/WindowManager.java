package jp.mirrordock;
import javafx.stage.Screen;
import javafx.stage.Stage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
public final class WindowManager {
    /** The suspended mode preserves the existing native window state. */
    public enum EngineWindowMode { ON_CHANGE, PERIODIC, SUSPENDED }

    record NativeWindow(long handle, boolean topMost) {}
    interface NativeWindows {
        List<NativeWindow> visibleWindows(long pid);
        boolean setTopMost(long handle, boolean enabled);
    }

    private final NativeWindows nativeWindows;
    private final Set<Long> appliedHandles = new HashSet<>();
    private EngineWindowMode engineWindowMode = EngineWindowMode.ON_CHANGE;
    private long appliedPid = -1;
    private Boolean requestedTopMost;

    public WindowManager() {
        this(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? new Win32Windows() : null);
    }

    WindowManager(NativeWindows nativeWindows) { this.nativeWindows = nativeWindows; }

    public synchronized void setEngineWindowMode(EngineWindowMode mode) {
        Objects.requireNonNull(mode);
        if (engineWindowMode != mode) {
            engineWindowMode = mode;
            invalidateAppliedState();
        }
    }

    public synchronized EngineWindowMode getEngineWindowMode() { return engineWindowMode; }

    public record Bounds(double x,double y,double width,double height) {}
    public static Bounds correct(Bounds b,List<Bounds> monitors) {
        if (monitors.isEmpty()) return new Bounds(0,0,900,660);
        Bounds target=monitors.stream().filter(m -> b.x()+b.width()>m.x() && b.x()<m.x()+m.width() && b.y()+b.height()>m.y() && b.y()<m.y()+m.height()).findFirst().orElse(monitors.getFirst());
        double w=Math.min(b.width(),target.width()),h=Math.min(b.height(),target.height());
        return new Bounds(Math.max(target.x(),Math.min(b.x(),target.x()+target.width()-w)),Math.max(target.y(),Math.min(b.y(),target.y()+target.height()-h)),w,h);
    }
    public void restore(Stage stage,Settings s) {
        List<Bounds> monitors=Screen.getScreens().stream().map(Screen::getVisualBounds).map(r -> new Bounds(r.getMinX(),r.getMinY(),r.getWidth(),r.getHeight())).toList();
        Bounds b=correct(new Bounds(s.x(),s.y(),s.width(),s.height()),monitors);
        stage.setX(b.x());stage.setY(b.y());stage.setWidth(b.width());stage.setHeight(b.height());stage.setAlwaysOnTop(s.onTop());
    }
    public synchronized void engineOnTop(long pid,boolean enabled) {
        if (engineWindowMode == EngineWindowMode.SUSPENDED) return;
        if (pid < 0 || nativeWindows == null) {
            invalidateAppliedState();
            return;
        }
        if (pid != appliedPid || requestedTopMost == null || requestedTopMost != enabled) {
            appliedHandles.clear();
            appliedPid = pid;
            requestedTopMost = enabled;
        }
        List<NativeWindow> windows = nativeWindows.visibleWindows(pid);
        Set<Long> visibleHandles = new HashSet<>();
        for (NativeWindow window : windows) visibleHandles.add(window.handle());
        appliedHandles.retainAll(visibleHandles);
        for (NativeWindow window : windows) {
            if (engineWindowMode == EngineWindowMode.PERIODIC || !appliedHandles.contains(window.handle())
                    || window.topMost() != enabled) {
                if (nativeWindows.setTopMost(window.handle(), enabled)) appliedHandles.add(window.handle());
                else appliedHandles.remove(window.handle());
            }
        }
    }

    private void invalidateAppliedState() {
        appliedHandles.clear();
        appliedPid = -1;
        requestedTopMost = null;
    }

    private static final class Win32Windows implements NativeWindows {
        private static final int WS_EX_TOPMOST = 0x00000008;
        @Override public List<NativeWindow> visibleWindows(long pid) {
            List<NativeWindow> result = new ArrayList<>();
            boolean completed = User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference found = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, found);
                if (Integer.toUnsignedLong(found.getValue()) == pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                    int style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
                    result.add(new NativeWindow(Pointer.nativeValue(hwnd.getPointer()), (style & WS_EX_TOPMOST) != 0));
                }
                return true;
            }, null);
            if (!completed) throw new IllegalStateException("映像ウィンドウを列挙できません。");
            return result;
        }

        @Override public boolean setTopMost(long handle, boolean enabled) {
            WinDef.HWND window = new WinDef.HWND(Pointer.createConstant(handle));
            WinDef.HWND order = new WinDef.HWND(Pointer.createConstant(enabled ? -1 : -2));
            return User32.INSTANCE.SetWindowPos(window, order, 0, 0, 0, 0,
                    WinUser.SWP_NOSIZE | WinUser.SWP_NOMOVE | WinUser.SWP_NOACTIVATE);
        }
    }
}
