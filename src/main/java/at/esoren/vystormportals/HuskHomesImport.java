package at.esoren.vystormportals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.BiFunction;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One-off import of HuskHomes homes and warps into {@code travel.yml} (1.22.0, {@code /vportal import huskhomes}).
 * Reads the database named in {@code plugins/HuskHomes/config.yml} (SQLite, MySQL or MariaDB) and never writes to it.
 * {@link #read} blocks on the database and belongs off the server thread; {@link #apply} runs on the server thread.
 */
final class HuskHomesImport {
    /** A saved HuskHomes position; {@code owner == null} is a warp. */
    record Row(UUID owner, String name, String worldName, String worldUuid, double x, double y, double z, float yaw, float pitch) {}

    /** What an import did. {@code existing}: a home or warp of that name was already there and kept. {@code noWorld}: world not loaded. */
    record Outcome(int homes, int warps, int existing, int noWorld) {}

    private HuskHomesImport() {}

    /** Reads all homes and warps. Throws when HuskHomes has no readable config or the database cannot be opened. */
    static List<Row> read(Path huskHomes) throws IOException, SQLException {
        Path config = huskHomes.resolve("config.yml");
        if (!Files.isRegularFile(config)) throw new IOException("plugins/HuskHomes/config.yml not found");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config.toFile());
        String home = table(yaml, "HOME_DATA", "huskhomes_homes"), warp = table(yaml, "WARP_DATA", "huskhomes_warps");
        String saved = table(yaml, "SAVED_POSITION_DATA", "huskhomes_saved_positions"), position = table(yaml, "POSITION_DATA", "huskhomes_position_data");
        String columns = "s.name, p.world_name, p.world_uuid, p.x, p.y, p.z, p.yaw, p.pitch";
        String joins = " JOIN " + saved + " s ON t.saved_position_id = s.id JOIN " + position + " p ON s.position_id = p.id";
        List<Row> rows = new ArrayList<>();
        try (Connection connection = open(yaml, huskHomes); Statement statement = connection.createStatement()) {
            try (ResultSet r = statement.executeQuery("SELECT t.owner_uuid, " + columns + " FROM " + home + " t" + joins)) {
                while (r.next()) {
                    UUID owner;
                    try { owner = UUID.fromString(r.getString(1)); } catch (RuntimeException broken) { continue; }
                    rows.add(row(owner, r, 2));
                }
            }
            try (ResultSet r = statement.executeQuery("SELECT " + columns + " FROM " + warp + " t" + joins)) {
                while (r.next()) rows.add(row(null, r, 1));
            }
        }
        return rows;
    }

    private static Row row(UUID owner, ResultSet r, int from) throws SQLException {
        return new Row(owner, r.getString(from), r.getString(from + 1), r.getString(from + 2), r.getDouble(from + 3), r.getDouble(from + 4),
                r.getDouble(from + 5), r.getFloat(from + 6), r.getFloat(from + 7));
    }

    /** Table name from HuskHomes' config; anything but letters, digits and underscores falls back to the default. */
    private static String table(YamlConfiguration yaml, String key, String fallback) {
        String name = yaml.getString("database.table_names." + key, fallback);
        return name != null && name.matches("[A-Za-z0-9_]{1,64}") ? name : fallback;
    }

    private static Connection open(YamlConfiguration yaml, Path huskHomes) throws SQLException, IOException {
        String type = yaml.getString("database.type", "SQLITE").toUpperCase(Locale.ROOT);
        switch (type) {
            case "SQLITE" -> {
                Path file = huskHomes.resolve("HuskHomesData.db");
                if (!Files.isRegularFile(file)) throw new IOException("HuskHomesData.db not found");
                driver("org.sqlite.JDBC");
                return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            }
            case "MYSQL", "MARIADB" -> {
                String base = "database.credentials.";
                String parameters = yaml.getString(base + "parameters", "");
                String url = "jdbc:mysql://" + yaml.getString(base + "host", "localhost") + ":" + yaml.getInt(base + "port", 3306) + "/"
                        + yaml.getString(base + "database", "HuskHomes") + (parameters == null ? "" : parameters);
                driver("com.mysql.cj.jdbc.Driver");
                return DriverManager.getConnection(url, yaml.getString(base + "username", "root"), yaml.getString(base + "password", ""));
            }
            default -> throw new IOException("HuskHomes database type " + type + " is not supported (SQLITE, MYSQL, MARIADB)");
        }
    }

    private static void driver(String name) {
        try { Class.forName(name); } catch (ClassNotFoundException | LinkageError missing) { /* DriverManager may still know it */ }
    }

    /**
     * Names in travel.yml are 1–32 lowercase letters, digits, underscores or hyphens: lowercases, turns other characters
     * into underscores and cuts to 32. Empty names become {@code home}.
     */
    static String key(String name) {
        String key = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (key.length() > 32) key = key.substring(0, 32);
        return key.isEmpty() ? "home" : key;
    }

    /**
     * Adds the rows that are not in {@code points} yet; existing homes and warps always win. {@code world} maps a
     * HuskHomes world (uuid text, name) to the loaded world's id, or null when it is not loaded here.
     */
    static Outcome apply(List<Row> rows, TravelPoints points, BiFunction<String, String, UUID> world) throws IOException {
        Map<UUID, Map<String, TravelPoints.Point>> homes = new HashMap<>();
        Map<String, TravelPoints.Point> warps = new HashMap<>();
        int existing = 0, noWorld = 0;
        for (Row row : rows) {
            UUID id = world.apply(row.worldUuid(), row.worldName());
            if (id == null) { noWorld++; continue; }
            String key = key(row.name());
            TravelPoints.Point point = new TravelPoints.Point(id, row.x(), row.y(), row.z(), row.yaw(), row.pitch());
            if (row.owner() == null) {
                if (points.warps().containsKey(key) || warps.putIfAbsent(key, point) != null) existing++;
            } else {
                Map<String, TravelPoints.Point> mine = homes.computeIfAbsent(row.owner(), k -> new HashMap<>());
                if (points.homes(row.owner()).containsKey(key) || mine.putIfAbsent(key, point) != null) existing++;
            }
        }
        int homeCount = homes.values().stream().mapToInt(Map::size).sum();
        if (homeCount > 0 || !warps.isEmpty()) points.addAll(homes, warps);
        return new Outcome(homeCount, warps.size(), existing, noWorld);
    }
}
