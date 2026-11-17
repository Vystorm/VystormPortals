package at.esoren.vystormportals;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Portal-specific rules and persistence; all visual geometry lives in Vystorm Core. */
public final class PortalsPlugin extends JavaPlugin implements Listener {
    private Map<String, Portal> portals = Map.of();
    private final Map<UUID, ActiveView> views = new HashMap<>();
    private final Map<UUID, Passage> pending = new HashMap<>();
    private final Map<UUID, AdmissionCorrection> corrections = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private PortalStore store;
    private PortalStyles styles;
    private PortalHours hours;
    private TaskGroup tasks;
    private DestinationChunks destinationChunks;
    private NativeEndViews endViews;
    private NativeNetherViews netherViews;
    private NativeGatewayViews gatewayViews;
    private InstantPortalTravel instantTravel;
    private EntityTravel entityTravel;
    private PortalMenu menu;
    private PortalPanels panelMenus;
    private PortalTrips trips;
    TravelGates gates;
    private Trust trust = new Trust(true, 16, true);
    private long tick;
    /** trust.* config: trusted players may use a private portal. Disabled = saved lists are kept but ignored. */
    record Trust(boolean enabled, int maxPerPortal, boolean playerMenu) {
        static Trust read(org.bukkit.configuration.ConfigurationSection c) {
            return new Trust(c.getBoolean("trust.enabled", true), Math.clamp(c.getInt("trust.max-per-portal", 16), 1, Portal.MAX_TRUSTED), c.getBoolean("trust.player-menu", true));
        }
    }
    private record ActiveView(String portal, PortalPreview view) {}
    private record Passage(String portal, String target, Location start, long due, PlayerMoveEvent admission) {}
    /** The Core hub; set first thing in onEnable. */
    private Vystorm.vystorm_core.API core;
    /** The Core hub for the classes of this plugin (they get the plugin in their constructor). */
    Vystorm.vystorm_core.API core() { return core; }
    @Override public void onEnable() {
        if (!CoreApi.atLeast("0.64.0")) {
            getLogger().severe("Vystorm Core 0.64.0 or newer required");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();
        core.admin().updates().spigot(this, 139879);
        try {
            for (String feature : List.of("instant-travel", "end-portals.gateways", "end-portals.return-view",
                "entity-travel.objects", "entity-travel.projectiles", "entity-travel.mobs", "entity-travel.vehicles", "trust.player-menu",
                "travel-gates.short-commands", "travel-gates.warps.take-others-along"))
                core.settings().config().feature(this, feature);
        } catch (RuntimeException featureSwitchesUnavailable) { }
        saveDefaultConfig();
        try {
            core.settings().config().registerAll(this, null, false);
            for (String key : getConfig().getKeys(false)) core.settings().config().activation(this, key, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        } catch (RuntimeException configMenuUnavailable) {
            getLogger().fine("Config menu unavailable: " + configMenuUnavailable);
        }
        try {
            lang = core.lang().register(this);
        } catch (RuntimeException unavailable) {
            lang = null;
        }
        try {
            core.registerTutorial(this);
        } catch (RuntimeException noTutorial) {
            getLogger().fine("Tutorial not registered: " + noTutorial);
        }
        store = new PortalStore(getDataFolder().toPath().resolve("portals.yml"));
        try {
            portals = store.load();
        } catch (Exception failure) {
            getLogger().severe("Cannot load portals; original file retained: " + failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        styles = new PortalStyles(getDataFolder().toPath().resolve("styles.yml"), core.data().files());
        styles.retain(portals.keySet());
        hours = new PortalHours(getDataFolder().toPath().resolve("hours.yml"), core.data().files());
        hours.retain(portals.keySet());
        getServer().getPluginManager().registerEvents(this, this);
        handleBlockEvents(core.priority().stages());
        handleSharedEvents(core.priority().stages());
        core.command().register(this, lang, commandSpec(this));
        if (getConfig().getBoolean("travel-gates.enabled", true) && getConfig().getBoolean("travel-gates.short-commands", true))
            for (CommandSpec spec : gateCommands(this)) {
                try {
                    core.command().register(this, lang, spec);
                } catch (RuntimeException taken) {
                    getLogger().info("/" + spec.name() + " not registered (" + taken.getMessage() + "); /vportal " + spec.name() + " still works");
                }
            }
        destinationChunks = new DestinationChunks(this, core.compat().platform());
        getServer().getPluginManager().registerEvents(destinationChunks, this);
        gates = new TravelGates(this, destinationChunks, System::currentTimeMillis);
        try {
            trips = new PortalTrips(this, destinationChunks);
            getServer().getPluginManager().registerEvents(trips, this);
            trips.handleSharedEvents(core.priority().stages());
        } catch (Exception failure) {
            getLogger().severe("Portal itineraries unavailable; preserving trips.yml: " + failure);
        }
        endViews = new NativeEndViews(this, core, destinationChunks);
        netherViews = new NativeNetherViews(this, core, destinationChunks);
        gatewayViews = new NativeGatewayViews(this, core, destinationChunks);
        getServer().getPluginManager().registerEvents(netherViews, this);
        if (getConfig().getBoolean("instant-travel", true)) {
            instantTravel = new InstantPortalTravel();
            getServer().getWorlds().forEach(instantTravel::apply);
            getServer().getPluginManager().registerEvents(instantTravel, this);
        }
        entityTravel = new EntityTravel(this, EntityTravel.Options.read(getConfig()));
        tasks = core.tasks().of(this, "portal-visuals");
        tasks.timer(1, 1, this::update);
        trust = Trust.read(getConfig());
        if (getConfig().getBoolean("menu.enabled", true) && lang != null) {
            menu = new PortalMenu(this, lang);
        }
        if (lang != null) {
            try {
                PortalPanels created = new PortalPanels(this, lang, menu == null ? new PortalMenu(this, lang) : menu);
                try {
                    created.register(menu != null);
                    panelMenus = created;
                } catch (RuntimeException failure) {
                    created.close();
                    throw failure;
                }
            } catch (RuntimeException unavailable) {
                getLogger().info("Core panels unavailable; using chest menus: " + unavailable);
            }
        }
        if (menu != null && panelMenus == null) {
            try {
                core.gui().registerMenu(this, "portals", lang.ref("menu.entry"), Material.ENDER_EYE,
                    p -> p.hasPermission("vystormportals.menu") && p.hasPermission("vystormportals.use"), this::openMenu,
                    Vystorm.vystorm_core._uiMethods.menu.MenuCategory.WORLD.group(), lang.ref("menu.entry-desc"));
                } catch (RuntimeException unavailable) {
                getLogger().info("Core main menu unavailable; /vportal menu still works");
            }
        }
        if (trust.enabled() && trust.playerMenu() && lang != null) {
            try {
                TrustMenuEntries.register(this, lang);
            } catch (RuntimeException failure) {
                getLogger().warning("Player menu entry unavailable: " + failure.getMessage());
            }
        }
    }
    @Override public void onDisable() {
        if (gates != null) {
            gates.shutdown();
            gates = null;
        }
        if (panelMenus != null) {
            panelMenus.close();
            panelMenus = null;
        }
        if (trips != null) trips.close();
        if (entityTravel != null) entityTravel.close();
        if (instantTravel != null) instantTravel.close();
        if (tasks != null) tasks.close();
        if (endViews != null) endViews.close();
        if (gatewayViews != null) gatewayViews.close();
        if (netherViews != null) netherViews.close();
        if (destinationChunks != null) destinationChunks.close();
        if (core != null) core.particle().visuals().release(this);
        views.clear();
        pending.clear();
        corrections.clear();
        cooldown.clear();
    }
    private void openMenu(Player player) {
        if (menu == null || !isEnabled() || !getConfig().getBoolean("menu.enabled", true) || !player.hasPermission("vystormportals.menu") || !player.hasPermission("vystormportals.use")) return;
        if (panelMenus != null) {
            try {
                if (panelMenus.open(player)) return;
            } catch (RuntimeException unavailable) {
                getLogger().fine("Portal panel unavailable: " + unavailable);
            }
        }
        menu.open(player);
    }
    void openTrust(Player viewer, Player target) {
        if (!isEnabled() || !trust.playerMenu() || !target.isOnline() || !TrustMenuEntries.visible(this, viewer, target)) return;
        if (panelMenus != null) {
            try {
                if (panelMenus.openTrust(viewer, target)) return;
            } catch (RuntimeException unavailable) {
                getLogger().fine("Trust panel unavailable: " + unavailable);
            }
        }
        TrustMenuEntries.picker(this, lang, viewer, target);
    }
    private static Point3 point(Location p) {
        return new Point3(p.getX(), p.getY(), p.getZ());
    }
    boolean allowed(Player player, Portal p) {
        return player.hasPermission("vystormportals.use") && (p.publicAccess() || p.owner().equals(player.getUniqueId())
                || (trust.enabled() && p.trusted().contains(player.getUniqueId())) || player.hasPermission("vystormportals.admin"));
    }
    /** Owner or admin, and allowed to run editing commands at all. */
    boolean canEdit(Player player, Portal p) {
        return (player.hasPermission("vystormportals.create") || player.hasPermission("vystormportals.admin"))
                && (p.owner().equals(player.getUniqueId()) || player.hasPermission("vystormportals.admin"));
    }
    Map<String, Portal> portals() {
        return portals;
    }
    PortalStyles styles() {
        return styles;
    }
    PortalHours hours() {
        return hours;
    }
    /** Local time for opening hours: setting opening-hours.timezone, else the server's zone. */
    java.time.LocalDateTime localNow() {
        String zone = getConfig().getString("opening-hours.timezone", "");
        try {
            return java.time.LocalDateTime.now(zone == null || zone.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone.strip()));
        } catch (java.time.DateTimeException invalid) {
            return java.time.LocalDateTime.now();
        }
    }
    /** Inside its opening hours (or without any); timed portals off = always. */
    boolean hoursOpen(Portal portal) {
        return portal == null || hours == null || !getConfig().getBoolean("opening-hours.enabled", true) || hours.openAt(portal.id(), localNow());
    }
    /** Not paused and both ends inside their opening hours. */
    boolean routeOpen(Portal portal, Portal destination) {
        return portal.routeOpen(destination) && hoursOpen(portal) && hoursOpen(destination);
    }
    /** "opens in 3 h 05 min" for a closed portal, or empty. */
    private String nextOpening(Portal portal) {
        if (hours == null) return "";
        return hours.of(portal.id()).flatMap(s -> s.untilChange(localNow())).map(PortalHours::duration).orElse("-");
    }
    /** Action bar countdown for players within 6 blocks of a portal with opening hours, every second. */
    private void hoursBar() {
        if (hours == null || !getConfig().getBoolean("opening-hours.enabled", true) || lang == null) return;
        java.time.LocalDateTime now = localNow();
        for (Player player : getServer().getOnlinePlayers()) {
            Portal shown = null;
            double best = 36;
            for (Portal portal : portals.values()) {
                if (hours.of(portal.id()).isEmpty() || !portal.frame().world().equals(player.getWorld().getUID())) continue;
                Point3 o = portal.frame().origin();
                double d = player.getLocation().distanceSquared(new Location(player.getWorld(), o.x(), o.y(), o.z()));
                if (d < best) {
                    best = d;
                    shown = portal;
                }
            }
            if (shown == null) continue;
            PortalHours.Schedule schedule = hours.of(shown.id()).orElseThrow();
            boolean open = schedule.openAt(now);
            String change = schedule.untilChange(now).map(PortalHours::duration).orElse(null);
            String key = open ? (change == null ? "hours.bar-always" : "hours.bar-open") : (change == null ? "hours.bar-never" : "hours.bar-closed");
            core.compat().platform().sendActionBar(player, lang.component(player, key, "name", shown.id(), "time", change == null ? "" : change));
        }
    }
    /** Style particles every half second and the ambient sound every four seconds, only where a player is within 32 blocks. */
    private void ambient() {
        for (Portal portal : portals.values()) {
            PortalStyles.Style style = styles.of(portal.id());
            if (style.plain()) continue;
            World world = getServer().getWorld(portal.frame().world());
            if (world == null) continue;
            Point3 o = portal.frame().origin();
            if (!world.isChunkLoaded((int)Math.floor(o.x())>>4, (int)Math.floor(o.z())>>4)) continue;
            boolean watched = false;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(new Location(world, o.x(), o.y(), o.z())) <= 32 * 32) {
                watched = true;
                break;
            }
            if (!watched) continue;
            PortalStyles.ambient(core.particle().paths(), world, portal, style);
            if (tick % 80 == 0) PortalStyles.ambientSound(world, portal, style);
        }
    }
    void tripCooldown(Player player) {
        cooldown.put(player.getUniqueId(), tick + 40);
    }
    Trust trust() {
        return trust;
    }
    private Portal target(Portal portal) {
        return portal.target() == null ? null : portals.get(portal.target());
    }
    Portal destination(Portal portal) {
        return target(portal);
    }
    private void stopView(UUID player) {
        ActiveView old = views.remove(player);
        if (old != null) old.view.close();
    }
    private void update() {
        tick++;
        if (trips != null && tick % 20 == 0) trips.tick(tick);
        if (gates != null) gates.tick(tick);
        corrections.clear(); // admission corrections run synchronously after the move event
        for (var item : List.copyOf(pending.entrySet())) {
            Player player = getServer().getPlayer(item.getKey());
            Passage passage = item.getValue();
            if (player == null || !player.isOnline() || player.isDead() || passage.admission.isCancelled() || !player.getWorld().equals(passage.start.getWorld())
                    || player.getLocation().distanceSquared(passage.start) > .8 * .8) {
                        pending.remove(item.getKey());
                        continue;
                    }
            if (tick >= passage.due) {
                pending.remove(item.getKey());
                travel(player, passage);
            }
        }
        netherViews.tick(tick);
        if (entityTravel != null) try {
            entityTravel.tick(tick, portals.values());
        } catch (RuntimeException failure) {
            getLogger().warning("Entity travel failed: " + failure.getMessage());
        }
        destinationChunks.expire(tick);
        if (styles != null && tick % 10 == 0) ambient();
        if (tick % 20 == 0) hoursBar();
        int viewers = 0;
        for (Player player : getServer().getOnlinePlayers()) {
            Portal nearest = null;
            double best = 144;
            Point3 eye = point(player.getEyeLocation());
            for (Portal portal : portals.values()) {
                Portal destination = target(portal);
                if (!routeOpen(portal, destination) || !portal.frame().world().equals(player.getWorld().getUID()) || !allowed(player, portal) || !allowed(player, destination)) continue;
                double d = eye.distanceSquared(portal.frame().origin());
                if (d < best && Math.abs(portal.frame().local(eye).z()) > .05) {
                    best = d;
                    nearest = portal;
                }
            }
            if (viewers >= 32) {
                stopView(player.getUniqueId());
                endViews.close(player.getUniqueId());
                gatewayViews.close(player.getUniqueId());
                netherViews.close(player.getUniqueId());
                if (gates != null) gates.release(player.getUniqueId());
                continue;
            }
            TravelGates.Gate gate = gates == null ? null : gates.nearest(player, eye);
            if (gate != null) {
                stopView(player.getUniqueId());
                endViews.close(player.getUniqueId());
                gatewayViews.close(player.getUniqueId());
                netherViews.close(player.getUniqueId());
                viewers++;
                gates.show(player, gate, eye, tick);
                continue;
            }
            if (gates != null) gates.release(player.getUniqueId());
            if (nearest == null) {
                stopView(player.getUniqueId());
                try {
                    if (netherViews.update(player, tick)) {
                        endViews.close(player.getUniqueId());
                        gatewayViews.close(player.getUniqueId());
                        viewers++;
                    } else if (endViews.update(player, tick)) {
                        gatewayViews.close(player.getUniqueId());
                        viewers++;
                    } else if (gatewayViews.update(player, tick)) viewers++;
                }
                catch (RuntimeException failure) {
                    endViews.close(player.getUniqueId());
                    gatewayViews.close(player.getUniqueId());
                    netherViews.close(player.getUniqueId());
                    getLogger().warning("Native view failed: " + failure.getMessage());
                }
                continue;
            }
            endViews.close(player.getUniqueId());
            gatewayViews.close(player.getUniqueId());
            netherViews.close(player.getUniqueId());
            viewers++;
            destinationChunks.touch(target(nearest).frame(), tick);
            ActiveView view = views.get(player.getUniqueId());
            if (view == null || view.view.isClosed() || !view.portal.equals(nearest.id())) {
                stopView(player.getUniqueId());
                try {
                    view = new ActiveView(nearest.id(), new PortalPreview(core, this, player));
                    views.put(player.getUniqueId(), view);
                } catch (IllegalStateException busy) {
                    continue;
                }
            }
            try {
                ViewFrame entryFrame = oriented(nearest.frame(), eye);
                ViewFrame exitFrame = entryFrame.quarterTurns() == nearest.frame().quarterTurns() ? target(nearest).frame() : reverse(target(nearest).frame());
                view.view.upright(entryFrame, exitFrame, eye, Set.of(), tick);
                if (tick % 20 == 0) core.particle().visuals().particles(player, Particle.END_ROD, core.particle().paths().outline(nearest.frame(), 8), null);
            } catch (RuntimeException failure) {
                stopView(player.getUniqueId());
                getLogger().warning("View failed for " + player.getName() + ": " + failure.getMessage());
            }
        }
        if (tick % 200 == 0) cooldown.entrySet().removeIf(e -> tick >= e.getValue());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event.getTo() == null || event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        if (trips != null && trips.active(player)) return;
        if (gates != null && !player.isInsideVehicle() && gates.enter(player, event.getTo())) return;
        if (player.isInsideVehicle() || !player.getPassengers().isEmpty() || tick < cooldown.getOrDefault(player.getUniqueId(), 0L) || pending.containsKey(player.getUniqueId())) return;
        // Runs for every move of every player: cheap world and distance checks first (1.19.1).
        java.util.UUID worldId = player.getWorld().getUID();
        double tx = event.getTo().getX(), ty = event.getTo().getY(), tz = event.getTo().getZ();
        for (Portal portal : portals.values()) {
            var frame = portal.frame();
            if (!frame.world().equals(worldId)) continue;
            double dx = tx - frame.origin().x(), dy = ty - frame.origin().y(), dz = tz - frame.origin().z(), reach = frame.halfWidth() + frame.height() + 3;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            Portal destination = target(portal);
            if (destination == null) continue;
            if (!oriented(portal.frame(), point(event.getFrom())).crosses(point(event.getFrom()), point(event.getTo()), .3, player.getHeight())) continue;
            if (!allowed(player, portal) || !allowed(player, destination)) {
                event.setTo(event.getFrom());
                say(player, "denied");
                return;
            }
            if (!portal.routeOpen(destination)) {
                event.setTo(event.getFrom());
                say(player, "route-paused");
                return;
            }
            if (!hoursOpen(portal) || !hoursOpen(destination)) {
                event.setTo(event.getFrom());
                Portal closed = hoursOpen(portal) ? destination : portal;
                if (lang != null) lang.send(player, "hours.closed", "name", closed.id(), "time", nextOpening(closed));
                tripCooldown(player);
                return;
            }
            if (getConfig().getBoolean("instant-travel", true)) {
                if (!travel(player, new Passage(portal.id(), destination.id(), event.getFrom().clone(), tick, event))) event.setTo(event.getFrom());
                return;
            }
            event.setTo(event.getFrom());
            corrections.put(player.getUniqueId(), new AdmissionCorrection(event.getFrom().clone()));
            pending.put(player.getUniqueId(), new Passage(portal.id(), destination.id(), event.getFrom().clone(), tick + Math.max(1, portal.warmupTicks()), event));
            if (portal.warmupTicks() > 0) say(player, "warming", portal.warmupTicks() / 20.0);
            return;
        }
    }
    private boolean travel(Player player, Passage passage) {
        Portal entrance = portals.get(passage.portal), destination = portals.get(passage.target);
        if (entrance == null || !entrance.routeOpen(destination) || !Objects.equals(entrance.target(), destination.id()) || !allowed(player, entrance) || !allowed(player, destination)) return false;
        World world = getServer().getWorld(destination.frame().world());
        if (world == null) {
            say(player, "unavailable");
            return false;
        }
        ViewFrame entryFrame = oriented(entrance.frame(), point(passage.start));
        ViewFrame exitFrame = entryFrame.quarterTurns() == entrance.frame().quarterTurns() ? destination.frame() : reverse(destination.frame());
        Point3 local = entryFrame.local(point(passage.start));
        Point3 exit = exitFrame.global(new Point3(-local.x(), Math.max(.05, local.y()), .8));
        Location location = new Location(world, exit.x(), exit.y(), exit.z(), player.getLocation().getYaw(), player.getLocation().getPitch());
        Vector currentDirection = player.getLocation().getDirection();
        Point3 direction = entryFrame.directionTo(exitFrame, new Point3(currentDirection.getX(), currentDirection.getY(), currentDirection.getZ()));
        location.setDirection(new Vector(direction.x(), direction.y(), direction.z()));
        if (!safe(location, player.getHeight())) {
            say(player, "unsafe");
            return false;
        }
        stopView(player.getUniqueId());
        Vector velocity = player.getVelocity();
        Point3 momentum = entryFrame.directionTo(exitFrame, new Point3(velocity.getX(), velocity.getY(), velocity.getZ()));
        if (!player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            say(player, "cancelled");
            return false;
        }
        cooldown.put(player.getUniqueId(), tick + 40);
        player.setVelocity(new Vector(momentum.x(), momentum.y(), momentum.z()));
        player.setFallDistance(0);
        if (styles != null) PortalStyles.arrival(location, styles.of(destination.id()), null);
        return true;
    }
    static ViewFrame reverse(ViewFrame f) {
        return new ViewFrame(f.world(), f.origin(), f.quarterTurns() + 2, f.halfWidth(), f.height());
    }
    static ViewFrame oriented(ViewFrame f, Point3 eye) {
        return f.local(eye).z() < 0 ? reverse(f) : f;
    }
    boolean safe(Location location, double height) {
        World w = location.getWorld();
        if (!w.getWorldBorder().isInside(location) || location.getY() < w.getMinHeight() + 1 || location.getY() + height >= w.getMaxHeight()) return false;
        int minX = (int)Math.floor(location.getX() - .3), maxX = (int)Math.floor(location.getX() + .3);
        int minZ = (int)Math.floor(location.getZ() - .3), maxZ = (int)Math.floor(location.getZ() + .3);
        int minY = location.getBlockY(), maxY = (int)Math.floor(location.getY() + height);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!w.isChunkLoaded(x>>4, z>>4)) return false;
            Material floor = w.getBlockAt(x, minY - 1, z).getType();
            if (!floor.isSolid() || floor == Material.MAGMA_BLOCK || floor == Material.CAMPFIRE || floor == Material.SOUL_CAMPFIRE || floor == Material.CACTUS) return false;
            for (int y = minY; y <= maxY; y++) {
                var block = w.getBlockAt(x, y, z);
                if (!block.isPassable() || block.isLiquid() || block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) return false;
            }
        }
        return true;
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        if (gates != null) gates.close(event.getPlayer().getUniqueId(), true);
        cleanup(event.getPlayer());
        if (panelMenus != null) panelMenus.forget(event.getPlayer().getUniqueId());
    }
    @EventHandler public void world(PlayerChangedWorldEvent event) {
        cleanup(event.getPlayer());
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        cleanup(event.getPlayer());
    }
    /**
     * Clicks and teleports go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * A click on a block that is only shown to the player is denied in PROTECT, like breaking and placing there.
     * A teleport is read in OBSERVE: only one that really happens (no plugin cancelled it) closes the player's views,
     * releases the gate and drops the pending passage; the handler never changes the teleport. It is registered
     * before the itineraries' handler, as before.
     */
    void handleSharedEvents(EventStages stages) {
        stages.handle(this, PlayerInteractEvent.class, EventStage.PROTECT, this::interact);
        stages.handle(this, PlayerTeleportEvent.class, EventStage.OBSERVE, this::teleport);
    }
    /** Stage OBSERVE, registered in {@link #handleSharedEvents}. */
    public void teleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        AdmissionCorrection correction = corrections.remove(id);
        // Paper translates our move-event setTo(from) into a PLUGIN teleport. It must
        // not cancel the passage that requested that exact, one-shot correction.
        if (correction != null && correction.matches(event)) return;
        stopView(id);
        if (endViews != null) endViews.close(id);
        if (gatewayViews != null) gatewayViews.close(id);
        if (netherViews != null) netherViews.close(id);
        if (gates != null) gates.release(id);
        pending.remove(id);
    }
    private void cleanup(Player p) {
        if (gates != null) gates.release(p.getUniqueId());
        stopView(p.getUniqueId());
        if (endViews != null) endViews.close(p.getUniqueId());
        if (gatewayViews != null) gatewayViews.close(p.getUniqueId());
        if (netherViews != null) netherViews.close(p.getUniqueId());
        pending.remove(p.getUniqueId());
        corrections.remove(p.getUniqueId());
        cooldown.remove(p.getUniqueId());
    }
    // Illusions must never let a player accidentally interact with the real blocks behind them.
    /** Stage PROTECT, registered in {@link #handleSharedEvents}; a click Bukkit already denies for the block is skipped, as before. */
    public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && illusion(e.getPlayer(), e.getClickedBlock())) e.setCancelled(true);
    }
    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins. Both
     * only deny (PROTECT): no feature of another plugin acts on a block that is merely shown to the player.
     */
    void handleBlockEvents(EventStages stages) {
        stages.handle(this, BlockBreakEvent.class, EventStage.PROTECT, this::breaking);
        stages.handle(this, BlockPlaceEvent.class, EventStage.PROTECT, this::placing);
    }
    /** Stage PROTECT, registered in {@link #handleBlockEvents}. */
    public void breaking(BlockBreakEvent e) {
        if (illusion(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }
    /** Stage PROTECT, registered in {@link #handleBlockEvents}. */
    public void placing(BlockPlaceEvent e) {
        if (illusion(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }
    private boolean illusion(Player player, org.bukkit.block.Block block) {
        ActiveView view = views.get(player.getUniqueId());
        BlockPosition position = new BlockPosition(block.getX(), block.getY(), block.getZ());
        return (view != null && view.view.covers(position)) || (endViews != null && endViews.covers(player.getUniqueId(), position))
                || (gatewayViews != null && gatewayViews.covers(player.getUniqueId(), position))
                || (netherViews != null && netherViews.covers(player.getUniqueId(), position))
                || (gates != null && gates.covers(player.getUniqueId(), position));
    }
    private void commit(Map<String, Portal> next) throws Exception {
        store.save(next);
        portals = Collections.unmodifiableMap(new TreeMap<>(next));
        pending.clear();
        corrections.clear();
        if (styles != null) styles.retain(portals.keySet());
        if (hours != null) hours.retain(portals.keySet());
        for (UUID id : List.copyOf(views.keySet())) stopView(id);
    }
    private Portal editable(Player p, String id) {
        Portal portal = portals.get(id.toLowerCase(Locale.ROOT));
        if (portal == null) throw new PortalInputException("unknown-portal", "Unknown portal: " + id, id);
        if (!p.hasPermission("vystormportals.admin") && !portal.owner().equals(p.getUniqueId())) throw new PortalInputException("not-owner", "Not your portal");
        return portal;
    }
    /** Runs a /vportal action (English ids, e.g. {@code "link", "a", "b"}) as the player, with all checks and messages of the command. */
    void run(Player player, String... args) {
        execute(player, args);
    }
    private boolean execute(Player player, String[] args) {
        CommandSender sender = player;
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        try {
            if (action.equals("nearby")) {
                if (args.length != 1) {
                    say(sender, "usage");
                    return true;
                }
                List<Portal> nearby = nearby(player);
                if (nearby.isEmpty()) say(sender, "nearby-empty");
                else {
                    say(sender, "nearby-title");
                    Point3 position = new Point3(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
                    for (Portal p : nearby) say(sender, "nearby-entry", p.id(), (long)Math.ceil(Math.sqrt(p.frame().origin().distanceSquared(position))), Objects.toString(p.target(), "-"));
                }
                return true;
            }
            if (action.equals("list")) {
                say(sender, "list", portals.values().stream().filter(p -> allowed(player, p)).map(p -> p.id() + " -> " + Objects.toString(p.target(), "-")).toList());
                return true;
            }
            if (action.equals("info")) {
                if (args.length != 2) {
                    say(sender, "usage");
                    return true;
                }
                Portal p = portals.get(args[1].toLowerCase(Locale.ROOT));
                if (p == null || !allowed(player, p)) throw new PortalInputException("unknown-portal", "Unknown portal: " + args[1], args[1]);
                World world = getServer().getWorld(p.frame().world());
                Object[] details = info(p, world == null ? p.frame().world().toString() : world.getName(), getServer().getOfflinePlayer(p.owner()).getName());
                if (lang != null) details[4] = lang.plain(player, p.publicAccess() ? "menu.public" : "menu.private");
                say(sender, "info", details);
                say(sender, p.paused() ? "status-paused" : "status-open", p.id());
                return true;
            }
            if (action.equals("menu")) {
                if (args.length != 1) {
                    say(sender, "usage");
                    return true;
                }
                if (menu == null) {
                    say(sender, "menu-disabled");
                    return true;
                }
                if (!player.hasPermission("vystormportals.menu") || !player.hasPermission("vystormportals.use")) {
                    say(sender, "denied");
                    return true;
                }
                openMenu(player);
                return true;
            }
            if (!player.hasPermission("vystormportals.create") && !player.hasPermission("vystormportals.admin")) {
                say(sender, "denied");
                return true;
            }
            Map<String, Portal> next = new TreeMap<>(portals);
            switch (action) {
                case "create" -> {
                    if (args.length < 2 || args.length > 4) {
                        say(sender, "usage");
                        break;
                    }
                    String id = args[1].toLowerCase(Locale.ROOT);
                    if (next.containsKey(id)) throw new PortalInputException("name-exists", "Name already exists");
                    int width = args.length > 2 ? number(args[2], "width") : 3, height = args.length > 3 ? number(args[3], "height") : 3;
                    if (width < 2 || width > 8 || height < 2 || height > 8) throw new PortalInputException("dimensions", "Dimensions must be 2..8");
                    int facing = Math.floorMod(Math.round(player.getLocation().getYaw() / 90f) + 2, 4);
                    Location at = player.getLocation();
                    ViewFrame frame = new ViewFrame(player.getWorld().getUID(), new Point3(at.getBlockX() + .5, at.getBlockY(), at.getBlockZ() + .5), facing, width / 2.0, height);
                    for (double x = -frame.halfWidth() + .5; x < frame.halfWidth(); x++) for (int y = 0; y < height; y++) {
                        BlockPosition p = BlockPosition.containing(frame.global(new Point3(x, y + .5, 0)));
                        if (!player.getWorld().isChunkLoaded(p.x()>>4, p.z()>>4) || !player.getWorld().getBlockAt(p.x(), p.y(), p.z()).isPassable()) throw new PortalInputException("aperture", "Aperture must be clear and loaded");
                    }
                    next.put(id, new Portal(id, player.getUniqueId(), frame, null, true, 0));
                    commit(next);
                    say(sender, "created", id);
                    core.particle().visuals().particles(player, Particle.END_ROD, core.particle().paths().outline(frame, 12), null);
                }
                case "link" -> {
                    if (args.length != 3) {
                        say(sender, "usage");
                        break;
                    }
                    Portal a = editable(player, args[1]), b = editable(player, args[2]);
                    commit(linked(next, a.id(), b.id()));
                    say(sender, "linked", a.id(), b.id());
                }
                case "unlink" -> {
                    if (args.length != 2) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    if (next.equals(unlinked(next, p.id()))) throw new PortalInputException("not-linked", "Portal " + p.id() + " is not linked", p.id());
                    commit(unlinked(next, p.id()));
                    say(sender, "unlinked", p.id());
                }
                case "delete" -> {
                    if (args.length != 2) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    next.remove(p.id());
                    next.replaceAll((id, value) -> Objects.equals(value.target(), p.id()) ? value.link(null) : value);
                    commit(next);
                    say(sender, "deleted", p.id());
                }
                case "access" -> {
                    if (args.length != 3 || !(args[2].equalsIgnoreCase("public") || args[2].equalsIgnoreCase("private"))) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    next.put(p.id(), p.access(args[2].equalsIgnoreCase("public")));
                    commit(next);
                    say(sender, "updated", p.id());
                }
                case "warmup" -> {
                    if (args.length != 3) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    next.put(p.id(), p.warmup(number(args[2], "warmup")));
                    commit(next);
                    say(sender, "updated", p.id());
                }
                case "pause", "resume" -> {
                    if (args.length != 2) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    next.put(p.id(), p.pause(action.equals("pause")));
                    commit(next);
                    say(sender, action.equals("pause") ? "status-paused" : "status-open", p.id());
                }
                case "rename" -> {
                    if (args.length != 3) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    String newId = args[2].toLowerCase(Locale.ROOT);
                    commit(renamed(next, p.id(), newId));
                    say(sender, "renamed", p.id(), newId);
                }
                case "trust", "untrust" -> {
                    if (!trust.enabled()) {
                        say(sender, "trust-disabled");
                        break;
                    }
                    if (args.length == 2 && action.equals("trust")) {
                        Portal p = editable(player, args[1]);
                        say(sender, "trusted-list", p.id(), p.trusted().isEmpty() ? "-" : String.join(", ", p.trusted().stream().map(this::playerName).toList()));
                        break;
                    }
                    if (args.length != 3) {
                        say(sender, "usage");
                        break;
                    }
                    Portal p = editable(player, args[1]);
                    OfflinePlayer who = findPlayer(args[2], p);
                    boolean add = action.equals("trust");
                    commit(trusted(next, p.id(), who.getUniqueId(), add, trust.maxPerPortal()));
                    String name = Objects.requireNonNullElse(who.getName(), args[2]);
                    say(sender, add ? "trusted" : "untrusted", p.id(), name);
                    Player online = who.getPlayer();
                    if (online != null && !online.equals(player)) say(online, add ? "trusted-notify" : "untrusted-notify", player.getName(), p.id());
                }
                default -> say(sender, "usage");
            }
        } catch (IllegalArgumentException error) {
            say(sender, "invalid", error instanceof PortalInputException input ? input.translated(lang, sender) : error.getMessage());
        } catch (Exception failure) {
            getLogger().severe("Portal update failed; runtime state retained: " + failure);
            say(sender, "failed");
        }
        return true;
    }
    /** Links a and b both ways; former partners of either side lose their now one-way link back. */
    static Map<String, Portal> linked(Map<String, Portal> portals, String a, String b) {
        if (a.equals(b)) throw new PortalInputException("self-link", "A portal cannot target itself");
        Map<String, Portal> next = unlinked(unlinked(portals, a), b);
        next.put(a, next.get(a).link(b));
        next.put(b, next.get(b).link(a));
        return next;
    }
    /** Clears the link of id and every link pointing at it; both portals stay. */
    static Map<String, Portal> unlinked(Map<String, Portal> portals, String id) {
        Map<String, Portal> next = new TreeMap<>(portals);
        next.replaceAll((key, value) -> key.equals(id) || id.equals(value.target()) ? value.link(null) : value);
        return next;
    }
    /** Renames portal id to newId; its partner's link follows, owner, frame, access, warmup and trusted players stay. */
    static Map<String, Portal> renamed(Map<String, Portal> portals, String id, String newId) {
        Portal p = portals.get(id);
        if (p == null) throw new PortalInputException("unknown-portal", "Unknown portal: " + id, id);
        if (!newId.matches("[a-z0-9_-]{1,40}")) throw new PortalInputException("invalid-name", "Invalid portal name: " + newId);
        if (id.equals(newId)) throw new PortalInputException("same-name", "Portal " + id + " already has that name", id);
        if (portals.containsKey(newId)) throw new PortalInputException("name-exists", "Name already exists");
        Map<String, Portal> next = new TreeMap<>(portals);
        next.remove(id);
        next.replaceAll((key, value) -> id.equals(value.target()) ? value.link(newId) : value);
        next.put(newId, p.rename(newId));
        return next;
    }
    /** Adds or removes one trusted player of portal id; rejects the owner, duplicates, unknown entries and a full list. */
    static Map<String, Portal> trusted(Map<String, Portal> portals, String id, UUID player, boolean add, int max) {
        Portal p = portals.get(id);
        if (p == null) throw new PortalInputException("unknown-portal", "Unknown portal: " + id, id);
        if (add) {
            if (p.owner().equals(player)) throw new PortalInputException("owner-access", "The owner can always use " + id, id);
            if (p.trusted().contains(player)) throw new PortalInputException("already-trusted", "Already trusted on " + id, id);
            if (p.trusted().size() >= max) throw new PortalInputException("trust-limit", "At most " + max + " trusted players per portal", max);
        } else if (!p.trusted().contains(player)) throw new PortalInputException("not-trusted", "Not trusted on " + id, id);
        Map<String, Portal> next = new TreeMap<>(portals);
        next.put(id, add ? p.trust(player) : p.untrust(player));
        return next;
    }
    /** Online player, a known offline player, or (for untrust) a trusted entry by its last known name. */
    private OfflinePlayer findPlayer(String name, Portal portal) {
        Player online = getServer().getPlayerExact(name);
        if (online != null) return online;
        for (UUID id : portal.trusted()) {
            OfflinePlayer known = getServer().getOfflinePlayer(id);
            if (name.equalsIgnoreCase(known.getName())) return known;
        }
        OfflinePlayer cached = core.compat().platform().getOfflinePlayerIfCached(name);
        if (cached == null || !cached.hasPlayedBefore()) throw new PortalInputException("unknown-player", "Unknown player: " + name, name);
        return cached;
    }
    String playerName(UUID id) {
        return Objects.requireNonNullElse(getServer().getOfflinePlayer(id).getName(), id.toString());
    }
    /** At most ten accessible manual portals within 256 blocks, without touching chunks. */
    List<Portal> nearby(Player player) {
        Location location = player.getLocation();
        Point3 position = new Point3(location.getX(), location.getY(), location.getZ());
        return portals.values().stream()
                .filter(p -> p.frame().world().equals(location.getWorld().getUID()) && allowed(player, p))
                .filter(p -> p.frame().origin().distanceSquared(position) <= 256d * 256d)
                .sorted(Comparator.comparingDouble((Portal p) -> p.frame().origin().distanceSquared(position)).thenComparing(Portal::id))
                .limit(10).toList();
    }
    /** Placeholder values {0}..{7} of the "info" text. */
    static Object[] info(Portal p, String worldName, String ownerName) {
        Point3 o = p.frame().origin();
        return new Object[]{p.id(), worldName, (int)Math.floor(o.x()) + " " + (int)Math.floor(o.y()) + " " + (int)Math.floor(o.z()),
                Math.round(p.frame().halfWidth() * 2) + "x" + Math.round(p.frame().height()), p.publicAccess() ? "public" : "private",
                p.warmupTicks(), Objects.toString(p.target(), "-"), ownerName == null ? p.owner().toString() : ownerName};
    }
    /** Whole number argument with a readable error instead of the raw Java parser message. */
    static int number(String text, String name) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException invalid) {
            throw new PortalInputException("whole-number", name + " must be a whole number, got \"" + text + "\"", text);
        }
    }
    /** German texts that 1.4.0 and older shipped in config.yml; only texts changed by the admin still override the language files. */
    static final Map<String, String> OLD_DEFAULTS = Map.ofEntries(
            Map.entry("denied", "Dafür fehlt dir die Berechtigung."),
            Map.entry("player-only", "Diesen Befehl im Spiel ausführen."),
            Map.entry("list", "Portale: {0}"),
            Map.entry("created", "Portal {0} angelegt. Mit /vportal link <Name> <Ziel> verbinden."),
            Map.entry("linked", "{0} und {1} sind in beide Richtungen verbunden."),
            Map.entry("deleted", "Portal {0} entfernt."),
            Map.entry("updated", "Portal {0} aktualisiert."),
            Map.entry("invalid", "Ungültige Eingabe: {0}"),
            Map.entry("failed", "Speichern fehlgeschlagen. Bisheriger Stand bleibt erhalten; siehe Serverlog."),
            Map.entry("warming", "Portal öffnet in {0} Sekunden. Zum Abbrechen weggehen."),
            Map.entry("unavailable", "Die Zielwelt ist nicht geladen."),
            Map.entry("unsafe", "Der Ausgang ist nicht geladen oder nicht sicher begehbar."),
            Map.entry("cancelled", "Die Teleportation wurde von einem anderen Plugin abgebrochen."),
            Map.entry("usage", "/vportal create <Name> [Breite Höhe] | link <Name> <Ziel> | delete <Name> | access <Name> public/private | warmup <Name> <Ticks> | list")
    );
    private Vystorm.vystorm_core.lang.Lang lang;
    void say(CommandSender sender, String key, Object... args) {
        String custom = getConfig().getString("messages." + key);
        if (custom != null && !custom.equals(OLD_DEFAULTS.get(key)) || lang == null) {
            String text = custom == null ? key : custom;
            for (int i = 0; i < args.length; i++) text = text.replace("{" + i + "}", String.valueOf(args[i]));
            core.compat().platform().sendMessage(sender, "[VystormPortals] " + text);
            return;
        }
        Object[] pairs = new Object[args.length * 2];
        for (int i = 0; i < args.length; i++) {
            pairs[2 * i] = Integer.toString(i);
            pairs[2 * i + 1] = args[i];
        }
        core.compat().platform().sendMessage(sender, net.kyori.adventure.text.Component.text("[VystormPortals] ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY).append(lang.component(sender, key, pairs)));
    }
    /** A language text as plain string (key itself without the language system). */
    String plain(CommandSender sender, String key) {
        return lang == null ? key : lang.plain(sender, key);
    }
    /** A language text in the action bar; {0}, {1}… are the arguments. */
    void actionBar(Player player, String key, Object... args) {
        if (lang == null) return;
        Object[] pairs = new Object[args.length * 2];
        for (int i = 0; i < args.length; i++) {
            pairs[2 * i] = Integer.toString(i);
            pairs[2 * i + 1] = args[i];
        }
        core.compat().platform().sendActionBar(player, lang.component(player, key, pairs));
    }
    /** Old tab completion for English ids (e.g. {@code "link", "a", ""}); feeds the argument suggestions of the Core tree. */
    List<String> complete(Player player, String... args) {
        List<String> editableIds = portals.values().stream()
                .filter(p -> canEdit(player, p))
                .map(Portal::id).sorted().toList();
        List<String> visibleIds = portals.values().stream().filter(p -> allowed(player, p)).map(Portal::id).sorted().toList();
        List<String> playerNames = List.of();
        if (args.length == 3 && trust.enabled()) {
            Portal portal = portals.get(args[1].toLowerCase(Locale.ROOT));
            if (args[0].equalsIgnoreCase("trust")) playerNames = getServer().getOnlinePlayers().stream()
                    .filter(p -> portal == null || !(portal.owner().equals(p.getUniqueId()) || portal.trusted().contains(p.getUniqueId())))
                    .map(Player::getName).sorted().toList();
            else if (args[0].equalsIgnoreCase("untrust") && portal != null && editableIds.contains(portal.id()))
                playerNames = portal.trusted().stream().map(this::playerName).sorted().toList();
        }
        return suggestions(args, editableIds, visibleIds, playerNames);
    }
    static List<String> suggestions(String[] args, List<String> editableIds, List<String> visibleIds) {
        return suggestions(args, editableIds, visibleIds, List.of());
    }
    static List<String> suggestions(String[] args, List<String> editableIds, List<String> visibleIds, List<String> playerNames) {
        if (args.length == 0) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT);
        List<String> candidates;
        if (args.length == 1) candidates = List.of("list", "nearby", "menu", "info", "create", "link", "unlink", "delete", "access", "warmup", "trust", "untrust", "rename", "pause", "resume");
        else if (args.length == 2 && action.equals("info")) candidates = visibleIds;
        else if (args.length == 2 && Set.of("link", "unlink", "delete", "access", "warmup", "trust", "untrust", "rename", "pause", "resume").contains(action)) candidates = editableIds;
        else if (args.length == 3 && (action.equals("trust") || action.equals("untrust"))) candidates = playerNames;
        else if (args.length == 3 && action.equals("link")) candidates = editableIds.stream()
                .filter(id -> !id.equalsIgnoreCase(args[1])).toList();
        else if (args.length == 3 && action.equals("access")) candidates = List.of("public", "private");
        else return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    /**
     * {@code /vportal} as a Vystorm Core command tree: names, aliases, usage and help come from {@code commands.vportal.*}
     * in the language files ({@code /portale verbinden a b}, {@code /传送门 连接 a b}); English always works. Every node
     * hands the English ids to {@link #run}, so checks and messages are those of the menu and of earlier versions.
     */
    static CommandSpec commandSpec(PortalsPlugin p) {
        return CommandSpec.command("vportal")
                .aliases("portal", "portals")
                .playerOnly()
                .executes(ctx -> p.run(ctx.player()))
                .sub("list", s -> s.playerOnly().executes(ctx -> p.run(ctx.player(), "list")))
                .sub("nearby", s -> s.playerOnly().executes(ctx -> p.run(ctx.player(), "nearby")))
                .sub("trip", s -> s.playerOnly().permission("vystormportals.use").arg(Arg.greedy("action").optional()
                        .suggests((ctx, prefix) -> p.trips == null ? List.of() : p.trips.suggest(ctx.player(), Arrays.copyOfRange(ctx.rawArgs(), 1, ctx.rawArgs().length)))).executes(ctx -> {
                    if (p.trips == null) ctx.reply("failed");
                    else p.trips.command(ctx.player(), ctx.get("action"));
                }))
                .sub("menu", s -> s.playerOnly().executes(ctx -> p.run(ctx.player(), "menu")))
                .sub("info", s -> s.playerOnly().arg(portal(p, "info")).executes(ctx -> p.run(ctx.player(), "info", ctx.get("name"))))
                .sub("create", s -> s.playerOnly().arg(Arg.word("name"))
                        .arg(Arg.integer("width", 2, 8).optional()).arg(Arg.integer("height", 2, 8).optional())
                        .executes(ctx -> p.run(ctx.player(), args("create", ctx.get("name"), ctx.input("width"), ctx.input("height")))))
                .sub("link", s -> s.playerOnly().arg(portal(p, "link"))
                        .arg(Arg.word("target").suggests((ctx, prefix) -> p.complete(ctx.player(), "link", ctx.input("name"), prefix)))
                        .executes(ctx -> p.run(ctx.player(), "link", ctx.get("name"), ctx.get("target"))))
                .sub("unlink", s -> s.playerOnly().arg(portal(p, "unlink")).executes(ctx -> p.run(ctx.player(), "unlink", ctx.get("name"))))
                .sub("delete", s -> s.playerOnly().arg(portal(p, "delete")).executes(ctx -> p.run(ctx.player(), "delete", ctx.get("name"))))
                .sub("access", s -> s.playerOnly().arg(portal(p, "access")).arg(Arg.choice("access", "public", "private"))
                        .executes(ctx -> p.run(ctx.player(), "access", ctx.get("name"), ctx.get("access"))))
                .sub("warmup", s -> s.playerOnly().arg(portal(p, "warmup")).arg(Arg.integer("ticks"))
                        .executes(ctx -> p.run(ctx.player(), "warmup", ctx.get("name"), ctx.input("ticks"))))
                .sub("trust", s -> s.playerOnly().arg(portal(p, "trust")).arg(trustee(p, "trust").optional())
                        .executes(ctx -> p.run(ctx.player(), args("trust", ctx.get("name"), ctx.get("player")))))
                .sub("untrust", s -> s.playerOnly().arg(portal(p, "untrust")).arg(trustee(p, "untrust"))
                        .executes(ctx -> p.run(ctx.player(), "untrust", ctx.get("name"), ctx.get("player"))))
                .sub("rename", s -> s.playerOnly().arg(portal(p, "rename")).arg(Arg.word("newname"))
                        .executes(ctx -> p.run(ctx.player(), "rename", ctx.get("name"), ctx.get("newname"))))
                .sub("pause", s -> s.playerOnly().arg(portal(p, "pause")).executes(ctx -> p.run(ctx.player(), "pause", ctx.get("name"))))
                .sub("resume", s -> s.playerOnly().arg(portal(p, "resume")).executes(ctx -> p.run(ctx.player(), "resume", ctx.get("name"))))
                .sub("home", s -> gateHome(p, s))
                .sub("sethome", s -> gateSetHome(p, s))
                .sub("delhome", s -> gateDelHome(p, s))
                .sub("homes", s -> s.playerOnly().executes(ctx -> p.gates(g -> g.homes(ctx.player()), ctx.player())))
                .sub("warp", s -> gateWarp(p, s))
                .sub("setwarp", s -> gateSetWarp(p, s))
                .sub("delwarp", s -> gateDelWarp(p, s))
                .sub("warps", s -> s.playerOnly().executes(ctx -> p.gates(g -> g.warps(ctx.player()), ctx.player())))
                .sub("energy", s -> s.playerOnly().executes(ctx -> p.gates(g -> g.energy(ctx.player()), ctx.player())))
                .sub("import", s -> s.permission("vystormportals.admin").arg(Arg.choice("source", "huskhomes"))
                        .executes(ctx -> p.importHuskHomes(ctx.sender())))
                .build();
    }
    /**
     * {@code /vportal import huskhomes} (1.22.0, players with vystormportals.admin and the console): copies HuskHomes
     * homes and warps into travel.yml. Existing homes and warps are kept. The database is read off the server thread.
     */
    void importHuskHomes(CommandSender sender) {
        if (gates == null) {
            say(sender, "gate.disabled");
            return;
        }
        java.nio.file.Path husk = getDataFolder().toPath().resolveSibling("HuskHomes");
        say(sender, "gate.import-started");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<HuskHomesImport.Row> rows;
            try {
                rows = HuskHomesImport.read(husk);
            } catch (Exception failure) {
                getLogger().warning("HuskHomes import failed: " + failure);
                String reason = String.valueOf(failure.getMessage());
                Bukkit.getScheduler().runTask(this, () -> say(sender, "gate.import-failed", reason));
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (gates == null) return;
                try {
                    var done = HuskHomesImport.apply(rows, gates.points(), (uuid, name) -> {
                        World world = null;
                        try {
                            if (uuid != null) world = Bukkit.getWorld(UUID.fromString(uuid));
                        } catch (IllegalArgumentException noUuid) { }
                        if (world == null && name != null) world = Bukkit.getWorld(name);
                        return world == null ? null : world.getUID();
                    });
                    say(sender, "gate.import-done", done.homes(), done.warps(), done.existing(), done.noWorld());
                    getLogger().info("HuskHomes import: " + done);
                } catch (java.io.IOException failure) {
                    say(sender, "failed");
                    getLogger().warning("Cannot save travel.yml: " + failure.getMessage());
                }
            });
        });
    }
    /** Runs a travel gate action, or says that travel gates are off. */
    void gates(java.util.function.Consumer<TravelGates> action, Player player) {
        if (gates == null) say(player, "gate.disabled");
        else action.accept(gates);
    }
    private static Arg<String> homeName(PortalsPlugin p) {
        return Arg.word("home").suggests((ctx, prefix) -> p.gates == null ? List.of() : p.gates.homeNames(ctx.player()).stream().filter(n -> n.startsWith(prefix.toLowerCase(Locale.ROOT))).toList());
    }
    private static Arg<String> warpName(PortalsPlugin p) {
        return Arg.word("warp").suggests((ctx, prefix) -> p.gates == null ? List.of() : p.gates.warpNames(ctx.player()).stream().filter(n -> n.startsWith(prefix.toLowerCase(Locale.ROOT))).toList());
    }
    private static CommandSpec.Builder gateHome(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(homeName(p).optional()).executes(ctx -> p.gates(g -> g.home(ctx.player(), ctx.input("home")), ctx.player()));
    }
    private static CommandSpec.Builder gateSetHome(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(Arg.word("home").optional()).executes(ctx -> p.gates(g -> g.setHome(ctx.player(), ctx.input("home")), ctx.player()));
    }
    private static CommandSpec.Builder gateDelHome(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(homeName(p)).executes(ctx -> p.gates(g -> g.deleteHome(ctx.player(), ctx.get("home")), ctx.player()));
    }
    private static CommandSpec.Builder gateWarp(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(warpName(p)).executes(ctx -> p.gates(g -> g.warp(ctx.player(), ctx.get("warp")), ctx.player()));
    }
    private static CommandSpec.Builder gateSetWarp(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(Arg.word("warp")).executes(ctx -> p.gates(g -> g.setWarp(ctx.player(), ctx.get("warp")), ctx.player()));
    }
    private static CommandSpec.Builder gateDelWarp(PortalsPlugin p, CommandSpec.Builder s) {
        return s.playerOnly().arg(warpName(p)).executes(ctx -> p.gates(g -> g.deleteWarp(ctx.player(), ctx.get("warp")), ctx.player()));
    }
    /** Short root commands /home, /sethome, /delhome, /homes, /warp, /setwarp, /delwarp, /warps (travel-gates.short-commands). */
    static List<CommandSpec> gateCommands(PortalsPlugin p) {
        return List.of(
                gateHome(p, CommandSpec.command("home")).build(),
                gateSetHome(p, CommandSpec.command("sethome")).build(),
                gateDelHome(p, CommandSpec.command("delhome")).build(),
                CommandSpec.command("homes").playerOnly().executes(ctx -> p.gates(g -> g.homes(ctx.player()), ctx.player())).build(),
                gateWarp(p, CommandSpec.command("warp")).build(),
                gateSetWarp(p, CommandSpec.command("setwarp")).build(),
                gateDelWarp(p, CommandSpec.command("delwarp")).build(),
                CommandSpec.command("warps").playerOnly().executes(ctx -> p.gates(g -> g.warps(ctx.player()), ctx.player())).build());
    }
    private static Arg<String> portal(PortalsPlugin p, String action) {
        return Arg.word("name").suggests((ctx, prefix) -> p.complete(ctx.player(), action, prefix));
    }
    private static Arg<String> trustee(PortalsPlugin p, String action) {
        return Arg.word("player").suggests((ctx, prefix) -> p.complete(ctx.player(), action, ctx.input("name"), prefix));
    }
    /** The English argument list without the optional values left out. */
    private static String[] args(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).toArray(String[]::new);
    }
}
