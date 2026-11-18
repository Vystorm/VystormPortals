package at.esoren.vystormportals;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The feature switches of config.yml (1.24.0 adds the ones marked new). Read from the live config on every use, so the
 * reload hook applies them at once; a missing key counts as on. Switching a feature off never deletes saved data.
 */
final class PortalSwitches {
    // new in 1.24.0
    /** Manual portals: their views, travel through them, entity travel, styles and the /vportal portal commands. */
    static final String MANUAL = "manual-portals.enabled";
    static final String TRIPS = "trips.enabled";
    /** Portal designer: frame particles, ambient sound and arrival effect, and the style menu. */
    static final String DESIGNER = "designer.enabled";
    /** Maintenance mode per portal (/vportal pause and resume). */
    static final String MAINTENANCE = "maintenance.enabled";
    static final String HOMES = "travel-gates.homes.enabled";
    static final String WARPS = "travel-gates.warps.enabled";
    static final String IMPORT = "import.enabled";
    /** Top-level boolean: use the world-links map (filled by Vystorm Worlds) instead of the vanilla world pairs. */
    static final String WORLD_LINKS = "use-world-links";
    static final String TUTORIAL = "tutorial.enabled";
    /** The one language key every switched-off command answers with. */
    static final String OFF = "feature-off";

    static final List<String> NEW = List.of(MANUAL, TRIPS, DESIGNER, MAINTENANCE, HOMES, WARPS, IMPORT, WORLD_LINKS, TUTORIAL);
    /** Switches named enabled before 1.24.0. */
    static final List<String> OLDER = List.of("end-portals.enabled", "nether-portals.enabled", "entity-travel.enabled",
            "menu.enabled", "trust.enabled", "opening-hours.enabled", "travel-gates.enabled", "travel-gates.energy.enabled");
    /** Booleans with other names, declared as switches; the first ten were declared before 1.24.0. */
    static final List<String> DECLARED = List.of("instant-travel", "end-portals.gateways", "end-portals.return-view",
            "entity-travel.objects", "entity-travel.projectiles", "entity-travel.mobs", "entity-travel.vehicles", "trust.player-menu",
            "travel-gates.short-commands", "travel-gates.warps.take-others-along",
            "nether-portals.prepare-counterpart", "end-portals.prepare-arrival-platform", "end-portals.generate-destination-chunks",
            "travel-gates.warps.permission-per-warp", WORLD_LINKS);
    /** Read only at startup: the short root commands are registered once, the tutorial too. */
    static final List<String> RESTART = List.of("travel-gates.short-commands", TUTORIAL);

    private PortalSwitches() {}

    static boolean on(ConfigurationSection config, String key) { return config == null || config.getBoolean(key, true); }

    static boolean manual(ConfigurationSection c) { return on(c, MANUAL); }
    /** The portal menu lists and edits manual portals, so it needs menu.enabled and manual-portals.enabled. */
    static boolean menu(ConfigurationSection c) { return (c == null || c.getBoolean("menu.enabled", true)) && manual(c); }
    static boolean designer(ConfigurationSection c) { return on(c, DESIGNER) && manual(c); }
    static boolean maintenance(ConfigurationSection c) { return on(c, MAINTENANCE); }

    /** Every switch, for descriptions and tests. */
    static List<String> all() {
        java.util.List<String> all = new java.util.ArrayList<>(NEW);
        all.addAll(OLDER);
        for (String key : DECLARED) if (!all.contains(key)) all.add(key);
        return all;
    }

    /** {@code trust.player-menu} -> {@code features.trust-player-menu}: the language key of a switch description. */
    static String descriptionKey(String path) {
        String subject = path.endsWith(".enabled") ? path.substring(0, path.length() - ".enabled".length()) : path;
        return "features." + subject.replace('.', '-');
    }
}
