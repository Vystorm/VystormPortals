package at.esoren.vystormportals;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class TripStore {
    private final Path file;
    private Map<String, PortalTrip> trips = new LinkedHashMap<>();
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    TripStore(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) throws Exception {
        this.files = files;
        this.file = file; if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        if (!yaml.isInt("schema") || yaml.getInt("schema") != 1) throw new IOException("Unsupported trip schema");
        var root = yaml.getConfigurationSection("trips"); if (root == null) throw new IOException("Missing trips");
        for (String key : root.getKeys(false)) {
            var row = root.getConfigurationSection(key); if (row == null) throw new IOException("Invalid trip");
            if (!row.isString("id") || !row.isString("owner") || !row.isList("stops")
                    || row.getList("stops").stream().anyMatch(stop -> !(stop instanceof String)))
                throw new IOException("Invalid trip fields");
            PortalTrip trip = new PortalTrip(row.getString("id"), UUID.fromString(row.getString("owner")), row.getStringList("stops"));
            if (trips.put(key(trip.owner(),trip.id()),trip) != null || trips.size() > 256) throw new IOException("Duplicate/excess trips");
            if (all(trip.owner()).size() > 16) throw new IOException("Excess trips for player");
        }
    }
    private static String key(UUID owner,String id) { return owner + "/" + id; }
    PortalTrip get(UUID owner,String id) { return trips.get(key(owner,id)); }
    List<PortalTrip> all(UUID owner) { return trips.values().stream().filter(trip -> trip.owner().equals(owner)).toList(); }
    void put(PortalTrip trip) throws IOException {
        Map<String,PortalTrip> next = new LinkedHashMap<>(trips); next.put(key(trip.owner(),trip.id()),trip);
        if (next.size() > 256 || next.values().stream().filter(t -> t.owner().equals(trip.owner())).count() > 16)
            throw new IllegalArgumentException("limit");
        save(next);
    }
    void remove(PortalTrip trip) throws IOException {
        Map<String,PortalTrip> next = new LinkedHashMap<>(trips); next.remove(key(trip.owner(),trip.id())); save(next);
    }
    private void save(Map<String,PortalTrip> next) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema",1); yaml.createSection("trips"); int index = 0;
        for (PortalTrip trip : next.values()) {
            String p = "trips." + index++ + "."; yaml.set(p+"id",trip.id()); yaml.set(p+"owner",trip.owner().toString()); yaml.set(p+"stops",trip.stops());
        }
        Files.createDirectories(file.getParent()); files.write(file,yaml.saveToString().getBytes(StandardCharsets.UTF_8)); trips = next;
    }
}
