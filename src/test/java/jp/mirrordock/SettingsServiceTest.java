package jp.mirrordock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {
    @TempDir Path directory;

    @Test void newAndLegacyConstructedSettingsRequestThirtyFps() throws Exception {
        assertEquals(30, Settings.defaults().requestedFps());
        assertEquals(30, new SettingsService(directory).load().requestedFps());
        Settings legacy = new Settings("MirrorDock", "", false, true, false, false,
                100, 100, 900, 660);
        assertEquals(30, legacy.requestedFps());
    }

    @Test void sixtyFpsRoundTripPreservesOtherSettings() throws Exception {
        Settings settings = new Settings("日本語の受信機", "C:\\engine\\bin\\uxplay.exe",
                true, false, true, true, -1920, 40, 1100, 720, 60);
        SettingsService service = new SettingsService(directory);
        service.save(settings);
        assertEquals(settings, service.load());
        service.save(settings);
        assertEquals(settings, service.load());
    }

    @Test void sixtyFpsCanBeSavedBackToThirty() throws Exception {
        SettingsService service = new SettingsService(directory);
        service.save(new Settings("MirrorDock", "", false, true, false, false,
                100, 100, 900, 660, 60));
        assertEquals(60, service.load().requestedFps());
        Settings thirty = new Settings("MirrorDock", "", false, true, false, false,
                100, 100, 900, 660, 30);
        service.save(thirty);
        assertEquals(thirty, service.load());
    }

    @Test void settingsWrittenBeforeFpsSupportKeepTheirExistingValues() throws Exception {
        Files.writeString(directory.resolve("settings.properties"),
                "name=ExistingReceiver\naudio=false\nonTop=true\nautoStart=true\nlogging=true\n"
                        + "x=-1920\ny=30\nwidth=1024\nheight=768\n");
        Settings loaded = new SettingsService(directory).load();
        assertEquals(new Settings("ExistingReceiver", "", true, false, true, true,
                -1920, 30, 1024, 768, 30), loaded);
    }

    @Test void unsupportedOrMalformedStoredFpsFallsBackWithoutDiscardingSettings() throws Exception {
        String[] invalid = {"", "0", "-1", "15", "59", "61", "120", "60.0", "NaN",
                "not-a-number", "2147483648", " 60 "};
        SettingsService service = new SettingsService(directory);
        for (String value : invalid) {
            Files.writeString(directory.resolve("settings.properties"),
                    "name=Preserved\naudio=false\nrequestedFps=" + value + "\n");
            Settings loaded = service.load();
            assertEquals(30, loaded.requestedFps(), "requestedFps=" + value);
            assertEquals("Preserved", loaded.receiverName());
            assertFalse(loaded.audio());
        }
    }

    @Test void unsupportedProgrammaticFpsIsNormalizedBeforeSaving() throws Exception {
        SettingsService service = new SettingsService(directory);
        for (int value : new int[]{Integer.MIN_VALUE, -60, 0, 29, 31, 59, 61, 120, Integer.MAX_VALUE}) {
            Settings settings = new Settings("MirrorDock", "", false, true, false, false,
                    100, 100, 900, 660, value);
            assertEquals(30, settings.requestedFps(), "requestedFps=" + value);
            service.save(settings);
            assertEquals(settings, service.load());
        }
    }
}
