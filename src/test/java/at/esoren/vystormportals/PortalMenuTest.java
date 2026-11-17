package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PortalMenuTest {
    private static final UUID ME = UUID.fromString("ae77dd8b-3bb4-4e79-9f54-900477d10230");
    private static final UUID YOU = UUID.fromString("9a7c1e55-2d3b-4c8e-b1f0-6e5d4c3b2a10");
    private static final UUID WORLD = UUID.fromString("02e66380-761b-4e67-953a-363fa21a3eb8");

    @Test void listsOwnPortalsFirstThenByNameAndHidesInvisibleOnes() {
        Portal zMine = portal("z-mine", ME, false), aYours = portal("a-yours", YOU, true), bMine = portal("b-mine", ME, false),
                hidden = portal("hidden", YOU, false);
        List<Portal> ordered = PortalMenu.ordered(List.of(zMine, aYours, hidden, bMine), ME, p -> p.publicAccess() || p.owner().equals(ME));
        assertEquals(List.of(bMine, zMine, aYours), ordered);
    }

    @Test void pagesHoldFortyFivePortalsAndClampOutOfRangePages() {
        assertEquals(1, PortalMenu.pages(0));
        assertEquals(1, PortalMenu.pages(45));
        assertEquals(2, PortalMenu.pages(46));
        assertEquals(6, PortalMenu.pages(256));
        assertEquals(0, PortalMenu.page(-3, 10));
        assertEquals(1, PortalMenu.page(7, 50));
    }

    @Test void warmupButtonStepsAndStaysInRange() {
        assertEquals(10, PortalMenu.warmupStep(0, false, false));
        assertEquals(0, PortalMenu.warmupStep(0, true, false));
        assertEquals(30, PortalMenu.warmupStep(40, true, false));
        assertEquals(90, PortalMenu.warmupStep(40, false, true));
        assertEquals(200, PortalMenu.warmupStep(190, false, true));
        assertEquals(0, PortalMenu.warmupStep(20, true, true));
    }

    @Test void englishAndGermanHaveTheSameKeysAndListsStayLists() {
        YamlConfiguration en = lang("en"), de = lang("de");
        assertEquals(en.getKeys(true), de.getKeys(true));
        for (String key : en.getKeys(true)) {
            if (en.isConfigurationSection(key)) continue;
            assertEquals(en.isList(key), de.isList(key), key);
            assertFalse(en.getString(key, "").contains("<arg"), key);
        }
        for (String key : List.of("menu.title", "menu.portal-lore", "menu.create", "menu.delete-hint", "trust-menu.entry", "trust-menu.trusted-lore",
                "trusted", "untrusted", "trusted-list", "trusted-notify", "menu-disabled", "trust-disabled"))
            assertTrue(en.contains(key) && de.contains(key), key);
        assertTrue(en.getString("usage").contains("trust NAME") && en.getString("usage").contains("menu"));
    }

    private static YamlConfiguration lang(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PortalMenuTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }
    private static Portal portal(String id, UUID owner, boolean open) {
        return new Portal(id, owner, new ViewFrame(WORLD, new Point3(0.5, 64, 0.5), 0, 1.5, 3), null, open, 0);
    }
}
