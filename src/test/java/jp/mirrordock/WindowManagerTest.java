package jp.mirrordock;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WindowManagerTest {
    private static final class FakeWindows implements WindowManager.NativeWindows {
        final Map<Long, Boolean> windows = new LinkedHashMap<>();
        final List<String> applied = new ArrayList<>();
        final List<Long> enumeratedPids = new ArrayList<>();
        boolean succeed = true;

        @Override public List<WindowManager.NativeWindow> visibleWindows(long pid) {
            enumeratedPids.add(pid);
            return windows.entrySet().stream()
                    .map(window -> new WindowManager.NativeWindow(window.getKey(), window.getValue())).toList();
        }

        @Override public boolean setTopMost(long handle, boolean enabled) {
            applied.add(handle + ":" + enabled);
            if (succeed) windows.put(handle, enabled);
            return succeed;
        }
    }

    @Test void unchangedWindowsDoNotRepeatSetWindowPosInDefaultMode() {
        FakeWindows nativeWindows = new FakeWindows();
        nativeWindows.windows.put(10L, false);
        WindowManager manager = new WindowManager(nativeWindows);

        manager.engineOnTop(50, false);
        manager.engineOnTop(50, false);
        manager.engineOnTop(50, false);

        assertEquals(WindowManager.EngineWindowMode.ON_CHANGE, manager.getEngineWindowMode());
        assertEquals(List.of("10:false"), nativeWindows.applied);
        assertEquals(List.of(50L, 50L, 50L), nativeWindows.enumeratedPids);
    }

    @Test void settingChangesNewWindowsAndExternalStateChangesAreApplied() {
        FakeWindows nativeWindows = new FakeWindows();
        nativeWindows.windows.put(10L, false);
        WindowManager manager = new WindowManager(nativeWindows);

        manager.engineOnTop(50, false);
        manager.engineOnTop(50, true);
        nativeWindows.windows.put(11L, false);
        manager.engineOnTop(50, true);
        nativeWindows.windows.put(10L, false); // Another window manager changed the native state.
        manager.engineOnTop(50, true);

        assertEquals(List.of("10:false", "10:true", "11:true", "10:true"), nativeWindows.applied);
    }

    @Test void disappearingAndRecreatedWindowAndNewProcessAreReapplied() {
        FakeWindows nativeWindows = new FakeWindows();
        nativeWindows.windows.put(10L, true);
        WindowManager manager = new WindowManager(nativeWindows);

        manager.engineOnTop(50, true);
        nativeWindows.windows.clear();
        manager.engineOnTop(50, true);
        nativeWindows.windows.put(10L, true);
        manager.engineOnTop(50, true);
        manager.engineOnTop(51, true);
        manager.engineOnTop(-1, true);
        manager.engineOnTop(51, true);

        assertEquals(List.of("10:true", "10:true", "10:true", "10:true"), nativeWindows.applied);
        assertFalse(nativeWindows.enumeratedPids.contains(-1L));
    }

    @Test void periodicModeRetainsOldBehaviourAndSuspendedModeMakesNoNativeCalls() {
        FakeWindows nativeWindows = new FakeWindows();
        nativeWindows.windows.put(10L, false);
        WindowManager manager = new WindowManager(nativeWindows);
        manager.setEngineWindowMode(WindowManager.EngineWindowMode.PERIODIC);
        manager.engineOnTop(50, true);
        manager.engineOnTop(50, true);
        assertEquals(List.of("10:true", "10:true"), nativeWindows.applied);

        manager.setEngineWindowMode(WindowManager.EngineWindowMode.SUSPENDED);
        manager.engineOnTop(50, false);
        manager.engineOnTop(51, false);
        assertEquals(2, nativeWindows.applied.size());
        assertEquals(2, nativeWindows.enumeratedPids.size());
        assertTrue(nativeWindows.windows.get(10L)); // Suspension preserves the current native state.

        manager.setEngineWindowMode(WindowManager.EngineWindowMode.ON_CHANGE);
        manager.engineOnTop(50, false);
        manager.engineOnTop(50, false);
        assertEquals(List.of("10:true", "10:true", "10:false"), nativeWindows.applied);
    }

    @Test void failedNativeApplicationIsRetriedAndNullModeIsRejected() {
        FakeWindows nativeWindows = new FakeWindows();
        nativeWindows.windows.put(10L, false);
        nativeWindows.succeed = false;
        WindowManager manager = new WindowManager(nativeWindows);

        manager.engineOnTop(50, true);
        nativeWindows.succeed = true;
        manager.engineOnTop(50, true);
        manager.engineOnTop(50, true);

        assertEquals(List.of("10:true", "10:true"), nativeWindows.applied);
        assertThrows(NullPointerException.class, () -> manager.setEngineWindowMode(null));
    }
}
