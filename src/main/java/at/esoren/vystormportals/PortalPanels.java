package at.esoren.vystormportals;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.*;
import java.io.IOException;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Loaded only after the Core capability guard. All state and handlers run on the server thread. */
final class PortalPanels implements PortalPanel.Actions, TrustPanel.Actions {
    private final PortalsPlugin plugin;
    private final Lang lang;
    private final PortalMenu chest;
    private final Panels panels;
    private final Map<UUID, String> selected = new HashMap<>(), targets = new HashMap<>(), trustSelected = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>(), targetPages = new HashMap<>(), trustPages = new HashMap<>();
    private final Map<UUID, UUID> trustTargets = new HashMap<>();

    PortalPanels(PortalsPlugin plugin, Lang lang, PortalMenu chest) {
        this.plugin = plugin; this.lang = lang; this.chest = chest; this.panels = plugin.core().gui().panels().of(plugin);
    }
    void register(boolean menuEnabled) {
        if (menuEnabled) panels.register(PortalPanel.NAME, PanelSpec.builder(lang.ref("menu.entry"))
                .access(this::access).render(ctx -> PortalPanel.panel(text(ctx.player()), overview(ctx.player()), this))
                .menu(MenuCategory.WORLD, Material.ENDER_EYE, lang.ref("menu.entry-desc"))
                .fallback(chest::open).build());
        if (plugin.trust().enabled() && plugin.trust().playerMenu()) panels.register(TrustPanel.NAME,
                PanelSpec.builder(lang.ref("panel.trust-title")).access(p -> trustTarget(p) != null)
                        .render(ctx -> TrustPanel.panel(text(ctx.player()), trustOverview(ctx.player()), this))
                        .fallback(p -> { Player target = trustTarget(p); if (target != null) TrustMenuEntries.picker(plugin, lang, p, target); })
                        .build());
    }
    boolean open(Player player) { return panels.open(player, PortalPanel.NAME) != PanelOpenResult.NONE; }
    boolean openTrust(Player viewer, Player target) {
        trustTargets.put(viewer.getUniqueId(), target.getUniqueId());
        trustSelected.remove(viewer.getUniqueId()); trustPages.remove(viewer.getUniqueId());
        return panels.open(viewer, TrustPanel.NAME) != PanelOpenResult.NONE;
    }
    void forget(UUID id) {
        selected.remove(id); targets.remove(id); pages.remove(id); targetPages.remove(id);
        trustSelected.remove(id); trustPages.remove(id); trustTargets.remove(id);
        trustTargets.entrySet().removeIf(e -> e.getValue().equals(id));
    }
    void close() { plugin.core().gui().panels().release(plugin); }
    private PortalPanel.Text text(Player player) { return (key, args) -> lang.plain(player, key, args); }
    private Result denied(Player player) { return Result.error(lang.plain(player, "denied")); }
    boolean access(Player player) {
        return plugin.isEnabled() && player.isOnline() && PortalSwitches.menu(plugin.getConfig())
                && player.hasPermission("vystormportals.use") && player.hasPermission("vystormportals.menu");
    }
    private List<Portal> visible(Player p) { return PortalMenu.ordered(plugin.portals().values(), p.getUniqueId(), portal -> plugin.allowed(p, portal)); }
    private List<Portal> linkTargets(Player p, String id) {
        return PortalMenu.ordered(plugin.portals().values(), p.getUniqueId(), portal -> !portal.id().equals(id) && plugin.canEdit(p, portal));
    }
    private PortalPanel.Row row(Player player, Portal portal) {
        var world = plugin.getServer().getWorld(portal.frame().world());
        return new PortalPanel.Row(portal, world == null ? portal.frame().world().toString() : world.getName(),
                plugin.playerName(portal.owner()), plugin.canEdit(player, portal),
                plugin.styles() == null || !PortalSwitches.designer(plugin.getConfig()) ? null : plugin.styles().of(portal.id()),
                plugin.hours() == null ? null : plugin.hours().of(portal.id()).orElse(null));
    }
    PortalPanel.Overview overview(Player player) {
        UUID viewer = player.getUniqueId(); List<Portal> all = visible(player);
        int page = PortalPanel.page(pages.getOrDefault(viewer, 0), all.size());
        List<PortalPanel.Row> rows = PortalPanel.slice(all, page).stream().map(p -> row(player, p)).toList();
        PortalPanel.Row chosen = rows.stream().filter(r -> r.portal().id().equals(selected.get(viewer))).findFirst().orElse(rows.isEmpty() ? null : rows.getFirst());
        List<Portal> links = chosen == null || !chosen.editable() ? List.of() : linkTargets(player, chosen.portal().id());
        int linkPage = PortalPanel.page(targetPages.getOrDefault(viewer, 0), links.size());
        List<PortalPanel.Row> linkRows = PortalPanel.slice(links, linkPage).stream().map(p -> row(player, p)).toList();
        String target = linkRows.stream().map(r -> r.portal().id()).filter(id -> id.equals(targets.get(viewer)))
                .findFirst().orElse(linkRows.isEmpty() ? null : linkRows.getFirst().portal().id());
        return new PortalPanel.Overview(rows, chosen, page, PortalPanel.pages(all.size()),
                player.hasPermission("vystormportals.create") || player.hasPermission("vystormportals.admin"), linkRows, target, linkPage, PortalPanel.pages(links.size()));
    }
    @Override public Result choose(Player player, String scope, String value) {
        if (!access(player)) return denied(player);
        UUID viewer = player.getUniqueId();
        switch (scope) {
            case "portal" -> {
                Portal p = plugin.portals().get(value);
                if (p == null || !plugin.allowed(player, p)) return denied(player);
                selected.put(viewer, value); targets.remove(viewer); targetPages.remove(viewer);
            }
            case "page" -> { pages.put(viewer, PortalPanel.page(Integer.parseInt(value), visible(player).size())); selected.remove(viewer); targets.remove(viewer); targetPages.remove(viewer); }
            case "target", "target-page" -> {
                var current = overview(player).selected();
                if (current == null || !current.editable()) return denied(player);
                List<Portal> choices = linkTargets(player, current.portal().id());
                if (scope.equals("target")) {
                    if (choices.stream().noneMatch(p -> p.id().equals(value))) return denied(player);
                    targets.put(viewer, value);
                } else { targetPages.put(viewer, PortalPanel.page(Integer.parseInt(value), choices.size())); targets.remove(viewer); }
            }
            default -> { return denied(player); }
        }
        return Result.ok();
    }
    @Override public Result change(Player player, String id, String setting, Object value) {
        Portal p = plugin.portals().get(id);
        if (!access(player) || p == null || !plugin.allowed(player, p) || !plugin.canEdit(player, p)) return denied(player);
        try {
            switch (setting) {
                case "access" -> { return command(player, "access", id, Boolean.TRUE.equals(value) ? "public" : "private"); }
                case "paused" -> { return command(player, Boolean.TRUE.equals(value) ? "pause" : "resume", id); }
                case "warmup" -> {
                    int ticks = integer(value, 0, 200);
                    return command(player, "warmup", id, Integer.toString(ticks));
                }
                case "delete", "unlink" -> { return command(player, setting, id); }
                case "link" -> {
                    Portal target = plugin.portals().get(value.toString());
                    if (target == null || id.equals(target.id()) || !plugin.canEdit(player, target)) return denied(player);
                    return command(player, "link", id, target.id());
                }
                case "particle", "sound", "arrival" -> {
                    if (plugin.styles() == null) return denied(player);
                    var s = plugin.styles().of(id); String choice = value.toString();
                    List<String> choices = setting.equals("particle") ? PortalStyles.PARTICLES : setting.equals("sound") ? PortalStyles.SOUNDS : PortalStyles.ARRIVALS;
                    if (!choices.contains(choice)) return denied(player);
                    if (!PortalSwitches.designer(plugin.getConfig())) return Result.error(lang.plain(player, PortalSwitches.OFF));
                    plugin.styles().set(id, new PortalStyles.Style(setting.equals("particle") ? choice : s.particle(),
                            setting.equals("sound") ? choice : s.sound(), setting.equals("arrival") ? choice : s.arrival()));
                }
                default -> {
                    if (plugin.hours() == null) return denied(player);
                    PortalHours.Schedule s = plugin.hours().of(id).orElse(PortalHours.Schedule.DEFAULT);
                    if (setting.equals("always")) s = Boolean.TRUE.equals(value) ? null : s;
                    else if (setting.equals("time-open")) s = s.open(integer(value, 0, 1439));
                    else if (setting.equals("time-close")) s = s.close(integer(value, 0, 1439));
                    else if (setting.matches("day-[0-6]")) {
                        int bit = 1 << Integer.parseInt(setting.substring(4));
                        s = new PortalHours.Schedule(Boolean.TRUE.equals(value) ? s.days() | bit : s.days() & ~bit, s.open(), s.close());
                    } else return denied(player);
                    plugin.hours().set(id, s);
                }
            }
            return Result.ok();
        } catch (IOException failure) {
            plugin.getLogger().warning("Panel settings not saved: " + failure);
            return Result.error(lang.plain(player, "failed"));
        } catch (IllegalArgumentException invalid) { return denied(player); }
    }
    /** Existing commands report their specific errors in chat; only a successful commit replaces the map. */
    private Result command(Player player, String... args) {
        Map<String, Portal> before = plugin.portals();
        plugin.run(player, args);
        return plugin.portals() != before ? Result.ok() : Result.error(lang.plain(player, "panel.change-failed"));
    }
    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max) throw new IllegalArgumentException();
        return n.intValue();
    }
    @Override public void leave(Player player, String id, String action) {
        if (!access(player)) return;
        if (action.equals("create")) {
            if (player.hasPermission("vystormportals.create") || player.hasPermission("vystormportals.admin")) chest.create(player);
            return;
        }
        Portal p = plugin.portals().get(id);
        if (p == null || !plugin.allowed(player, p)) return;
        if (action.equals("locate")) chest.locate(player, p);
        if (action.equals("preview") && plugin.canEdit(player, p) && plugin.styles() != null) chest.preview(player, p, plugin.styles().of(id));
    }
    private Player trustTarget(Player player) {
        UUID id = trustTargets.get(player.getUniqueId());
        Player target = id == null ? null : plugin.getServer().getPlayer(id);
        return plugin.isEnabled() && player.isOnline() && plugin.trust().playerMenu() && target != null && target.isOnline()
                && TrustMenuEntries.visible(plugin, player, target) ? target : null;
    }
    private List<Portal> owned(Player player) {
        return plugin.portals().values().stream().filter(p -> p.owner().equals(player.getUniqueId())).sorted(Comparator.comparing(Portal::id)).toList();
    }
    TrustPanel.Overview trustOverview(Player viewer) {
        Player target = trustTarget(viewer);
        if (target == null) return new TrustPanel.Overview("", List.of(), null, 0, 1);
        List<Portal> all = owned(viewer); UUID id = viewer.getUniqueId();
        int page = PortalPanel.page(trustPages.getOrDefault(id, 0), all.size());
        List<TrustPanel.Row> rows = PortalPanel.slice(all, page).stream()
                .map(p -> new TrustPanel.Row(p.id(), p.trusted().contains(target.getUniqueId()), p.publicAccess())).toList();
        TrustPanel.Row chosen = rows.stream().filter(r -> r.id().equals(trustSelected.get(id))).findFirst().orElse(rows.isEmpty() ? null : rows.getFirst());
        return new TrustPanel.Overview(target.getName(), rows, chosen, page, PortalPanel.pages(all.size()));
    }
    @Override public Result choose(Player player, String id) {
        if (trustTarget(player) == null || owned(player).stream().noneMatch(p -> p.id().equals(id))) return denied(player);
        trustSelected.put(player.getUniqueId(), id); return Result.ok();
    }
    @Override public Result page(Player player, int page) {
        if (trustTarget(player) == null) return denied(player);
        trustPages.put(player.getUniqueId(), PortalPanel.page(page, owned(player).size())); trustSelected.remove(player.getUniqueId());
        return Result.ok();
    }
    @Override public Result trust(Player player, String id, boolean value) {
        Player target = trustTarget(player); Portal p = plugin.portals().get(id);
        if (target == null || p == null || !p.owner().equals(player.getUniqueId()) || !plugin.canEdit(player, p)) return denied(player);
        if (p.trusted().contains(target.getUniqueId()) == value) return Result.ok();
        plugin.run(player, value ? "trust" : "untrust", id, target.getName());
        Portal updated = plugin.portals().get(id);
        return updated != null && updated.trusted().contains(target.getUniqueId()) == value ? Result.ok() : Result.error(lang.plain(player, "panel.change-failed"));
    }
}
