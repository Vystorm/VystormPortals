package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalTrustTest {
    private static final UUID OWNER = UUID.fromString("ae77dd8b-3bb4-4e79-9f54-900477d10230");
    private static final UUID FRIEND = UUID.fromString("0f1b3c3e-5b2a-4d7f-8f39-4a1c2b9d7e01");
    private static final UUID OTHER = UUID.fromString("9a7c1e55-2d3b-4c8e-b1f0-6e5d4c3b2a10");
    private static final UUID WORLD = UUID.fromString("02e66380-761b-4e67-953a-363fa21a3eb8");

    @TempDir Path directory;

    @Test void trustAndUntrustReturnCopiesAndOtherChangesKeepTheList() {
        Portal portal = portal("gate").trust(FRIEND);
        assertEquals(Set.of(FRIEND), portal.trusted());
        assertEquals(Set.of(FRIEND), portal.link("home").access(true).warmup(40).trusted());
        assertEquals(Set.of(), portal.untrust(FRIEND).trusted());
        assertThrows(UnsupportedOperationException.class, () -> portal.trusted().add(OTHER));
        assertEquals(Set.of(), new Portal("gate", OWNER, frame(), null, false, 0, null).trusted());
    }

    @Test void ownerCannotBeTrustedAndTheHardCapHolds() {
        assertThrows(IllegalArgumentException.class, () -> portal("gate").trust(OWNER));
        Set<UUID> many = new HashSet<>();
        for (int i = 0; i <= Portal.MAX_TRUSTED; i++) many.add(new UUID(1, i));
        assertThrows(IllegalArgumentException.class, () -> new Portal("gate", OWNER, frame(), null, false, 0, many));
    }

    @Test void trustedHelperRejectsDuplicatesUnknownEntriesTheOwnerAndAFullList() {
        Map<String, Portal> portals = Map.of("gate", portal("gate"));
        Map<String, Portal> once = PortalsPlugin.trusted(portals, "gate", FRIEND, true, 1);
        assertEquals(Set.of(FRIEND), once.get("gate").trusted());
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.trusted(once, "gate", FRIEND, true, 5));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.trusted(once, "gate", OTHER, true, 1));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.trusted(portals, "gate", OWNER, true, 5));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.trusted(portals, "gate", OTHER, false, 5));
        assertThrows(IllegalArgumentException.class, () -> PortalsPlugin.trusted(portals, "missing", OTHER, true, 5));
        assertEquals(Set.of(), PortalsPlugin.trusted(once, "gate", FRIEND, false, 5).get("gate").trusted());
        assertTrue(portals.get("gate").trusted().isEmpty(), "input map stays unchanged");
    }

    @Test void trustedListsRoundTripAndOldFilesWithoutThemStillLoad() throws Exception {
        Path file = directory.resolve("portals.yml");
        Files.writeString(file, """
                schema: 1
                portals:
                  old:
                    owner: %s
                    world: %s
                    x: 1.0
                    y: 64.0
                    z: 2.0
                    turns: 1
                    half-width: 1.5
                    height: 3.0
                    public: false
                    warmup: 0
                """.formatted(OWNER, WORLD));
        PortalStore store = new PortalStore(file);
        Map<String, Portal> loaded = store.load();
        assertEquals(Set.of(), loaded.get("old").trusted());

        Map<String, Portal> next = new TreeMap<>(loaded);
        next.put("old", loaded.get("old").trust(FRIEND).trust(OTHER));
        store.save(next);
        assertEquals(Set.of(FRIEND, OTHER), store.load().get("old").trusted());

        next.put("old", next.get("old").untrust(FRIEND).untrust(OTHER));
        store.save(next);
        assertFalse(Files.readString(file).contains("trusted"), "empty lists are not written");
    }

    @Test void brokenTrustedEntriesFailClosed() throws Exception {
        Path file = directory.resolve("portals.yml");
        PortalStore store = new PortalStore(file);
        store.save(Map.of("gate", portal("gate").trust(FRIEND)));
        for (Object broken : List.of("not-a-list", List.of("not-a-uuid"), List.of(42), List.of(OWNER.toString()))) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            yaml.set("portals.gate.trusted", broken);
            yaml.save(file.toFile());
            assertThrows(Exception.class, store::load, String.valueOf(broken));
        }
    }

    @Test void trustCommandsCompleteNamesAndPlayers() {
        List<String> ids = List.of("east", "west");
        List<String> players = List.of("Alex", "Steve");
        assertEquals(List.of("trust"), PortalsPlugin.suggestions(new String[]{"TR"}, ids, ids, players));
        assertEquals(ids, PortalsPlugin.suggestions(new String[]{"untrust", ""}, ids, ids, players));
        assertEquals(List.of("Steve"), PortalsPlugin.suggestions(new String[]{"trust", "east", "st"}, ids, ids, players));
        assertEquals(List.of(), PortalsPlugin.suggestions(new String[]{"trust", "east", "Alex", ""}, ids, ids, players));
        assertTrue(PortalsPlugin.suggestions(new String[]{"m"}, ids, ids).contains("menu"));
    }

    @Test void trustConfigIsClampedAndDefaultsOn() {
        YamlConfiguration empty = new YamlConfiguration();
        assertEquals(new PortalsPlugin.Trust(true, 16, true), PortalsPlugin.Trust.read(empty));
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("trust.enabled", false);
        custom.set("trust.max-per-portal", 999);
        custom.set("trust.player-menu", false);
        assertEquals(new PortalsPlugin.Trust(false, Portal.MAX_TRUSTED, false), PortalsPlugin.Trust.read(custom));
    }

    @Test void playerMenuCountsOnlyTheViewersOwnPortals() {
        Portal mine = portal("b-mine").trust(FRIEND);
        Portal mineToo = portal("a-mine");
        Portal foreign = new Portal("foreign", OTHER, frame(), null, false, 0).trust(FRIEND);
        List<Portal> all = List.of(mine, foreign, mineToo);
        assertEquals(List.of(mineToo, mine), TrustMenuEntries.own(all, OWNER));
        assertEquals(1, TrustMenuEntries.trustedOn(all, OWNER, FRIEND));
        assertEquals(0, TrustMenuEntries.trustedOn(all, OWNER, OTHER));
    }

    private static Portal portal(String id) { return new Portal(id, OWNER, frame(), null, false, 0); }
    private static ViewFrame frame() { return new ViewFrame(WORLD, new Point3(0.5, 64, 0.5), 0, 1.5, 3); }
}
