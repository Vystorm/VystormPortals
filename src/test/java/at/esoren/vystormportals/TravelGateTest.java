package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TravelGateTest {
    static final UUID W = UUID.randomUUID();

    /** A world with stone below y 64, air above, and extra wall blocks. */
    record World(java.util.Set<BlockPosition> walls) implements TravelGate.Probe {
        public boolean wall(int x, int y, int z) { return walls.contains(new BlockPosition(x, y, z)) || y < 64; }
        public boolean free(int x, int y, int z) { return !wall(x, y, z); }
        public boolean floor(int x, int y, int z) { return wall(x, y, z); }
    }

    static java.util.Set<BlockPosition> wallAt(int z, int fromX, int toX) {
        var out = new HashSet<BlockPosition>();
        for (int x = fromX; x <= toX; x++) for (int y = 64; y < 68; y++) out.add(new BlockPosition(x, y, z));
        return out;
    }

    @Test
    void gateFormsInTheWallAhead() {
        // player at 0/64/0 looking south (+z), wall at z = 2
        TravelGate gate = TravelGate.place(W, 0, 64, 0, 0, new World(wallAt(2, -3, 3)));
        assertNotNull(gate);
        assertTrue(gate.inWall());
        assertEquals(java.util.List.of(new BlockPosition(0, 64, 2), new BlockPosition(0, 65, 2)), gate.blocks());
        assertTrue(gate.contains(0.5, 64, 1.7), "pressing against the wall walks in");
        assertFalse(gate.contains(0.5, 64, 0.9), "a step before the wall does not");
        assertFalse(gate.contains(1.5, 64, 1.7), "next column does not");
    }

    @Test
    void gateStandsFreeWithoutAWallAndStepsUpOnBumps() {
        TravelGate open = TravelGate.place(W, 0, 64, 0, 0, new World(java.util.Set.of()));
        assertFalse(open.inWall());
        assertEquals(new BlockPosition(0, 64, 2), open.base());
        // a 1-high bump at distance 1 and 2 (too low for a wall gate): the gate goes one block up
        var bumps = new HashSet<BlockPosition>(java.util.List.of(new BlockPosition(0, 64, 1), new BlockPosition(0, 64, 2)));
        TravelGate step = TravelGate.place(W, 0, 64, 0, 0, new World(bumps));
        assertFalse(step.inWall());
        assertEquals(65, step.base().y());
        // a wall 4 blocks away is too far: free standing gate
        assertFalse(TravelGate.place(W, 0, 64, 0, 0, new World(wallAt(4, -3, 3))).inWall());
        // a 1-block corridor ceiling at 66 with a wall right ahead: wall gate at distance 1
        assertTrue(TravelGate.place(W, 0, 64, 0, 0, new World(wallAt(1, -3, 3))).inWall());
    }

    @Test
    void windowFacesThePlayerAndExitFacesTheArrivalView() {
        for (int q = 0; q < 4; q++) {
            int[] f = TravelGate.forward(q);
            TravelGate gate = TravelGate.place(W, 0, 64, 0, q, new World(java.util.Set.of()));
            ViewFrame entry = gate.entry();
            assertTrue(entry.local(new Point3(.5, 65.6, .5)).z() > 1, "player in front for " + q);
            ViewFrame exit = TravelGate.exit(W, 100.5, 70, 100.5, q * 90f);
            Point3 arrival = exit.global(new Point3(0, 0, .8));
            assertEquals(100.5, arrival.x(), 1e-9); assertEquals(100.5, arrival.z(), 1e-9);
            Point3 walk = entry.directionTo(exit, entry.global(new Point3(0, 0, -1)).subtract(entry.origin()));
            assertEquals(f[0], walk.x(), 1e-9); assertEquals(f[1], walk.z(), 1e-9);
        }
        assertEquals(16, TravelGate.place(W, 0, 64, 0, 1, new World(java.util.Set.of())).edges(4).size());
    }

    @Test
    void energyCostsScaleWithDistanceAndRecharge() {
        PortalEnergy.Rules rules = new PortalEnergy.Rules(true, 100, 5, 5, 2, 40);
        assertEquals(5, rules.cost(0));
        assertEquals(25, rules.cost(1000));
        assertEquals(45, rules.cost(-1), "other world");
        assertEquals(100, rules.cost(1_000_000), "never more than max");
        assertEquals(15, rules.recharge(10, 60_000), 1e-9);
        assertEquals(100, rules.recharge(99, 3_600_000), 1e-9);
        assertEquals(120, rules.secondsUntil(10, 20));
        assertEquals(0, rules.secondsUntil(30, 20));
        assertEquals(-1, new PortalEnergy.Rules(true, 100, 0, 5, 2, 40).secondsUntil(10, 20));
    }

    @Test
    void energyIsTakenOnlyWhenEnoughAndRefunded(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("energy.yml");
        PortalEnergy.Rules rules = new PortalEnergy.Rules(true, 100, 6, 5, 2, 40);
        PortalEnergy energy = new PortalEnergy(file, rules, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID p = UUID.randomUUID();
        assertEquals(100, energy.current(p, 0), 1e-9, "new players start full");
        assertTrue(energy.take(p, 70, 0));
        assertFalse(energy.take(p, 40, 0));
        assertEquals(30, energy.current(p, 0), 1e-9);
        assertEquals(36, energy.current(p, 60_000), 1e-9);
        energy.refund(p, 70, 0);
        assertEquals(100, new PortalEnergy(file, rules, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).current(p, 0), 1e-9, "saved");
        assertTrue(Files.isRegularFile(file));
        YamlConfiguration config = new YamlConfiguration();
        assertFalse(PortalEnergy.Rules.read(config).enabled(), "off by default");
    }

    @Test
    void playersWithAFullBarAgainLeaveEnergyFileWithoutChangingTheirEnergy(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("energy.yml");
        PortalEnergy.Rules rules = new PortalEnergy.Rules(true, 100, 10, 5, 2, 40);
        PortalEnergy energy = new PortalEnergy(file, rules, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID early = UUID.randomUUID(), late = UUID.randomUUID();
        assertTrue(energy.take(early, 50, 0));
        assertEquals(1, energy.stored());
        long later = 6 * 60_000; // 10 per minute: 50 is back after 5 minutes
        assertTrue(energy.take(late, 20, later));
        assertEquals(1, energy.stored(), "the player whose bar filled up again is no longer kept");
        assertEquals(100, energy.current(early, later), 1e-9);
        PortalEnergy reread = new PortalEnergy(file, rules, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(1, reread.stored());
        assertFalse(YamlConfiguration.loadConfiguration(file.toFile()).contains("energy." + early));
        assertEquals(100, reread.current(early, later), 1e-9);
        assertEquals(80, reread.current(late, later), 1e-9);
    }

    @Test
    void homesAndWarpsAreSaved(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("travel.yml");
        TravelPoints points = new TravelPoints(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID p = UUID.randomUUID();
        points.setHome(p, "home", new TravelPoints.Point(W, 1, 2, 3, 90, 10));
        points.setWarp("spawn", new TravelPoints.Point(W, 4, 5, 6, 0, 0));
        TravelPoints again = new TravelPoints(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(new TravelPoints.Point(W, 1, 2, 3, 90, 10), again.homes(p).get("home"));
        assertEquals(1, again.warps().size());
        assertTrue(again.deleteHome(p, "home"));
        assertFalse(again.deleteHome(p, "home"));
        assertTrue(TravelPoints.validName("my_base-2"));
        assertFalse(TravelPoints.validName("My Base"));
    }
}
