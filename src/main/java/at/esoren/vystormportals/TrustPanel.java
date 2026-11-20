package at.esoren.vystormportals;

import Vystorm.vystorm_core.panel.*;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Player-menu trust picker; all owned portals are reachable through paging. */
final class TrustPanel {
    static final String NAME = "portal-access";
    record Row(String id, boolean trusted, boolean publicAccess) {}
    record Overview(String target, List<Row> rows, Row selected, int page, int pages) {}
    interface Actions {
        Result choose(Player player, String id);
        Result page(Player player, int page);
        Result trust(Player player, String id, boolean value);
    }
    static Panel panel(PortalPanel.Text t, Overview o, Actions actions) {
        Ui.ListView list = Ui.list("trust-portals").empty(t.get("menu.empty"))
                .items(o.rows(), r -> Ui.listItem(r.id(), r.id()).icon(Ui.item(r.trusted() ? Material.LIME_DYE : Material.GRAY_DYE))
                        .subtitle(t.get(r.publicAccess() ? "menu.public" : "menu.private")))
                .selected(o.selected() == null ? null : o.selected().id()).grow(2)
                .onSelect((a, id) -> PortalPanel.refresh(a, actions.choose(a.player(), id)));
        Ui.Box detail = Ui.col().grow(3);
        if (o.selected() != null) {
            Row r = o.selected();
            // 1.25.0: the one setting is a list row on Vystorm Screens (Core 0.66.0), its help text the description.
            detail.children(Ui.heading(r.id()), Ui.section(t.get("panel.trust-title")).list().id("trust-settings").child(
                    Ui.toggle("trusted", t.get("trust-menu.entry", "player", o.target())).description(t.get("panel.trust-help", "player", o.target()))
                            .value(r.trusted())
                            .onChange((a, value) -> PortalPanel.refresh(a, actions.trust(a.player(), r.id(), value)))));
        }
        Panel panel = Panel.page(t.get("trust-menu.title", "player", o.target()))
                .subtitle(t.get("menu.title", "page", o.page() + 1, "pages", o.pages()))
                .icon(Ui.item(Material.ENDER_EYE)).size(Panel.Size.LARGE).child(Ui.row().gap(8).children(list, detail));
        if (o.page() > 0) panel.footer(Ui.button("previous").label(t.get("menu.previous")).ghost()
                .onClick(a -> PortalPanel.refresh(a, actions.page(a.player(), o.page() - 1))));
        if (o.page() + 1 < o.pages()) panel.footer(Ui.button("next").label(t.get("menu.next")).ghost()
                .onClick(a -> PortalPanel.refresh(a, actions.page(a.player(), o.page() + 1))));
        return panel.footer(Ui.button("close").label(t.get("menu.close")).ghost()
                .onClick(a -> { a.closeAndThen(p -> {}); return Result.ok(); }));
    }
}
