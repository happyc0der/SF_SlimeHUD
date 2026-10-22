package io.github.schntgaispock.slimehud.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerDataStoreTest {
    private static final String FIRST = "00000000-1111-2222-3333-444444444444";
    private static final String SECOND = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Logger LOGGER = Logger.getLogger("PlayerDataStoreTest");

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("player.yml");
    }

    private PlayerDataStore open() {
        return new PlayerDataStore(file(), LOGGER);
    }

    @Test
    void failedLoadCannotCreateASaveableStoreOrChangeOriginalFile() throws Exception {
        byte[] original = (FIRST + ": [broken\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file(), original);
        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void unchangedStartupShutdownAndSameValueKeepBytesExactly() throws Exception {
        byte[] original = ("# Existing preferences\r\n'" + FIRST + "':\r\n"
                + "  waila: false\r\n  display: 'actionbar'\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file(), original);
        var data = open();
        assertFalse(data.getBoolean(FIRST + ".waila", true));
        assertEquals("actionbar", data.getString(FIRST + ".display", "bossbar"));
        data.set(FIRST + ".display", "actionbar");
        data.save();
        data.save();
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test
    void changedPreferencesPreserveOtherUsersAndUnknownValuesAcrossReopen() throws Exception {
        Files.writeString(file(), FIRST + ":\n  waila: false\n  display: actionbar\n  custom-count: 9223372036854775807\n"
                + SECOND + ":\n  waila: true\n  display: bossbar\n  future: [keep, me]\n");
        var data = open();
        data.set(FIRST + ".display", "bossbar");
        data.save();
        var reopened = open();
        assertFalse(reopened.getBoolean(FIRST + ".waila", true));
        assertEquals("bossbar", reopened.getString(FIRST + ".display", ""));
        assertTrue(reopened.getBoolean(SECOND + ".waila", false));
        var yaml = PlayerSettingsFile.load(file());
        assertEquals(Long.MAX_VALUE, yaml.getLong(FIRST + ".custom-count"));
        assertEquals(List.of("keep", "me"), yaml.getStringList(SECOND + ".future"));
    }

    @Test
    void removingOnePreferenceDoesNotDeleteAnotherUserOrPreference() throws Exception {
        var data = open();
        data.set(FIRST + ".display", "actionbar");
        data.set(FIRST + ".waila", false);
        data.set(SECOND + ".waila", true);
        assertTrue(data.hasPreference(FIRST + ".waila"));
        data.save();
        data.set(FIRST + ".display", null);
        data.save();
        var reopened = open();
        assertEquals("default", reopened.getString(FIRST + ".display", "default"));
        assertFalse(reopened.getBoolean(FIRST + ".waila", true));
        assertTrue(reopened.getBoolean(SECOND + ".waila", false));
    }

    @Test
    void failedWriteRemainsPendingAndCanBeRetriedWithoutLosingOldFile() throws Exception {
        Files.writeString(file(), FIRST + ":\n  display: actionbar\n");
        var data = open();
        var backup = directory.resolve("original.yml");
        Files.move(file(), backup);
        Files.createDirectory(file());
        var child = file().resolve("retain.txt");
        Files.writeString(child, "retain");
        data.set(FIRST + ".display", "bossbar");
        data.save();
        assertEquals("retain", Files.readString(child));
        assertTrue(Files.readString(backup).contains("actionbar"));
        Files.delete(child);
        Files.delete(file());
        Files.move(backup, file());
        data.save();
        assertEquals("bossbar", open().getString(FIRST + ".display", ""));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void freshStoreRetainsExistingDefaultsAndCreatesAnEmptyFileOnSave() throws Exception {
        var data = open();
        assertFalse(data.hasPreference(FIRST + ".waila"));
        assertTrue(data.getBoolean(FIRST + ".waila", true));
        assertEquals("bossbar", data.getString(FIRST + ".display", "bossbar"));
        assertFalse(Files.exists(file()));
        data.save();
        assertTrue(Files.isRegularFile(file()));
        assertTrue(PlayerSettingsFile.load(file()).getKeys(false).isEmpty());
    }

    @Test
    void concurrentSetAndSaveOperationsRetainEveryDistinctPreference() throws Exception {
        var data = open();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    data.set("user" + index + ".display", "actionbar");
                    data.save();
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        }
        var dataFromDisk = open();
        for (int i = 0; i < 32; i++) {
            assertEquals("actionbar", dataFromDisk.getString("user" + i + ".display", ""));
        }
    }

    @Test
    void malformedReplacementIsNotAcceptedOnTheNextLifecycle() throws Exception {
        var data = open();
        data.set(FIRST + ".waila", false);
        data.save();
        byte[] broken = "replacement: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), broken);
        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(broken, Files.readAllBytes(file()));
    }
}
