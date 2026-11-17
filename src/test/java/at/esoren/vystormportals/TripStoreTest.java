package at.esoren.vystormportals;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TripStoreTest {
    @TempDir Path folder;

    @Test void malformedStopEntriesCannotBeSilentlyDroppedOrCoerced() throws Exception {
        Path file=folder.resolve("trips.yml");
        UUID owner=UUID.randomUUID();
        for(Object stops:List.of(List.of("a",Map.of("broken","stop"),"b"),List.of("a",42,"b"))) {
            YamlConfiguration yaml=new YamlConfiguration();
            yaml.set("schema",1);yaml.set("trips.0.id","tour");yaml.set("trips.0.owner",owner.toString());yaml.set("trips.0.stops",stops);
            yaml.save(file.toFile());String original=Files.readString(file);
            assertThrows(Exception.class,()->new TripStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
            assertEquals(original,Files.readString(file));
        }
    }

    @Test void fractionalSchemaIsRejectedWithoutRewriting() throws Exception {
        Path file=folder.resolve("trips.yml");String original="schema: 1.5\ntrips: {}\n";
        Files.writeString(file,original);
        assertThrows(Exception.class,()->new TripStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        assertEquals(original,Files.readString(file));
    }

    @Test void loadedPlansRespectTheSamePerPlayerLimitAsSavedPlans() throws Exception {
        Path file=folder.resolve("trips.yml");UUID owner=UUID.randomUUID();
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("schema",1);
        for(int i=0;i<17;i++) {
            yaml.set("trips."+i+".id","tour"+i);yaml.set("trips."+i+".owner",owner.toString());yaml.set("trips."+i+".stops",List.of("a","b"));
        }
        yaml.save(file.toFile());
        assertThrows(Exception.class,()->new TripStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
    }
}
