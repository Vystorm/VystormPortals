package at.esoren.vystormportals;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/**
 * "Portal access" entry in the Vystorm Core player menu (Core 0.43.0+, sneak + right-click a player): the owner picks
 * which of their portals the target may use, through the same code as {@code /vportal trust|untrust NAME PLAYER}.
 */
final class TrustMenuEntries {
    static final int MAX_SHOWN=45;
    private TrustMenuEntries() {}

    /** The viewer's own portals by name, at most one chest page. */
    static List<Portal> own(Collection<Portal> portals,UUID viewer) {
        return portals.stream().filter(p->p.owner().equals(viewer)).sorted(Comparator.comparing(Portal::id)).limit(MAX_SHOWN).toList();
    }
    static long trustedOn(Collection<Portal> portals,UUID viewer,UUID target) {
        return own(portals,viewer).stream().filter(p->p.trusted().contains(target)).count();
    }
    static boolean visible(PortalsPlugin plugin,Player viewer,Player target) {
        return plugin.trust().enabled() && !viewer.equals(target) && viewer.hasPermission("vystormportals.use")
                && (viewer.hasPermission("vystormportals.create") || viewer.hasPermission("vystormportals.admin"))
                && !own(plugin.portals().values(),viewer.getUniqueId()).isEmpty();
    }

    static void register(PortalsPlugin plugin,Lang lang) {
        plugin.core().gui().playerMenu().register(plugin,PlayerMenuEntry.builder("portal-access")
                .priority(130)
                .visibleWhen((viewer,target)->visible(plugin,viewer,target))
                .icon((viewer,target)->MenuItems.item(plugin.core().compat().platform(),lang,viewer,Material.ENDER_EYE,"trust-menu.entry","trust-menu.entry-lore",
                        "player",target.getName(),"count",trustedOn(plugin.portals().values(),viewer.getUniqueId(),target.getUniqueId()),
                        "total",own(plugin.portals().values(),viewer.getUniqueId()).size()))
                .onClick((viewer,target,click)->{ plugin.openTrust(viewer,target); return PlayerMenu.Result.KEEP; })
                .build());
    }

    static void picker(PortalsPlugin plugin,Lang lang,Player viewer,Player target) {
        if(!target.isOnline() || !visible(plugin,viewer,target)) {viewer.closeInventory();return;}
        List<Portal> portals=own(plugin.portals().values(),viewer.getUniqueId());
        Map<Integer,String> slots=new HashMap<>();
        Inventory inventory=plugin.core().gui().createInventory(plugin,null,viewer.getUniqueId(),54,
                lang.text(viewer,"trust-menu.title","player",target.getName()),new GuiCallbacks() {
                    @Override public void click(InventoryClickEvent event) {
                        event.setCancelled(true);
                        int slot=event.getRawSlot();
                        if(slot==49) {plugin.getServer().getScheduler().runTask(plugin,()->viewer.closeInventory());return;}
                        String id=slots.get(slot);
                        if(id==null) return;
                        plugin.getServer().getScheduler().runTask(plugin,()->{
                            Portal portal=plugin.portals().get(id);
                            if(portal==null || !viewer.isOnline()) return;
                            plugin.run(viewer,portal.trusted().contains(target.getUniqueId())?"untrust":"trust",id,target.getName());
                            picker(plugin,lang,viewer,target);
                        });
                    }
                });
        for(int i=0;i<portals.size();i++) {
            Portal portal=portals.get(i);
            boolean trusted=portal.trusted().contains(target.getUniqueId());
            inventory.setItem(i,MenuItems.item(plugin.core().compat().platform(),lang,viewer,trusted?Material.LIME_DYE:Material.GRAY_DYE,
                    trusted?"trust-menu.trusted":"trust-menu.not-trusted",trusted?"trust-menu.trusted-lore":"trust-menu.not-trusted-lore",
                    "name",portal.id(),"player",target.getName(),"access",lang.plain(viewer,portal.publicAccess()?"menu.public":"menu.private")));
            slots.put(i,portal.id());
        }
        inventory.setItem(49,MenuItems.item(plugin.core().compat().platform(),lang,viewer,Material.BARRIER,"menu.close",null));
        viewer.openInventory(inventory);
    }
}
