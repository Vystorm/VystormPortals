package at.esoren.vystormportals;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core.visual.ParticlePaths;
import Vystorm.vystorm_core.visual.Point3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Portal designer: per portal a frame particle, an ambient sound and an arrival effect, chosen from short curated
 * lists in the portal menu. Stored in {@code styles.yml} next to {@code portals.yml} so the portal file format stays
 * unchanged. Unknown names (an older server without that particle) fall back to "none". Server thread.
 */
final class PortalStyles {
    /** Choices in menu order; the first entry is the default. */
    static final List<String> PARTICLES = List.of("none", "end_rod", "portal", "enchant", "soul_fire_flame", "glow", "cherry_leaves", "snowflake", "flame", "dragon_breath", "happy_villager");
    static final List<String> SOUNDS = List.of("none", "portal", "beacon", "amethyst", "conduit", "bell");
    static final List<String> ARRIVALS = List.of("none", "flash", "swirl", "sparkle");

    record Style(String particle, String sound, String arrival) {
        static final Style NONE = new Style("none", "none", "none");
        Style {
            particle = pick(PARTICLES, particle);
            sound = pick(SOUNDS, sound);
            arrival = pick(ARRIVALS, arrival);
        }
        boolean plain() { return equals(NONE); }
    }

    /** {@code value} if it is in the list (ignoring case), else the list's first entry. */
    static String pick(List<String> choices, String value) {
        if (value != null) for (String c : choices) if (c.equalsIgnoreCase(value.strip())) return c;
        return choices.getFirst();
    }

    /** The next (or previous) entry of a cycle. */
    static String cycle(List<String> choices, String current, boolean back) {
        int i = Math.max(0, choices.indexOf(pick(choices, current)));
        return choices.get(Math.floorMod(i + (back ? -1 : 1), choices.size()));
    }

    private final Path file;
    private final Map<String, Style> styles = new TreeMap<>();

    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** @param files Core's file part, {@code api.data().files()} */
    PortalStyles(Path file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.files = files;
        this.file = file;
        styles.putAll(load(file));
    }

    Style of(String portal) { return styles.getOrDefault(portal, Style.NONE); }

    void set(String portal, Style style) throws IOException {
        if (style.plain()) styles.remove(portal); else styles.put(portal, style);
        write(files, file, styles);
    }

    /** Drops styles of portals that no longer exist; saves only when something changed. */
    void retain(Set<String> portals) {
        if (styles.keySet().retainAll(portals)) try { write(files, file, styles); } catch (IOException ignored) { /* retried on the next change */ }
    }

    static Map<String, Style> load(Path file) {
        Map<String, Style> out = new TreeMap<>();
        if (file == null || !Files.exists(file)) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection all = yaml.getConfigurationSection("styles");
        if (all != null) for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null || !id.matches("[a-z0-9_-]{1,40}")) continue;
            Style style = new Style(s.getString("particle"), s.getString("sound"), s.getString("arrival"));
            if (!style.plain()) out.put(id, style);
        }
        return out;
    }

    static void write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files, Path file, Map<String, Style> styles) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Portal styles (portal menu -> Style). Written by VystormPortals."));
        yaml.createSection("styles");
        styles.forEach((id, s) -> {
            yaml.set("styles." + id + ".particle", s.particle());
            yaml.set("styles." + id + ".sound", s.sound());
            yaml.set("styles." + id + ".arrival", s.arrival());
        });
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }

    // ---- effects ----

    static Particle particle(String name) {
        if (name == null || name.equals("none")) return null;
        try { return Particle.valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException missing) { return null; }
    }

    /** Minecraft sound key of a choice, or null for none. Keys work from 1.20.6 to 26.x. */
    static String sound(String name) {
        return switch (name == null ? "" : name) {
            case "portal" -> "minecraft:block.portal.ambient";
            case "beacon" -> "minecraft:block.beacon.ambient";
            case "amethyst" -> "minecraft:block.amethyst_block.chime";
            case "conduit" -> "minecraft:block.conduit.ambient";
            case "bell" -> "minecraft:block.bell.resonate";
            default -> null;
        };
    }

    /** A few particles along the frame, seen by everyone nearby. */
    static void ambient(Vystorm.vystorm_core.visual.ParticlePathsInterface paths, World world, Portal portal, Style style) {
        Particle particle = particle(style.particle());
        if (particle == null || particle.getDataType() != Void.class) return;
        for (Point3 p : paths.outline(portal.frame(), 4)) world.spawnParticle(particle, p.x(), p.y(), p.z(), 1, 0.05, 0.05, 0.05, 0.0);
    }

    static void ambientSound(World world, Portal portal, Style style) {
        String sound = sound(style.sound());
        if (sound == null) return;
        Point3 o = portal.frame().origin();
        world.playSound(new Location(world, o.x(), o.y() + portal.frame().height() / 2, o.z()), sound, 0.35f, 1.0f);
    }

    /** The arrival effect at {@code at}; {@code only} limits it to one viewer (menu preview). */
    static void arrival(Location at, Style style, Player only) {
        World world = at.getWorld();
        if (world == null) return;
        Location c = at.clone().add(0, 1, 0);
        switch (style.arrival()) {
            case "flash" -> { spawn(world, only, Particle.END_ROD, c, 30, 0.3, 0.6, 0.3, 0.08); play(world, only, c, "minecraft:entity.enderman.teleport", 0.5f, 1.4f); }
            case "swirl" -> { for (int i = 0; i < 24; i++) { double a = i * Math.PI / 6; spawn(world, only, Particle.REVERSE_PORTAL,
                    c.clone().add(Math.cos(a) * 0.8, i * 0.08 - 0.9, Math.sin(a) * 0.8), 1, 0, 0, 0, 0); } play(world, only, c, "minecraft:block.portal.travel", 0.15f, 1.8f); }
            case "sparkle" -> { spawn(world, only, Particle.FIREWORK, c, 25, 0.4, 0.6, 0.4, 0.05); play(world, only, c, "minecraft:entity.firework_rocket.twinkle", 0.5f, 1.2f); }
            default -> { }
        }
    }

    private static void spawn(World world, Player only, Particle particle, Location at, int count, double dx, double dy, double dz, double speed) {
        try {
            if (only != null) only.spawnParticle(particle, at, count, dx, dy, dz, speed);
            else world.spawnParticle(particle, at, count, dx, dy, dz, speed);
        } catch (RuntimeException unsupported) { /* particle needs data on this server version */ }
    }

    private static void play(World world, Player only, Location at, String sound, float volume, float pitch) {
        if (only != null) only.playSound(at, sound, volume, pitch); else world.playSound(at, sound, volume, pitch);
    }
}
