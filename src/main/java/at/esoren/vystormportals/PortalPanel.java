package at.esoren.vystormportals;

import Vystorm.vystorm_core.panel.*;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pure panel rendering. Services and per-player navigation live in PortalPanels. */
final class PortalPanel {
    static final String NAME = "portals";
    static final int PAGE_SIZE = 9;

    @FunctionalInterface interface Text { String get(String key, Object... args); }
    interface Actions {
        Result choose(Player player, String scope, String value);
        Result change(Player player, String portal, String setting, Object value);
        void leave(Player player, String portal, String action);
    }
    record Row(Portal portal, String world, String owner, boolean editable,
               PortalStyles.Style style, PortalHours.Schedule hours) {}
    record Overview(List<Row> rows, Row selected, int page, int pages, boolean create,
                    List<Row> targets, String target, int targetPage, int targetPages) {}

    static int pages(int count) { return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE); }
    static int page(int wanted, int count) { return Math.clamp(wanted, 0, pages(count) - 1); }
    static <T> List<T> slice(List<T> rows, int page) {
        int start = page(page, rows.size()) * PAGE_SIZE;
        return rows.subList(start, Math.min(rows.size(), start + PAGE_SIZE));
    }

    static Panel panel(Text t, Overview o, Actions actions) {
        Ui.ListView list = Ui.list("portals").empty(t.get("menu.empty"))
                .items(o.rows(), r -> Ui.listItem(r.portal().id(), r.portal().id())
                        .icon(Ui.item(icon(r.portal())))
                        .subtitle(r.world()).right(t.get(r.portal().paused() ? "menu.paused" : "menu.open")))
                .selected(o.selected() == null ? null : o.selected().portal().id())
                .onSelect((a, id) -> refresh(a, actions.choose(a.player(), "portal", id))).grow(2);
        Ui.Box detail = Ui.col().grow(3);
        if (o.selected() != null) detail.child(detail(t, o, actions));
        else detail.child(Ui.text(t.get("menu.empty")));
        Panel panel = Panel.page(t.get("menu.entry")).subtitle(t.get("menu.title", "page", o.page() + 1, "pages", o.pages()))
                .icon(Ui.item(Material.ENDER_EYE)).size(Panel.Size.LARGE).child(Ui.row().gap(8).children(list, detail));
        if (o.page() > 0) panel.footer(pageButton(t, actions, "previous", "page", o.page() - 1));
        if (o.page() + 1 < o.pages()) panel.footer(pageButton(t, actions, "next", "page", o.page() + 1));
        if (o.create()) panel.footer(Ui.button("create").label(t.get("menu.create")).icon(Ui.item(Material.NETHER_STAR)).primary()
                .onClick(a -> { a.closeAndThen(p -> actions.leave(p, "", "create")); return Result.ok(); }));
        return panel.footer(Ui.button("close").label(t.get("menu.close")).ghost()
                .onClick(a -> { a.closeAndThen(p -> {}); return Result.ok(); }));
    }

    private static Ui.Tabs detail(Text t, Overview o, Actions actions) {
        Row r = o.selected(); Portal p = r.portal(); String id = p.id();
        Object[] info = PortalsPlugin.info(p, r.world(), r.owner());
        Ui.Box summary = Ui.col().gap(6).children(Ui.heading(id),
                Ui.badge(t.get(p.paused() ? "menu.paused" : "menu.open")),
                Ui.kv().id("details")
                        .row(t.get("panel.world"), r.world())
                        .row(t.get("panel.position"), info[2])
                        .row(t.get("panel.size"), info[3])
                        .row(t.get("panel.owner"), r.owner())
                        .row(t.get("menu.link"), info[6])
                        .row(t.get("panel.access"), t.get(p.publicAccess() ? "menu.public" : "menu.private")),
                Ui.button("locate").label(t.get("menu.locate")).icon(Ui.item(Material.COMPASS))
                        .onClick(a -> { a.closeAndThen(v -> actions.leave(v, id, "locate")); return Result.ok(); }));
        Ui.Tabs tabs = Ui.tabs("portal-tabs").tab("details", t.get("panel.details"), summary);
        if (!r.editable()) return tabs;
        summary.children(
                Ui.toggle("public", t.get("menu.access-public")).value(p.publicAccess())
                        .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "access", value))),
                Ui.toggle("paused", t.get("menu.pause")).value(p.paused())
                        .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "paused", value))),
                Ui.number("warmup", t.get("menu.warmup", "ticks", p.warmupTicks(), "seconds", p.warmupTicks() / 20.0))
                        .range(0, 200, 10).value(p.warmupTicks()).decimals(0)
                        .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "warmup", value))),
                Ui.button("delete").label(t.get("menu.delete")).icon(Ui.item(Material.LAVA_BUCKET)).danger()
                        .confirm(t.get("menu.delete"), t.get("panel.delete-confirm", "name", id))
                        .onClick(a -> refresh(a, actions.change(a.player(), id, "delete", ""))));

        Ui.Box links = Ui.col().gap(6).child(Ui.list("targets").empty(t.get("menu.empty"))
                .items(o.targets(), target -> Ui.listItem(target.portal().id(), target.portal().id()).icon(Ui.item(icon(target.portal())))
                        .subtitle(target.world()))
                .selected(o.target()).onSelect((a, target) -> refresh(a, actions.choose(a.player(), "target", target))));
        Ui.Box navigation = Ui.row().gap(6);
        if (o.targetPage() > 0) navigation.child(pageButton(t, actions, "previous", "target-page", o.targetPage() - 1));
        if (o.targetPage() + 1 < o.targetPages()) navigation.child(pageButton(t, actions, "next", "target-page", o.targetPage() + 1));
        links.children(Ui.text(t.get("menu.link-title", "page", o.targetPage() + 1, "pages", o.targetPages())), navigation);
        if (o.target() != null) links.child(Ui.button("link").label(t.get("menu.link")).icon(Ui.item(Material.LEAD)).primary()
                .confirm(t.get("menu.link"), t.get("panel.link-confirm", "name", id, "target", o.target()))
                .onClick(a -> refresh(a, actions.change(a.player(), id, "link", o.target()))));
        if (p.target() != null) links.child(Ui.button("unlink").label(t.get("menu.unlink")).icon(Ui.item(Material.SHEARS))
                .confirm(t.get("menu.unlink"), t.get("panel.unlink-confirm", "name", id))
                .onClick(a -> refresh(a, actions.change(a.player(), id, "unlink", ""))));
        tabs.tab("links", t.get("menu.link"), links);
        if (r.style() != null) {
            PortalStyles.Style s = r.style();
            tabs.tab("style", t.get("menu.style"), Ui.col().gap(6).children(
                    style(t, actions, id, "particle", s.particle(), PortalStyles.PARTICLES),
                    style(t, actions, id, "sound", s.sound(), PortalStyles.SOUNDS),
                    style(t, actions, id, "arrival", s.arrival(), PortalStyles.ARRIVALS),
                    Ui.button("preview").label(t.get("style.preview")).icon(Ui.item(Material.SPYGLASS))
                            .onClick(a -> { a.closeAndThen(v -> actions.leave(v, id, "preview")); return Result.ok(); })));
        }
        PortalHours.Schedule s = r.hours() == null ? PortalHours.Schedule.DEFAULT : r.hours();
        Ui.Box hours = Ui.col().gap(6).child(Ui.toggle("always", t.get("hours.always")).value(r.hours() == null)
                .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "always", value))));
        if (r.hours() != null) {
            for (int day = 0; day < 7; day++) {
                int d = day;
                hours.child(Ui.toggle("day-" + day, t.get("hours.day." + (day + 1))).value((s.days() & (1 << day)) != 0)
                        .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "day-" + d, value))));
            }
            hours.children(time(t, actions, id, "open", s.open()), time(t, actions, id, "close", s.close()));
        }
        return tabs.tab("hours", t.get("hours.menu"), hours);
    }

    private static Ui.NumberField time(Text t, Actions actions, String id, String key, int minute) {
        return Ui.number("time-" + key, t.get("hours." + key, "time", PortalHours.clock(minute)))
                .range(0, 1439, 1).value(minute).decimals(0).tooltip(t.get("panel.minutes"))
                .onChange((a, value) -> refresh(a, actions.change(a.player(), id, "time-" + key, value)));
    }
    private static Ui.Select style(Text t, Actions actions, String id, String key, String value, List<String> choices) {
        Ui.Select select = Ui.select(key, t.get("style." + key, "value", t.get("style.choice." + value))).value(value);
        for (String choice : choices) select.option(choice, t.get("style.choice." + choice));
        return select.onChange((a, choice) -> refresh(a, actions.change(a.player(), id, key, choice)));
    }
    static Ui.Button pageButton(Text t, Actions actions, String direction, String scope, int page) {
        return Ui.button(scope + "-" + direction).label(t.get("menu." + direction)).icon(Ui.item(Material.ARROW)).ghost()
                .onClick(a -> refresh(a, actions.choose(a.player(), scope, Integer.toString(page))));
    }
    static Result refresh(PanelAction a, Result result) { if (result.success()) a.refresh(); return result; }
    static Material icon(Portal p) { return p.paused() ? Material.REDSTONE_BLOCK : p.target() == null ? Material.ENDER_PEARL : Material.ENDER_EYE; }
}
