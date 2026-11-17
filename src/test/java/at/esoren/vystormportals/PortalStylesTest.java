package at.esoren.vystormportals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PortalStylesTest {
    @TempDir Path folder;

    @Test
    void givenUnknownValues_whenStyleIsBuilt_thenTheyFallBackToNone() {
        var style = new PortalStyles.Style("RAINBOW", null, "Swirl");
        assertEquals("none", style.particle());
        assertEquals("none", style.sound());
        assertEquals("swirl", style.arrival());
        assertTrue(new PortalStyles.Style(null, null, null).plain());
    }

    @Test
    void givenCycle_whenForwardAndBack_thenItWrapsAround() {
        assertEquals("end_rod", PortalStyles.cycle(PortalStyles.PARTICLES, "none", false));
        assertEquals("happy_villager", PortalStyles.cycle(PortalStyles.PARTICLES, "none", true));
        assertEquals("none", PortalStyles.cycle(PortalStyles.ARRIVALS, "sparkle", false));
        assertEquals("flash", PortalStyles.cycle(PortalStyles.ARRIVALS, "unknown", false));
    }

    @Test
    void givenStyles_whenSavedAndLoaded_thenOnlyRealStylesStay() throws Exception {
        Path file = folder.resolve("styles.yml");
        PortalStyles styles = new PortalStyles(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        styles.set("harbour", new PortalStyles.Style("cherry_leaves", "amethyst", "sparkle"));
        styles.set("mine", new PortalStyles.Style("flame", "none", "none"));
        styles.set("mine", PortalStyles.Style.NONE);
        Map<String, PortalStyles.Style> loaded = PortalStyles.load(file);
        assertEquals(Map.of("harbour", new PortalStyles.Style("cherry_leaves", "amethyst", "sparkle")), loaded);
        PortalStyles again = new PortalStyles(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        again.retain(Set.of("other"));
        assertTrue(PortalStyles.load(file).isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    void givenSoundChoices_whenMapped_thenEveryChoiceButNoneHasAKey() {
        for (String choice : PortalStyles.SOUNDS) {
            if (choice.equals("none")) assertNull(PortalStyles.sound(choice));
            else assertTrue(PortalStyles.sound(choice).startsWith("minecraft:"), choice);
        }
    }
}
