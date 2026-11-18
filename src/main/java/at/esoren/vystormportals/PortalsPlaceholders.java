package at.esoren.vystormportals;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import Vystorm.vystorm_core.lang.Lang;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The {@code portals} placeholders (1.24.0, Vystorm Core 0.65.0): {@code %vystorm_portals_energy%},
 * {@code {portals:portal_open:market}} and so on. Plain values; a value whose feature is switched off is empty. Energy,
 * homes and opening hours live in server-thread maps; only {@code portals_total} reads the immutable portal map and
 * also answers off the server thread.
 */
final class PortalsPlaceholders {
    static final String NAMESPACE = "portals";
    static final List<String> KEYS = List.of("energy", "energy_max", "energy_percent", "energy_full_in", "energy_enabled",
            "homes_count", "homes_max", "warps_count", "portals_owned", "portals_total", "portal_open");

    /** What the values read, looked up at call time. */
    interface Source {
        boolean gatesOn();
        boolean energyOn();
        boolean homesOn();
        boolean warpsOn();
        boolean manualOn();
        /** Current energy, or NaN when unknown. */
        double energy(UUID player);
        double energyMax();
        /** Seconds until the bar is full. */
        long secondsUntilFull(UUID player);
        int homes(UUID player);
        int maxHomes(Player player);
        int warps();
        Map<String, Portal> portals();
        /** Not paused and inside its opening hours. */
        boolean open(Portal portal);
    }

    private PortalsPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface placeholders, Plugin owner, Lang lang, Source s) {
        PlaceholderNamespace ns = placeholders.register(owner, NAMESPACE);
        add(ns, lang, PlaceholderValue.viewer("energy", p -> !energy(s) ? "" : (long) Math.floor(s.energy(p.getUniqueId()))));
        add(ns, lang, PlaceholderValue.global("energy_max", () -> !energy(s) ? "" : (long) s.energyMax()));
        add(ns, lang, PlaceholderValue.viewer("energy_percent", p -> !energy(s) ? "" : Math.round(s.energy(p.getUniqueId()) * 100 / Math.max(1, s.energyMax()))));
        add(ns, lang, PlaceholderValue.viewer("energy_full_in", p -> {
            if (!energy(s)) return "";
            long seconds = s.secondsUntilFull(p.getUniqueId());
            return seconds <= 0 ? "" : TravelGates.time(seconds);
        }));
        add(ns, lang, PlaceholderValue.global("energy_enabled", () -> energy(s)));
        add(ns, lang, PlaceholderValue.viewer("homes_count", p -> !s.gatesOn() || !s.homesOn() ? "" : s.homes(p.getUniqueId())));
        add(ns, lang, PlaceholderValue.player("homes_max", p -> !s.gatesOn() || !s.homesOn() ? "" : s.maxHomes(p)));
        add(ns, lang, PlaceholderValue.global("warps_count", () -> !s.gatesOn() || !s.warpsOn() ? "" : s.warps()));
        add(ns, lang, PlaceholderValue.viewer("portals_owned", p -> !s.manualOn() ? "" : s.portals().values().stream().filter(portal -> portal.owner().equals(p.getUniqueId())).count()));
        add(ns, lang, PlaceholderValue.global("portals_total", () -> !s.manualOn() ? "" : s.portals().size()).asyncSafe());
        add(ns, lang, PlaceholderValue.of("portal_open", context -> {
            String id = context.rest(0);
            if (id == null || id.isBlank()) return null;
            if (!s.manualOn()) return "";
            Portal portal = s.portals().get(id.toLowerCase(Locale.ROOT));
            return portal == null ? null : s.open(portal);
        }).arguments("<portal>"));
        return ns;
    }

    private static boolean energy(Source s) { return s.gatesOn() && s.energyOn(); }

    private static void add(PlaceholderNamespace ns, Lang lang, PlaceholderValue value) {
        ns.add(lang == null ? value : value.description(lang.ref("placeholder." + value.key())));
    }
}
