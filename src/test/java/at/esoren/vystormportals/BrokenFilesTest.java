package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or data file with a YAML error is never saved over. */
class BrokenFilesTest {
    static final String BROKEN = "styles:\n  gate:\n    particle: flame\n broken here\nother: 1\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return logger;
    }

    @Test void givenBrokenConfig_whenTheReloadHookRuns_thenTheFileStaysAndTheLastGoodSettingsAreKept() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        PortalsPlugin plugin = mock(PortalsPlugin.class, CALLS_REAL_METHODS);
        YamlConfiguration lastGood = new YamlConfiguration();
        lastGood.set("menu.enabled", false);
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger()).when(plugin).getLogger();
        doReturn(lastGood).when(plugin).getConfig();
        doNothing().when(plugin).reloadConfig();
        doNothing().when(plugin).saveConfig();
        plugin.reloadSettings();
        verify(plugin, never()).reloadConfig();
        verify(plugin, never()).saveConfig();
        assertArrayEquals(before, Files.readAllBytes(file));
        List<LogRecord> severe = logged.stream().filter(r -> r.getLevel() == Level.SEVERE).toList();
        assertEquals(1, severe.size());
        assertTrue(severe.getFirst().getMessage().contains("line 4, column"), severe.getFirst().getMessage());
        // at start the same check runs; Bukkit then serves the jar defaults and nothing is written
        logged.clear();
        assertNotNull(YamlCheck.readConfig(plugin, false));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void givenBrokenStylesFile_whenAStyleIsSaved_thenTheBrokenFileWasCopiedAsideFirst() throws Exception {
        Path file = folder.resolve("styles.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        PortalStyles styles = new PortalStyles(file, AtomicFilesInterface.DEFAULT);
        assertArrayEquals(before, Files.readAllBytes(file), "loading alone changes nothing");
        styles.set("gate", new PortalStyles.Style("flame", null, null));
        assertArrayEquals(before, Files.readAllBytes(copy("styles.yml")), "the broken file is kept as styles.yml.broken-<time>");
    }

    @Test void givenEveryBrokenDataFile_whenLoaded_thenEachIsCopiedAsideBeforeItCanBeWritten() throws Exception {
        for (String name : List.of("hours.yml", "travel.yml", "energy.yml")) Files.writeString(folder.resolve(name), BROKEN, StandardCharsets.UTF_8);
        new PortalHours(folder.resolve("hours.yml"), AtomicFilesInterface.DEFAULT);
        new TravelPoints(folder.resolve("travel.yml"), AtomicFilesInterface.DEFAULT);
        new PortalEnergy(folder.resolve("energy.yml"), mock(PortalEnergy.Rules.class), AtomicFilesInterface.DEFAULT);
        for (String name : List.of("hours.yml", "travel.yml", "energy.yml"))
            assertEquals(BROKEN, Files.readString(copy(name), StandardCharsets.UTF_8), name);
    }

    @Test void givenBrokenYaml_whenDescribed_thenLineColumnAndProblemAreNamed() throws Exception {
        Path file = folder.resolve("x.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertTrue(YamlCheck.problem(file.toFile()).matches("line 4, column \\d+: .+"), YamlCheck.problem(file.toFile()));
    }

    private Path copy(String name) throws Exception {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith(name + ".broken-")).findFirst()
                    .orElseThrow(() -> new AssertionError("no copy of " + name));
        }
    }
}
