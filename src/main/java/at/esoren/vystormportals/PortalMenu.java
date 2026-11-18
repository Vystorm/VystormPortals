package at.esoren.vystormportals;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.visual.ParticlePaths;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.Visuals;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Chest menu for manual portals (/vportal menu, Core /vmenu → World): every portal the player may use, and for owners
 * and admins the buttons for access, link, unlink, warmup, delete and "create here". Every change runs through the
 * matching /vportal action, so permissions, checks and messages are exactly those of the commands.
 */
final class PortalMenu {
    static final int PAGE_SIZE=45;
    static final int WARMUP_STEP=10, WARMUP_SHIFT_STEP=50;
    private final PortalsPlugin plugin;
    private final Lang lang;
    PortalMenu(PortalsPlugin plugin,Lang lang){this.plugin=plugin;this.lang=lang;}

    /** Visible portals, the viewer's own first, then by name. */
    static List<Portal> ordered(Collection<Portal> all,UUID viewer,Predicate<Portal> visible) {
        return all.stream().filter(visible)
                .sorted(Comparator.comparing((Portal p)->!p.owner().equals(viewer)).thenComparing(Portal::id)).toList();
    }
    static int pages(int count) { return Math.max(1,(count+PAGE_SIZE-1)/PAGE_SIZE); }
    static int page(int wanted,int count) { return Math.clamp(wanted,0,pages(count)-1); }
    /** Left click adds, right click removes; shift uses the big step. Always 0..200 ticks. */
    static int warmupStep(int current,boolean remove,boolean shift) {
        int step=shift?WARMUP_SHIFT_STEP:WARMUP_STEP;
        return Math.clamp(current+(remove?-step:step),0,200);
    }

    void open(Player player) { overview(player,0); }

    private void overview(Player player,int wanted) {
        List<Portal> list=ordered(plugin.portals().values(),player.getUniqueId(),p->plugin.allowed(player,p));
        Map<Integer,Consumer<ClickType>> actions=new HashMap<>();
        Inventory inventory=list(player,"menu.title",list,wanted,p->icon(player,p,"menu.portal-lore"),p->detail(player,p.id()),this::overview,actions);
        if(player.hasPermission("vystormportals.create") || player.hasPermission("vystormportals.admin")) {
            inventory.setItem(53,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.NETHER_STAR,"menu.create","menu.create-lore"));
            actions.put(53,click->create(player));
        }
        if(list.isEmpty()) inventory.setItem(22,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.GRAY_STAINED_GLASS_PANE,"menu.empty","menu.empty-lore"));
        player.openInventory(inventory);
    }

    /** One page of portal icons with page buttons; the caller adds extra buttons to actions before opening. */
    private Inventory list(Player player,String titleKey,List<Portal> list,int wanted,Function<Portal,ItemStack> icon,Consumer<Portal> click,
            PageOpener reopen,Map<Integer,Consumer<ClickType>> actions) {
        int page=page(wanted,list.size()),pages=pages(list.size());
        Inventory inventory=inventory(player,54,lang.text(player,titleKey,"page",page+1,"pages",pages),actions);
        for(int slot=0;slot<PAGE_SIZE && page*PAGE_SIZE+slot<list.size();slot++) {
            Portal portal=list.get(page*PAGE_SIZE+slot);
            inventory.setItem(slot,icon.apply(portal));
            actions.put(slot,c->click.accept(portal));
        }
        if(page>0) {inventory.setItem(45,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.previous",null));actions.put(45,c->reopen.open(player,page-1));}
        if(page<pages-1) {inventory.setItem(51,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.next",null));actions.put(51,c->reopen.open(player,page+1));}
        inventory.setItem(49,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.BARRIER,"menu.close",null));actions.put(49,c->player.closeInventory());
        return inventory;
    }

    private void detail(Player player,String id) {
        Portal portal=plugin.portals().get(id);
        if(portal==null || !plugin.allowed(player,portal)) {overview(player,0);return;}
        Map<Integer,Consumer<ClickType>> actions=new HashMap<>();
        Inventory inventory=inventory(player,27,lang.text(player,"menu.detail-title","name",portal.id()),actions);
        inventory.setItem(4,icon(player,portal,"menu.detail-lore"));
        inventory.setItem(14,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.COMPASS,"menu.locate","menu.locate-lore"));
        actions.put(14,c->locate(player,portal));
        inventory.setItem(22,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.back",null));
        actions.put(22,c->overview(player,0));
        if(plugin.canEdit(player,portal)) {
            inventory.setItem(15,MenuItems.item(plugin.core().compat().platform(),lang,player,portal.paused()?Material.REDSTONE_TORCH:Material.LEVER,
                    portal.paused()?"menu.resume":"menu.pause","menu.pause-lore"));
            actions.put(15,c->change(player,id,portal.paused()?"resume":"pause",id));
            boolean open=portal.publicAccess();
            inventory.setItem(10,MenuItems.item(plugin.core().compat().platform(),lang,player,open?Material.LIME_DYE:Material.GRAY_DYE,open?"menu.access-public":"menu.access-private","menu.access-lore"));
            actions.put(10,c->change(player,id,"access",id,open?"private":"public"));
            inventory.setItem(11,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.LEAD,"menu.link","menu.link-lore","target",Objects.toString(portal.target(),"-")));
            actions.put(11,c->linkPicker(player,id,0));
            if(portal.target()!=null) {
                inventory.setItem(12,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.SHEARS,"menu.unlink","menu.unlink-lore","target",portal.target()));
                actions.put(12,c->change(player,id,"unlink",id));
            }
            inventory.setItem(13,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.CLOCK,"menu.warmup","menu.warmup-lore","ticks",portal.warmupTicks(),
                    "seconds",portal.warmupTicks()/20.0));
            actions.put(13,c->change(player,id,"warmup",id,Integer.toString(warmupStep(portal.warmupTicks(),c.isRightClick(),c.isShiftClick()))));
            if(plugin.hours()!=null) {
                String summary=plugin.hours().of(id).map(s->hoursSummary(player,s)).orElse(lang.plain(player,"hours.always"));
                inventory.setItem(9,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.DAYLIGHT_DETECTOR,"hours.menu","hours.menu-lore","hours",summary));
                actions.put(9,c->hours(player,id));
            }
            if(PortalSwitches.designer(plugin.getConfig())) {   // designer.enabled (1.24.0): no style button while it is off
                inventory.setItem(17,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.FIREWORK_STAR,"menu.style","menu.style-lore"));
                actions.put(17,c->style(player,id));
            }
            inventory.setItem(16,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.LAVA_BUCKET,"menu.delete","menu.delete-lore"));
            actions.put(16,c->{
                if(!c.isShiftClick()) {lang.send(player,"menu.delete-hint","name",id);return;}
                plugin.run(player,"delete",id);overview(player,0);
            });
        }
        player.openInventory(inventory);
    }

    /** Style page: left click = next choice, right click = previous; every change is saved at once. */
    private void style(Player player,String id) {
        Portal portal=plugin.portals().get(id);
        if(portal==null || !plugin.canEdit(player,portal) || plugin.styles()==null || !PortalSwitches.designer(plugin.getConfig())) {overview(player,0);return;}
        PortalStyles.Style s=plugin.styles().of(id);
        Map<Integer,Consumer<ClickType>> actions=new HashMap<>();
        Inventory inventory=inventory(player,27,lang.text(player,"style.title","name",id),actions);
        inventory.setItem(10,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.BLAZE_POWDER,"style.particle","style.cycle-lore","value",lang.plain(player,"style.choice."+s.particle())));
        actions.put(10,c->setStyle(player,id,new PortalStyles.Style(PortalStyles.cycle(PortalStyles.PARTICLES,s.particle(),c.isRightClick()),s.sound(),s.arrival())));
        inventory.setItem(12,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.NOTE_BLOCK,"style.sound","style.cycle-lore","value",lang.plain(player,"style.choice."+s.sound())));
        actions.put(12,c->setStyle(player,id,new PortalStyles.Style(s.particle(),PortalStyles.cycle(PortalStyles.SOUNDS,s.sound(),c.isRightClick()),s.arrival())));
        inventory.setItem(14,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ENDER_PEARL,"style.arrival","style.cycle-lore","value",lang.plain(player,"style.choice."+s.arrival())));
        actions.put(14,c->setStyle(player,id,new PortalStyles.Style(s.particle(),s.sound(),PortalStyles.cycle(PortalStyles.ARRIVALS,s.arrival(),c.isRightClick()))));
        inventory.setItem(16,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.SPYGLASS,"style.preview","style.preview-lore"));
        actions.put(16,c->preview(player,portal,s));
        inventory.setItem(22,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.back",null));
        actions.put(22,c->detail(player,id));
        player.openInventory(inventory);
    }

    /** "Mon–Fri 08:00–22:00" style summary using the language's short day names. */
    private String hoursSummary(Player player,PortalHours.Schedule s) {
        StringBuilder days=new StringBuilder();
        for(int i=0;i<7;i++) if((s.days()>>i&1)==1) days.append(days.isEmpty()?"":" ").append(lang.plain(player,"hours.day."+(i+1)));
        if(days.isEmpty()) days.append(lang.plain(player,"hours.no-days"));
        return days+" · "+PortalHours.clock(s.open())+"–"+PortalHours.clock(s.close());
    }

    /** Opening hours page: day toggles, open and close time (left +1 h, right −1 h, shift ±15 min), always open. */
    private void hours(Player player,String id) {
        Portal portal=plugin.portals().get(id);
        if(portal==null || !plugin.canEdit(player,portal) || plugin.hours()==null) {overview(player,0);return;}
        Optional<PortalHours.Schedule> current=plugin.hours().of(id);
        PortalHours.Schedule s=current.orElse(PortalHours.Schedule.DEFAULT);
        Map<Integer,Consumer<ClickType>> actions=new HashMap<>();
        Inventory inventory=inventory(player,27,lang.text(player,"hours.title","name",id),actions);
        for(int i=0;i<7;i++) {
            int day=i; boolean on=current.isPresent() && (s.days()>>i&1)==1;
            inventory.setItem(1+i,MenuItems.item(plugin.core().compat().platform(),lang,player,on?Material.LIME_DYE:Material.GRAY_DYE,on?"hours.day-on":"hours.day-off","hours.day-lore","day",lang.plain(player,"hours.day."+(i+1))));
            actions.put(1+i,c->setHours(player,id,current.isPresent()?s.toggleDay(day):PortalHours.Schedule.DEFAULT));
        }
        inventory.setItem(11,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.CLOCK,"hours.open","hours.time-lore","time",PortalHours.clock(s.open())));
        actions.put(11,c->setHours(player,id,s.open(s.open()+timeStep(c))));
        inventory.setItem(15,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.RECOVERY_COMPASS,"hours.close","hours.time-lore","time",PortalHours.clock(s.close())));
        actions.put(15,c->setHours(player,id,s.close(s.close()+timeStep(c))));
        inventory.setItem(13,MenuItems.item(plugin.core().compat().platform(),lang,player,current.isPresent()?Material.REDSTONE_LAMP:Material.GLOWSTONE,
                current.isPresent()?"hours.timed":"hours.always-item","hours.toggle-lore","hours",current.map(x->hoursSummary(player,x)).orElse(lang.plain(player,"hours.always"))));
        actions.put(13,c->setHours(player,id,current.isPresent()?null:PortalHours.Schedule.DEFAULT));
        inventory.setItem(22,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.back",null));
        actions.put(22,c->detail(player,id));
        player.openInventory(inventory);
    }

    private static int timeStep(ClickType click) { return (click.isShiftClick()?15:60)*(click.isRightClick()?-1:1); }

    private void setHours(Player player,String id,PortalHours.Schedule schedule) {
        Portal portal=plugin.portals().get(id);
        if(portal==null || !plugin.canEdit(player,portal)) {overview(player,0);return;}
        try { plugin.hours().set(id,schedule); }
        catch(java.io.IOException failure) { lang.send(player,"hours.save-failed"); plugin.getLogger().warning("hours.yml not saved: "+failure); }
        hours(player,id);
    }

    private void setStyle(Player player,String id,PortalStyles.Style style) {
        Portal portal=plugin.portals().get(id);
        if(portal==null || !plugin.canEdit(player,portal) || !PortalSwitches.designer(plugin.getConfig())) {overview(player,0);return;}
        try { plugin.styles().set(id,style); }
        catch(java.io.IOException failure) { lang.send(player,"style.save-failed"); plugin.getLogger().warning("styles.yml not saved: "+failure); }
        style(player,id);
    }

    /** Shows the frame particles and the arrival effect to the editor only. */
    void preview(Player player,Portal portal,PortalStyles.Style style) {
        World world=plugin.getServer().getWorld(portal.frame().world());
        if(world==null || !world.equals(player.getWorld())) {lang.send(player,"style.preview-far");return;}
        Particle particle=PortalStyles.particle(style.particle());
        if(particle!=null && particle.getDataType()==Void.class) plugin.core().particle().visuals().particles(player,particle,plugin.core().particle().paths().outline(portal.frame(),12),null);
        String sound=PortalStyles.sound(style.sound());
        if(sound!=null) player.playSound(player.getLocation(),sound,0.6f,1.0f);
        PortalStyles.arrival(player.getLocation(),style,player);
    }

    private void linkPicker(Player player,String id,int wanted) {
        Portal source=plugin.portals().get(id);
        if(source==null || !plugin.canEdit(player,source)) {overview(player,0);return;}
        List<Portal> targets=ordered(plugin.portals().values(),player.getUniqueId(),p->!p.id().equals(id) && plugin.canEdit(player,p));
        Map<Integer,Consumer<ClickType>> actions=new HashMap<>();
        Inventory inventory=list(player,"menu.link-title",targets,wanted,p->icon(player,p,"menu.link-target-lore"),
                p->change(player,id,"link",id,p.id()),(viewer,page)->linkPicker(viewer,id,page),actions);
        inventory.setItem(47,MenuItems.item(plugin.core().compat().platform(),lang,player,Material.ARROW,"menu.back",null));
        actions.put(47,c->detail(player,id));
        player.openInventory(inventory);
    }

    /** Runs the command action, then shows the portal again with fresh data. */
    private void change(Player player,String id,String... args) {
        plugin.run(player,args);
        detail(player,id);
    }

    void locate(Player player,Portal portal) {
        player.closeInventory();
        World world=plugin.getServer().getWorld(portal.frame().world());
        Point3 o=portal.frame().origin();
        String position=(int)Math.floor(o.x())+" "+(int)Math.floor(o.y())+" "+(int)Math.floor(o.z());
        if(world==null || !world.equals(player.getWorld())) {
            lang.send(player,"menu.locate-far","name",portal.id(),"world",world==null?"?":world.getName(),"position",position);
            return;
        }
        long distance=Math.round(player.getLocation().distance(new Location(world,o.x(),o.y(),o.z())));
        lang.send(player,"menu.locate-near","name",portal.id(),"position",position,"distance",distance);
        plugin.core().particle().visuals().particles(player,Particle.END_ROD,plugin.core().particle().paths().outline(portal.frame(),12),null);
    }

    void create(Player player) {
        player.closeInventory();
        try {
            Vystorm.vystorm_core.compat.dialog.DialogInterface dialogs=plugin.core().dialogs();
            dialogs.prompt(plugin,player,lang.ref("menu.create-title"),lang.ref("menu.create-question"),"",
                    name->plugin.run(player,"create",name.strip().replace(' ','-')),()->{},Player::isOnline);
        } catch(RuntimeException | LinkageError noDialogs) { lang.send(player,"menu.create-fallback"); }
    }

    private ItemStack icon(Player player,Portal portal,String loreKey) {
        World world=plugin.getServer().getWorld(portal.frame().world());
        Object[] info=PortalsPlugin.info(portal,world==null?"?":world.getName(),plugin.playerName(portal.owner()));
        Material material=portal.paused()?Material.REDSTONE_BLOCK:portal.target()==null?Material.ENDER_PEARL:Material.ENDER_EYE;
        return MenuItems.item(plugin.core().compat().platform(),lang,player,material,"menu.portal",loreKey,"name",info[0],"world",info[1],"position",info[2],"size",info[3],
                "access",lang.plain(player,portal.publicAccess()?"menu.public":"menu.private"),"warmup",info[5],"target",info[6],"owner",info[7],"status",lang.plain(player,portal.paused()?"menu.paused":"menu.open"));
    }

    private Inventory inventory(Player player,int size,String title,Map<Integer,Consumer<ClickType>> actions) {
        return plugin.core().gui().createInventory(plugin,null,player.getUniqueId(),size,title,new GuiCallbacks() {
            @Override public void click(InventoryClickEvent event) {
                event.setCancelled(true);
                if(event.getRawSlot()<0 || event.getRawSlot()>=size) return;
                Consumer<ClickType> action=actions.get(event.getRawSlot());
                ClickType click=event.getClick();
                if(action!=null) plugin.getServer().getScheduler().runTask(plugin,()->{ if(player.isOnline()) action.accept(click); });
            }
        });
    }
    @FunctionalInterface private interface PageOpener { void open(Player player,int page); }
}
