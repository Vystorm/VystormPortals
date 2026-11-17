package at.esoren.vystormportals;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Optional portal energy for travel gates (1.21.0): every player has up to {@code max} energy that recharges over time
 * (also while offline); opening a gate costs {@code base + per-100-blocks * distance / 100}, a gate to another world
 * {@code base + other-world}, never more than {@code max} (so a full bar always opens a gate). Stored in
 * {@code energy.yml} as the value at a point in time. Server thread.
 */
final class PortalEnergy {
    /** energy.* settings. */
    record Rules(boolean enabled, double max, double perMinute, double base, double per100Blocks, double otherWorld) {
        static Rules read(ConfigurationSection c) {
            double max = Math.max(1, c.getDouble("travel-gates.energy.max", 100));
            return new Rules(c.getBoolean("travel-gates.energy.enabled", false), max,
                    Math.max(0, c.getDouble("travel-gates.energy.recharge-per-minute", 5)),
                    Math.max(0, c.getDouble("travel-gates.energy.base-cost", 5)),
                    Math.max(0, c.getDouble("travel-gates.energy.cost-per-100-blocks", 1)),
                    Math.max(0, c.getDouble("travel-gates.energy.other-world-cost", 25)));
        }

        /** Cost of a gate over {@code distance} blocks; negative distance = other world. Rounded up, at most max. */
        double cost(double distance) {
            double raw = base + (distance < 0 ? otherWorld : per100Blocks * distance / 100.0);
            return Math.min(max, Math.ceil(raw));
        }

        /** Energy after {@code millis} of recharging from {@code value}. */
        double recharge(double value, long millis) {
            return Math.min(max, value + perMinute * Math.max(0, millis) / 60000.0);
        }

        /** Whole seconds until {@code value} reaches {@code needed}; 0 when it already has. -1 = never (no recharge). */
        long secondsUntil(double value, double needed) {
            if (value >= needed) return 0;
            if (perMinute <= 0) return -1;
            return (long) Math.ceil((needed - value) / perMinute * 60);
        }
    }

    private record Saved(double value, long at) {}

    private final Path file;
    private final Map<UUID, Saved> saved = new HashMap<>();
    private Rules rules;

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    PortalEnergy(Path file, Rules rules, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        this.rules = rules;
        if (Files.isRegularFile(file)) {
            ConfigurationSection all = YamlConfiguration.loadConfiguration(file.toFile()).getConfigurationSection("energy");
            if (all != null) for (String key : all.getKeys(false)) {
                try {
                    saved.put(UUID.fromString(key), new Saved(all.getDouble(key + ".value"), all.getLong(key + ".at")));
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    Rules rules() { return rules; }
    void rules(Rules next) { rules = next; }

    /** Current energy of a player (new players start full). */
    double current(UUID player, long now) {
        Saved s = saved.get(player);
        return s == null ? rules.max() : rules.recharge(s.value(), now - s.at());
    }

    /** Takes {@code amount} if the player has it; false (and nothing taken) otherwise. */
    boolean take(UUID player, double amount, long now) throws IOException {
        double value = current(player, now);
        if (value + 1e-9 < amount) return false;
        saved.put(player, new Saved(value - amount, now));
        save();
        return true;
    }

    /** Gives back {@code amount} (a gate nobody used). */
    void refund(UUID player, double amount, long now) throws IOException {
        saved.put(player, new Saved(Math.min(rules.max(), current(player, now) + amount), now));
        save();
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        saved.forEach((id, s) -> { yaml.set("energy." + id + ".value", s.value()); yaml.set("energy." + id + ".at", s.at()); });
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }
}
