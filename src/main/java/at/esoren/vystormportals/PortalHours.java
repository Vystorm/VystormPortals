package at.esoren.vystormportals;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Timed portals: optional opening hours per portal (days of the week and one daily time window, which may run past
 * midnight). A portal without hours is always open. Stored in {@code hours.yml} next to {@code portals.yml} so the
 * portal file format stays unchanged. Server thread.
 */
final class PortalHours {
    /**
     * Opening hours: {@code days} bit 0 = Monday … bit 6 = Sunday, {@code open}/{@code close} minute of the day. A window
     * with {@code close <= open} runs past midnight (into the next day); {@code open == close} means the whole day.
     */
    record Schedule(int days, int open, int close) {
        static final Schedule DEFAULT = new Schedule(0b1111111, 8 * 60, 22 * 60);
        Schedule {
            days &= 0b1111111;
            open = Math.floorMod(open, 1440);
            close = Math.floorMod(close, 1440);
        }

        boolean day(LocalDate date) { return (days >> (date.getDayOfWeek().getValue() - 1) & 1) == 1; }

        boolean openAt(LocalDateTime time) {
            int minute = time.getHour() * 60 + time.getMinute();
            LocalDate date = time.toLocalDate();
            if (open == close) return day(date);
            if (open < close) return day(date) && minute >= open && minute < close;
            return (day(date) && minute >= open) || (day(date.minusDays(1)) && minute < close);
        }

        /** Time until the open state changes, or empty if it never does (always or never open). */
        Optional<Duration> untilChange(LocalDateTime now) {
            boolean state = openAt(now);
            LocalDateTime base = now.withSecond(0).withNano(0);
            List<LocalDateTime> edges = new ArrayList<>();
            for (int d = 0; d <= 8; d++) {
                LocalDate date = base.toLocalDate().plusDays(d);
                edges.add(date.atStartOfDay().plusMinutes(open));
                edges.add(date.atStartOfDay().plusMinutes(close));
                edges.add(date.atStartOfDay());
            }
            return edges.stream().filter(edge -> edge.isAfter(now)).sorted()
                    .filter(edge -> openAt(edge) != state).findFirst().map(edge -> Duration.between(now, edge));
        }

        Schedule toggleDay(int index) { return new Schedule(days ^ (1 << index), open, close); }
        Schedule open(int minute) { return new Schedule(days, minute, close); }
        Schedule close(int minute) { return new Schedule(days, open, minute); }
    }

    static String clock(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }

    static int parseClock(String text, int fallback) {
        try {
            String[] parts = text.strip().split(":");
            int hour = Integer.parseInt(parts[0]), minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 ? hour * 60 + minute : fallback;
        } catch (RuntimeException invalid) { return fallback; }
    }

    /** Short duration for the action bar: "2 d 3 h", "4 h 05 min", "12 min". */
    static String duration(Duration d) {
        long minutes = Math.max(1, (d.getSeconds() + 59) / 60);
        if (minutes >= 1440) return minutes / 1440 + " d " + minutes % 1440 / 60 + " h";
        if (minutes >= 60) return minutes / 60 + " h " + String.format(Locale.ROOT, "%02d", minutes % 60) + " min";
        return minutes + " min";
    }

    private final Path file;
    private final Map<String, Schedule> hours = new TreeMap<>();

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    PortalHours(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        hours.putAll(load(file));
    }

    Optional<Schedule> of(String portal) { return Optional.ofNullable(hours.get(portal)); }

    boolean openAt(String portal, LocalDateTime time) {
        Schedule schedule = hours.get(portal);
        return schedule == null || schedule.openAt(time);
    }

    void set(String portal, Schedule schedule) throws IOException {
        if (schedule == null) hours.remove(portal); else hours.put(portal, schedule);
        write(files, file, hours);
    }

    void retain(Set<String> portals) {
        if (hours.keySet().retainAll(portals)) try { write(files, file, hours); } catch (IOException ignored) { /* retried on the next change */ }
    }

    static Map<String, Schedule> load(Path file) {
        Map<String, Schedule> out = new TreeMap<>();
        if (file == null || !Files.exists(file)) return out;
        ConfigurationSection all = YamlCheck.loadData(file, java.util.logging.Logger.getLogger("VystormPortals")).getConfigurationSection("hours");
        if (all != null) for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null || !id.matches("[a-z0-9_-]{1,40}")) continue;
            String days = s.getString("days", "1111111");
            int mask = 0;
            for (int i = 0; i < Math.min(7, days.length()); i++) if (days.charAt(i) == '1') mask |= 1 << i;
            out.put(id, new Schedule(mask, parseClock(s.getString("open", "08:00"), 480), parseClock(s.getString("close", "22:00"), 1320)));
        }
        return out;
    }

    static void write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files, Path file, Map<String, Schedule> hours) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Portal opening hours (portal menu -> Opening hours). days: Monday..Sunday, 1 = open.",
                "A window whose close time is not after its open time runs past midnight; open = close means the whole day."));
        yaml.createSection("hours");
        hours.forEach((id, s) -> {
            StringBuilder days = new StringBuilder();
            for (int i = 0; i < 7; i++) days.append((s.days() >> i & 1) == 1 ? '1' : '0');
            yaml.set("hours." + id + ".days", days.toString());
            yaml.set("hours." + id + ".open", clock(s.open()));
            yaml.set("hours." + id + ".close", clock(s.close()));
        });
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }
}
