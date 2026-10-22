package jp.mirrordock;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import java.util.List;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
public final class WindowManager {
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
    public void engineOnTop(long pid,boolean enabled) {
        if (pid<0 || !System.getProperty("os.name").toLowerCase().contains("win")) return;
        User32.INSTANCE.EnumWindows((hwnd,data) -> {
            IntByReference found=new IntByReference();User32.INSTANCE.GetWindowThreadProcessId(hwnd,found);
            if (Integer.toUnsignedLong(found.getValue())==pid && User32.INSTANCE.IsWindowVisible(hwnd)) {
                User32.INSTANCE.SetWindowPos(hwnd,enabled ? new WinDef.HWND(com.sun.jna.Pointer.createConstant(-1)) : new WinDef.HWND(com.sun.jna.Pointer.createConstant(-2)),0,0,0,0,0x0001|0x0002|0x0010);
            }
            return true;
        },null);
    }
}
