package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortalLinkingTest {
    private static final UUID OWNER = UUID.fromString("ae77dd8b-3bb4-4e79-9f54-900477d10230");
    private static final UUID WORLD = UUID.fromString("02e66380-761b-4e67-953a-363fa21a3eb8");

    @Test
    void relinkingClearsTheOneWayLinkOfTheFormerPartners() {
        Map<String, Portal> portals = PortalsPlugin.linked(PortalsPlugin.linked(portals("a", "b", "c", "d"), "a", "b"), "c", "d");

        Map<String, Portal> next = PortalsPlugin.linked(portals, "a", "c");

        assertEquals("c", next.get("a").target());
        assertEquals("a", next.get("c").target());
        assertNull(next.get("b").target());
        assertNull(next.get("d").target());
        assertEquals("b", portals.get("a").target(), "input map stays unchanged");
    }

    @Test
    void unlinkingClearsBothSidesButKeepsThePortals() {
        Map<String, Portal> next = PortalsPlugin.unlinked(PortalsPlugin.linked(portals("a", "b"), "a", "b"), "b");

        assertEquals(2, next.size());
        assertNull(next.get("a").target());
        assertNull(next.get("b").target());
        assertEquals(portals("a", "b"), PortalsPlugin.unlinked(portals("a", "b"), "a"), "unlinked portal is a no-op");
    }

    @Test
    void renamingMovesThePortalAndItsPartnerFollows() {
        Map<String, Portal> portals = PortalsPlugin.linked(portals("a", "b", "c"), "a", "b");
        portals.put("a", portals.get("a").warmup(40).access(false).trust(UUID.fromString("11111111-2222-3333-4444-555555555555")));

        Map<String, Portal> next = PortalsPlugin.renamed(portals, "a", "home");

        assertEquals(3, next.size());
        assertNull(next.get("a"));
        Portal home = next.get("home");
        assertEquals("b", home.target());
        assertEquals("home", next.get("b").target());
        assertEquals(40, home.warmupTicks());
        assertEquals(false, home.publicAccess());
        assertEquals(1, home.trusted().size());
        assertEquals(portals.get("a").frame(), home.frame());
        assertNull(next.get("c").target());
        assertEquals("a", portals.get("b").target(), "input map stays unchanged");
    }

    @Test
    void renamingRefusesTakenInvalidAndUnknownNames() {
        Map<String, Portal> portals = portals("a", "b");
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.renamed(portals, "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.renamed(portals, "a", "a"));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.renamed(portals, "a", "Bad Name"));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.renamed(portals, "x", "y"));
    }

    @Test
    void linkingAPortalToItselfIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.linked(portals("a"), "a", "a"));
    }

    @Test
    void infoShowsBlockPositionSizeAccessWarmupTargetAndOwner() {
        Portal portal = new Portal("gate", OWNER, new ViewFrame(WORLD, new Point3(-12.5, 64, 7.5), 1, 1.5, 4), "home", true, 40);

        assertArrayEquals(new Object[]{"gate", "world", "-13 64 7", "3x4", "public", 40, "home", "Esoren"},
                PortalsPlugin.info(portal, "world", "Esoren"));
        Object[] unnamed = PortalsPlugin.info(portal.link(null).access(false), "world", null);
        assertEquals("private", unnamed[4]);
        assertEquals("-", unnamed[6]);
        assertEquals(OWNER.toString(), unnamed[7]);
    }

    private static Map<String, Portal> portals(String... ids) {
        Map<String, Portal> portals = new TreeMap<>();
        for (String id : ids) portals.put(id, new Portal(id, OWNER, new ViewFrame(WORLD, new Point3(0.5, 64, 0.5), 0, 1.5, 3), null, false, 0));
        return portals;
    }
}
