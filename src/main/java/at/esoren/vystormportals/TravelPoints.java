package at.esoren.vystormportals;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Homes (per player) and warps (server-wide) for travel gates (1.21.0), stored in {@code travel.yml}. Names are
 * 1–32 lowercase letters, digits, underscores or hyphens. Server thread.
 */
final class TravelPoints {
    /** A saved place: world UUID, feet position and view. */
    record Point(UUID world, double x, double y, double z, float yaw, float pitch) {}

    static final String NAME = "[a-z0-9_-]{1,32}";
    private final Path file;
    private final Map<UUID, TreeMap<String, Point>> homes = new HashMap<>();
    private final TreeMap<String, Point> warps = new TreeMap<>();

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    TravelPoints(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        if (!Files.isRegularFile(file)) return;
        YamlConfiguration yaml = YamlCheck.loadData(file, java.util.logging.Logger.getLogger("VystormPortals"));
        ConfigurationSection h = yaml.getConfigurationSection("homes");
        if (h != null) for (String owner : h.getKeys(false)) {
            try {
                UUID id = UUID.fromString(owner);
                ConfigurationSection mine = h.getConfigurationSection(owner);
                if (mine != null) for (String name : mine.getKeys(false)) read(mine.getConfigurationSection(name))
                        .ifPresent(p -> homes.computeIfAbsent(id, k -> new TreeMap<>()).put(name, p));
            } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection w = yaml.getConfigurationSection("warps");
        if (w != null) for (String name : w.getKeys(false)) read(w.getConfigurationSection(name)).ifPresent(p -> warps.put(name, p));
    }

    private static Optional<Point> read(ConfigurationSection s) {
        if (s == null) return Optional.empty();
        try {
            return Optional.of(new Point(UUID.fromString(s.getString("world", "")), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch")));
        } catch (IllegalArgumentException broken) { return Optional.empty(); }
    }

    private static void write(ConfigurationSection s, Point p) {
        s.set("world", p.world().toString()); s.set("x", p.x()); s.set("y", p.y()); s.set("z", p.z());
        s.set("yaw", (double) p.yaw()); s.set("pitch", (double) p.pitch());
    }

    static boolean validName(String name) { return name != null && name.matches(NAME); }

    SortedMap<String, Point> homes(UUID owner) { return Collections.unmodifiableSortedMap(homes.getOrDefault(owner, new TreeMap<>())); }
    SortedMap<String, Point> warps() { return Collections.unmodifiableSortedMap(warps); }

    void setHome(UUID owner, String name, Point point) throws IOException {
        homes.computeIfAbsent(owner, k -> new TreeMap<>()).put(name, point); save();
    }
    boolean deleteHome(UUID owner, String name) throws IOException {
        TreeMap<String, Point> mine = homes.get(owner);
        if (mine == null || mine.remove(name) == null) return false;
        if (mine.isEmpty()) homes.remove(owner);
        save(); return true;
    }
    void setWarp(String name, Point point) throws IOException { warps.put(name, point); save(); }
    boolean deleteWarp(String name) throws IOException {
        if (warps.remove(name) == null) return false;
        save(); return true;
    }

    /** Import (1.22.0): puts all given homes and warps and saves once. Callers leave out names that already exist. */
    void addAll(Map<UUID, Map<String, Point>> newHomes, Map<String, Point> newWarps) throws IOException {
        newHomes.forEach((owner, mine) -> { if (!mine.isEmpty()) homes.computeIfAbsent(owner, k -> new TreeMap<>()).putAll(mine); });
        warps.putAll(newWarps);
        save();
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        homes.forEach((owner, mine) -> mine.forEach((name, p) -> write(yaml.createSection("homes." + owner + "." + name), p)));
        warps.forEach((name, p) -> write(yaml.createSection("warps." + name), p));
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }
}
