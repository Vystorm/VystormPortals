package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortalStoreTest {
    private static final UUID OWNER_A = UUID.fromString("d91a1c21-5e70-4a84-98ec-9dfeb15b5312");
    private static final UUID OWNER_B = UUID.fromString("5e166ebd-f2f2-47c0-a28a-b11f214d8b8e");
    private static final UUID WORLD_A = UUID.fromString("82e9e5cc-4d23-4da8-85c2-c08eab4420dc");
    private static final UUID WORLD_B = UUID.fromString("ea1d6722-9813-42ba-b585-f26c301acb48");

    @TempDir Path directory;

    @Test
    void givenPortalsInDifferentWorldsWhenSavingAndLoadingThenPreservesGeometryOwnershipAccessWarmupAndLinks() throws Exception {
        PortalStore store = store();
        Portal west = portal("west", OWNER_A, WORLD_A, new Point3(-18.5, 67.25, 42), 0,
                2.5, 5, "east", true, 40);
        Portal east = portal("east", OWNER_B, WORLD_B, new Point3(301, -12, 8.75), 3,
                1.25, 3.75, "west", false, 200);

        store.save(Map.of(west.id(), west, east.id(), east));

        Map<String, Portal> loaded = store.load();
        assertEquals(Map.of(west.id(), west, east.id(), east), loaded);
        assertEquals(WORLD_A, loaded.get("west").frame().world());
        assertEquals(0, loaded.get("west").frame().quarterTurns());
        assertEquals(3, loaded.get("east").frame().quarterTurns());
        assertTrue(loaded.get("west").publicAccess());
        assertFalse(loaded.get("east").publicAccess());
        assertEquals(40, loaded.get("west").warmupTicks());
        assertEquals("west", loaded.get("east").target());
    }

    @Test
    void givenValidSnapshotWhenSavingUnlinkedPortalAfterRemovingTargetThenRoundTripsNewSnapshot() throws Exception {
        PortalStore store = store();
        Portal source = portal("source", OWNER_A, WORLD_A, new Point3(0, 64, 0), 0,
                2, 4, "destination", false, 0);
        Portal destination = portal("destination", OWNER_A, WORLD_B, new Point3(100, 64, 0), 2,
                2, 4, "source", true, 20);
        store.save(Map.of(source.id(), source, destination.id(), destination));

        Portal unlinked = source.link(null);
        store.save(Map.of(unlinked.id(), unlinked));

        assertEquals(Map.of("source", unlinked), store.load());
        assertNull(store.load().get("source").target());
    }

    @Test
    void givenLoadedSnapshotWhenCallerAttemptsMutationThenMapIsImmutable() throws Exception {
        PortalStore store = store();
        Portal only = portal("only", OWNER_A, WORLD_A, new Point3(0, 70, 0), 1,
                1, 2, null, false, 10);
        store.save(Map.of(only.id(), only));

        Map<String, Portal> loaded = store.load();

        assertThrows(UnsupportedOperationException.class, () -> loaded.put("other", only));
        assertThrows(UnsupportedOperationException.class, loaded::clear);
    }

    @Test
    void givenUnsupportedSchemaMissingSectionOrMalformedPortalDataWhenLoadingThenFailsClosed() throws Exception {
        Path file = directory.resolve("portals.yml");
        PortalStore store = new PortalStore(file);

        Files.writeString(file, "schema: 2\nportals: {}\n");
        assertThrows(Exception.class, store::load);
        Files.writeString(file, "schema: 1\n");
        assertThrows(Exception.class, store::load);
        Files.writeString(file, "schema: 1\nportals:\n  broken:\n    owner: not-a-uuid\n");
        assertThrows(Exception.class, store::load);
        Files.writeString(file, "schema: 1\nportals:\n  malformed key:\n    owner: " + OWNER_A + "\n");
        assertThrows(Exception.class, store::load);
    }

    @Test
    void givenMissingLinkTargetWhenLoadingThenFailsClosed() throws Exception {
        Path file = directory.resolve("portals.yml");
        Files.writeString(file, """
                schema: 1
                portals:
                  entry:
                    owner: %s
                    world: %s
                    x: 1.0
                    y: 64.0
                    z: 2.0
                    turns: 1
                    half-width: 2.0
                    height: 4.0
                    target: nowhere
                    public: false
                    warmup: 0
                """.formatted(OWNER_A, WORLD_A));

        assertThrows(IllegalArgumentException.class, () -> new PortalStore(file).load());
    }

    @Test
    void givenInvalidSnapshotWhenSavingThenPreservesPriorValidFile() throws Exception {
        Path file = directory.resolve("portals.yml");
        PortalStore store = new PortalStore(file);
        Portal stable = portal("stable", OWNER_A, WORLD_A, new Point3(4, 65, -2), 0,
                1, 2, null, true, 0);
        store.save(Map.of(stable.id(), stable));
        byte[] before = Files.readAllBytes(file);
        Portal dangling = stable.link("missing");

        assertThrows(IllegalArgumentException.class, () -> store.save(Map.of(stable.id(), dangling)));

        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(Map.of(stable.id(), stable), store.load());
    }

    @Test
    void givenMoreThan256PortalsWhenSavingThenRejectsSnapshotBeforeCreatingFile() {
        Map<String, Portal> tooMany = new HashMap<>();
        for (int i = 0; i < 257; i++) {
            Portal portal = portal("p" + i, OWNER_A, WORLD_A, new Point3(i, 64, 0), i % 4,
                    1, 2, null, false, 0);
            tooMany.put(portal.id(), portal);
        }
        Path file = directory.resolve("bounded.yml");

        assertThrows(IllegalArgumentException.class, () -> new PortalStore(file).save(tooMany));
        assertFalse(Files.exists(file));
    }

    @Test
    void givenMissingFileWhenLoadingThenReturnsImmutableEmptySnapshot() throws Exception {
        Map<String, Portal> loaded = store().load();
        assertTrue(loaded.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> loaded.put("x", portal(
                "x", OWNER_A, WORLD_A, new Point3(0, 0, 0), 0, 1, 1, null, false, 0)));
    }

    @Test
    void givenWrongYamlTypesWhenLoadingThenRejectsInsteadOfDefaultingCoordinatesOrAccess() throws Exception {
        PortalStore store = store();
        Portal stable = portal("stable", OWNER_A, WORLD_A, new Point3(4, 65, -2), 0, 1, 2, null, true, 0);
        Path file = directory.resolve("portals.yml");
        for (String field : java.util.List.of("x", "turns", "public", "warmup", "target")) {
            store.save(Map.of("stable", stable));
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.load(file.toFile());
            yaml.set("portals.stable." + field, field.equals("target") ? 42 : "invalid");
            yaml.save(file.toFile());
            assertThrows(Exception.class, store::load, field);
        }
    }

    private PortalStore store() {
        return new PortalStore(directory.resolve("portals.yml"));
    }

    private static Portal portal(String id, UUID owner, UUID world, Point3 origin, int orientation,
            double halfWidth, double height, String target, boolean publicAccess, int warmup) {
        return new Portal(id, owner, new ViewFrame(world, origin, orientation, halfWidth, height),
                target, publicAccess, warmup);
    }
}
