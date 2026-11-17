package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HuskHomesImportTest {
    private static final UUID WORLD = UUID.randomUUID(), OWNER = UUID.randomUUID();

    private static HuskHomesImport.Row row(UUID owner, String name, String world) {
        return new HuskHomesImport.Row(owner, name, world, null, 1.5, 64, -2.5, 90f, 10f);
    }

    @Test void givenHuskHomesNamesWhenKeyedThenTheyFitTravelNames() {
        assertEquals("my_base", HuskHomesImport.key("My Base"));
        assertEquals("home", HuskHomesImport.key(""));
        assertEquals(32, HuskHomesImport.key("x".repeat(40)).length());
        assertTrue(TravelPoints.validName(HuskHomesImport.key("Ünïcode!Home")));
    }

    @Test void givenRowsWhenAppliedThenMissingOnesAreAddedAndExistingKept(@TempDir Path dir) throws Exception {
        TravelPoints points = new TravelPoints(dir.resolve("travel.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        points.setHome(OWNER, "base", new TravelPoints.Point(WORLD, 0, 70, 0, 0, 0));
        var outcome = HuskHomesImport.apply(List.of(row(OWNER, "Base", "world"), row(OWNER, "Farm", "world"),
                row(null, "Spawn", "world"), row(null, "spawn", "world"), row(OWNER, "Mine", "gone")), points,
                (uuid, name) -> "world".equals(name) ? WORLD : null);
        assertEquals(new HuskHomesImport.Outcome(1, 1, 2, 1), outcome);
        assertEquals(70, points.homes(OWNER).get("base").y());
        assertEquals(64, points.homes(OWNER).get("farm").y());
        TravelPoints reloaded = new TravelPoints(dir.resolve("travel.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(List.of("base", "farm"), List.copyOf(reloaded.homes(OWNER).keySet()));
        assertEquals(List.of("spawn"), List.copyOf(reloaded.warps().keySet()));
    }
}
