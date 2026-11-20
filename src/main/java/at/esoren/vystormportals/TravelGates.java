package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import Vystorm.vystorm_core.visual.Visuals;
import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * Travel gates (1.21.0): {@code /home} and {@code /warp} open a door-sized 1x2x1 portal (in the wall ahead, else free standing) that shows
 * the destination through its near side. Walking into the space travels; after {@code lifetime-seconds} (default 10) the
 * gate breaks down. Home gates are only for their opener, warp gates also take everybody who may use the warp along.
 * Optional portal energy limits how often and how far players travel. Gates exist only in memory. Server thread.
 */
final class TravelGates {
    enum Kind { HOME, WARP }

    /** travel-gates.* settings. */
    record Settings(boolean enabled, int lifetimeTicks, int maxHomes, boolean shareWarps, int cooldownTicks) {
        static Settings read(ConfigurationSection c) {
            return new Settings(c.getBoolean("travel-gates.enabled", true),
                    Math.clamp(c.getInt("travel-gates.lifetime-seconds", 10), 3, 60) * 20,
                    Math.clamp(c.getInt("travel-gates.homes.max", 3), 0, 100),
                    c.getBoolean("travel-gates.warps.take-others-along", true),
                    Math.clamp(c.getInt("travel-gates.cooldown-seconds", 3), 0, 3600) * 20);
        }
    }

    static final class Gate {
        final TravelGate shape; final UUID opener; final Kind kind; final String name; final TravelPoints.Point target;
        final ViewFrame exit; final long expires; final double cost; boolean used;
        final Map<UUID, PortalPreview> views = new HashMap<>();
        Gate(TravelGate shape, UUID opener, Kind kind, String name, TravelPoints.Point target, long expires, double cost) {
            this.shape = shape; this.opener = opener; this.kind = kind; this.name = name; this.target = target;
            this.expires = expires; this.cost = cost;
            exit = TravelGate.exit(target.world(), target.x(), target.y(), target.z(), target.yaw());
        }
    }

    static final double VIEW_RANGE = 12;
    private final PortalsPlugin plugin;
    private final DestinationChunks chunks;
    private final TravelPoints points;
    private final PortalEnergy energy;
    private final LongSupplier clock;
    private Settings settings;
    private final Map<UUID, Gate> gates = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Long> blocked = new HashMap<>();
    private long tick;

    TravelGates(PortalsPlugin plugin, DestinationChunks chunks, LongSupplier clock) {
        this.plugin = plugin; this.chunks = chunks; this.clock = clock;
        settings = Settings.read(plugin.getConfig());
        points = new TravelPoints(plugin.getDataFolder().toPath().resolve("travel.yml"), plugin.core().data().files());
        energy = new PortalEnergy(plugin.getDataFolder().toPath().resolve("energy.yml"), PortalEnergy.Rules.read(plugin.getConfig()), plugin.core().data().files());
    }

    boolean enabled() { return settings.enabled(); }

    /** The reload hook (1.24.0): new settings and energy rules; open gates finish their lifetime. */
    void reload() {
        settings = Settings.read(plugin.getConfig());
        energy.rules(PortalEnergy.Rules.read(plugin.getConfig()));
    }
    TravelPoints points() { return points; }
    PortalEnergy.Rules energyRules() { return energy.rules(); }
    /** Current portal energy of a player (recharge computed on the fly). Server thread. */
    double energyNow(UUID player) { return energy.current(player, clock.getAsLong()); }
    Collection<Gate> gates() { return Collections.unmodifiableCollection(gates.values()); }

    /** Homes a player may have: the highest vystormportals.homes.N permission (1..100), else travel-gates.homes.max. */
    int maxHomes(Player player) {
        int best = settings.maxHomes();
        for (int n = 100; n > best; n--) if (player.hasPermission("vystormportals.homes." + n)) return n;
        return best;
    }

    // ---- commands -------------------------------------------------------------------------------------------------

    void home(Player player, String name) {
        if (!check(player, "vystormportals.home")) return;
        SortedMap<String, TravelPoints.Point> mine = points.homes(player.getUniqueId());
        if (mine.isEmpty()) { plugin.say(player, "gate.no-homes"); return; }
        String key = name == null ? (mine.containsKey("home") ? "home" : mine.firstKey()) : name.toLowerCase(Locale.ROOT);
        TravelPoints.Point point = mine.get(key);
        if (point == null) { plugin.say(player, "gate.unknown-home", key); return; }
        open(player, Kind.HOME, key, point);
    }

    void setHome(Player player, String name) {
        if (!check(player, "vystormportals.home")) return;
        String key = name == null ? "home" : name.toLowerCase(Locale.ROOT);
        if (!TravelPoints.validName(key)) { plugin.say(player, "gate.invalid-name"); return; }
        SortedMap<String, TravelPoints.Point> mine = points.homes(player.getUniqueId());
        int max = maxHomes(player);
        if (!mine.containsKey(key) && mine.size() >= max) { plugin.say(player, "gate.home-limit", max); return; }
        try { points.setHome(player.getUniqueId(), key, here(player)); plugin.say(player, "gate.home-set", key); }
        catch (IOException failure) { plugin.say(player, "failed"); plugin.getLogger().warning("Cannot save travel.yml: " + failure.getMessage()); }
    }

    void deleteHome(Player player, String name) {
        if (!check(player, "vystormportals.home")) return;
        try {
            if (points.deleteHome(player.getUniqueId(), name.toLowerCase(Locale.ROOT))) plugin.say(player, "gate.home-deleted", name);
            else plugin.say(player, "gate.unknown-home", name);
        } catch (IOException failure) { plugin.say(player, "failed"); }
    }

    void homes(Player player) {
        if (!check(player, "vystormportals.home")) return;
        Set<String> mine = points.homes(player.getUniqueId()).keySet();
        if (mine.isEmpty()) plugin.say(player, "gate.no-homes");
        else plugin.say(player, "gate.homes", String.join(", ", mine), mine.size(), maxHomes(player));
    }

    void warp(Player player, String name) {
        if (!check(player, "vystormportals.warp")) return;
        String key = name.toLowerCase(Locale.ROOT);
        TravelPoints.Point point = points.warps().get(key);
        if (point == null || !mayUseWarp(player, key)) { plugin.say(player, "gate.unknown-warp", key); return; }
        open(player, Kind.WARP, key, point);
    }

    boolean mayUseWarp(Player player, String name) {
        return player.hasPermission("vystormportals.warp") && (player.hasPermission("vystormportals.warp.*")
                || player.hasPermission("vystormportals.warp." + name) || !permissionPerWarp());
    }

    private boolean permissionPerWarp() { return plugin.getConfig().getBoolean("travel-gates.warps.permission-per-warp", false); }

    void setWarp(Player player, String name) {
        if (!check(player, "vystormportals.warp.manage")) return;
        String key = name.toLowerCase(Locale.ROOT);
        if (!TravelPoints.validName(key)) { plugin.say(player, "gate.invalid-name"); return; }
        try { points.setWarp(key, here(player)); plugin.say(player, "gate.warp-set", key); }
        catch (IOException failure) { plugin.say(player, "failed"); }
    }

    void deleteWarp(Player player, String name) {
        if (!check(player, "vystormportals.warp.manage")) return;
        try {
            if (points.deleteWarp(name.toLowerCase(Locale.ROOT))) plugin.say(player, "gate.warp-deleted", name);
            else plugin.say(player, "gate.unknown-warp", name);
        } catch (IOException failure) { plugin.say(player, "failed"); }
    }

    void warps(Player player) {
        if (!check(player, "vystormportals.warp")) return;
        List<String> list = points.warps().keySet().stream().filter(w -> mayUseWarp(player, w)).toList();
        if (list.isEmpty()) plugin.say(player, "gate.no-warps");
        else plugin.say(player, "gate.warps", String.join(", ", list));
    }

    void energy(Player player) {
        PortalEnergy.Rules rules = energy.rules();
        if (!rules.enabled()) { plugin.say(player, PortalSwitches.OFF); return; }
        if (!settings.enabled()) { plugin.needs(player, PortalSwitches.TRAVEL_GATES); return; }
        double value = energy.current(player.getUniqueId(), clock.getAsLong());
        long full = rules.secondsUntil(value, rules.max());
        plugin.say(player, "gate.energy", (long) Math.floor(value), (long) rules.max(), time(full));
    }

    List<String> homeNames(Player player) { return List.copyOf(points.homes(player.getUniqueId()).keySet()); }
    List<String> warpNames(Player player) { return points.warps().keySet().stream().filter(w -> mayUseWarp(player, w)).toList(); }

    private boolean check(Player player, String permission) {
        // travel-gates.homes.enabled / travel-gates.warps.enabled (1.24.0): homes and warps can be switched apart
        String part = permission.startsWith("vystormportals.home") ? PortalSwitches.HOMES : PortalSwitches.WARPS;
        if (!PortalSwitches.on(plugin.getConfig(), part)) { plugin.say(player, PortalSwitches.OFF); return false; }
        if (!settings.enabled()) { plugin.needs(player, PortalSwitches.TRAVEL_GATES); return false; }
        if (!player.hasPermission(permission)) { plugin.say(player, "denied"); return false; }
        return true;
    }

    private static TravelPoints.Point here(Player player) {
        Location l = player.getLocation();
        return new TravelPoints.Point(player.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    static String time(long seconds) {
        if (seconds < 0) return "-";
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    // ---- opening --------------------------------------------------------------------------------------------------

    /** Opens a gate for the player; replaces their previous gate. */
    void open(Player player, Kind kind, String name, TravelPoints.Point target) {
        World destination = plugin.getServer().getWorld(target.world());
        if (destination == null) { plugin.say(player, "unavailable"); return; }
        if (player.isInsideVehicle()) { plugin.say(player, "gate.vehicle"); return; }
        long wait = cooldown.getOrDefault(player.getUniqueId(), 0L) - tick;
        if (wait > 0 && !player.hasPermission("vystormportals.energy.bypass")) { plugin.say(player, "gate.cooldown", (wait + 19) / 20); return; }
        TravelGate shape = place(player);
        if (shape == null) { plugin.say(player, "gate.no-room"); return; }
        Gate previous = gates.get(player.getUniqueId());
        if (previous != null && !previous.used && previous.cost > 0) {   // replacing an unused gate gives its energy back first
            try { energy.refund(player.getUniqueId(), previous.cost, clock.getAsLong()); }
            catch (IOException failure) { plugin.getLogger().warning("Cannot save energy.yml: " + failure.getMessage()); }
        }
        close(player.getUniqueId(), false);
        double cost = 0;
        PortalEnergy.Rules rules = energy.rules();
        if (rules.enabled() && !player.hasPermission("vystormportals.energy.bypass")) {
            double distance = destination.equals(player.getWorld())
                    ? Math.sqrt(shape.centre().distanceSquared(new Point3(target.x(), target.y(), target.z()))) : -1;
            cost = rules.cost(distance);
            long now = clock.getAsLong();
            try {
                if (!energy.take(player.getUniqueId(), cost, now)) {
                    double have = energy.current(player.getUniqueId(), now);
                    plugin.say(player, "gate.no-energy", (long) cost, (long) Math.floor(have), time(rules.secondsUntil(have, cost)));
                    return;
                }
            } catch (IOException failure) { plugin.say(player, "failed"); return; }
            showEnergy(player, cost);
        }
        Gate gate = new Gate(shape, player.getUniqueId(), kind, name, target, tick + settings.lifetimeTicks(), cost);
        gates.put(player.getUniqueId(), gate);
        cooldown.put(player.getUniqueId(), tick + settings.cooldownTicks());
        chunks.touch(gate.exit, tick);
        Point3 c = shape.centre();
        Location at = new Location(player.getWorld(), c.x(), c.y(), c.z());
        player.getWorld().playSound(at, "minecraft:block.beacon.activate", 0.6f, 1.6f);
        player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 60, 0.6, 0.6, 0.6, 0.05);
        plugin.say(player, kind == Kind.HOME ? "gate.opened-home" : "gate.opened-warp", name, settings.lifetimeTicks() / 20);
    }

    /**
     * Where the 1x2x1 gate forms: in the wall ahead if there is one, else free standing (see {@link TravelGate#place}).
     * Feet height is rounded up, so slabs, paths and farmland count as the floor.
     */
    TravelGate place(Player player) {
        Location l = player.getLocation();
        World world = player.getWorld();
        return TravelGate.place(world.getUID(), l.getBlockX(), (int) Math.ceil(l.getY() - 0.01), l.getBlockZ(), TravelGate.facing(l.getYaw()), probe(world));
    }

    static TravelGate.Probe probe(World world) {
        return new TravelGate.Probe() {
            private Block at(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
                return world.getBlockAt(x, y, z);
            }
            @Override public boolean wall(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.getType().isOccluding() && !(b.getState() instanceof org.bukkit.block.TileState);   // no furnaces or other block entities
            }
            @Override public boolean free(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.isPassable() && !b.isLiquid() && b.getType() != Material.POWDER_SNOW;
            }
            @Override public boolean floor(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.getType().isSolid();
            }
        };
    }

    private void showEnergy(Player player, double cost) {
        PortalEnergy.Rules rules = energy.rules();
        double value = energy.current(player.getUniqueId(), clock.getAsLong());
        try {
            plugin.core().gui().huds().of(plugin).show(player, Vystorm.vystorm_core.screen.HudCard.of(Material.ENDER_PEARL,
                    plugin.plain(player, "gate.energy-title"), (long) Math.floor(value) + "/" + (long) rules.max(),
                    value / rules.max(), "-" + (long) cost).withAccent(Vystorm.vystorm_core.screen.HudCard.Accent.PURPLE), 60);
            return;
        } catch (RuntimeException hudUnavailable) { /* chat line instead */ }
        plugin.say(player, "gate.energy-used", (long) cost, (long) Math.floor(value), (long) rules.max());
    }

    // ---- runtime --------------------------------------------------------------------------------------------------

    /** Every tick: expiry, outline particles, destination chunks. */
    void tick(long now) {
        tick = now;
        for (Gate gate : List.copyOf(gates.values())) {
            World world = plugin.getServer().getWorld(gate.shape.world());
            if (world == null || now >= gate.expires) { breakDown(gate, world); continue; }
            chunks.touch(gate.exit, now);
            long left = gate.expires - now;
            if (now % 5 == 0 || (left < 60 && now % 2 == 0)) {
                Particle particle = left < 60 ? Particle.SMOKE : Particle.PORTAL;
                for (Player viewer : world.getPlayers()) {
                    if (viewer.getLocation().toVector().distanceSquared(toVector(gate.shape.centre())) > 48 * 48) continue;
                    plugin.core().particle().visuals().particles(viewer, particle, gate.shape.edges(5), null);
                }
            }
            if (left % 20 == 0) {
                Player opener = plugin.getServer().getPlayer(gate.opener);
                if (opener != null && left <= 60) plugin.actionBar(opener, "gate.closing", left / 20);
            }
        }
        if (now % 200 == 0) { cooldown.entrySet().removeIf(e -> e.getValue() <= now); blocked.entrySet().removeIf(e -> e.getValue() <= now); }
    }

    private static Vector toVector(Point3 p) { return new Vector(p.x(), p.y(), p.z()); }

    private void breakDown(Gate gate, World world) {
        gates.remove(gate.opener, gate);
        gate.views.values().forEach(PortalPreview::close);
        gate.views.clear();
        if (world != null) {
            Point3 c = gate.shape.centre();
            Location at = new Location(world, c.x(), c.y(), c.z());
            world.spawnParticle(Particle.SMOKE, at, 40, 0.6, 0.6, 0.6, 0.02);
            world.spawnParticle(Particle.REVERSE_PORTAL, at, 40, 0.5, 0.5, 0.5, 0.2);
            world.playSound(at, "minecraft:block.respawn_anchor.deplete", 0.7f, 1.2f);
        }
        if (!gate.used && gate.cost > 0) {
            try { energy.refund(gate.opener, gate.cost, clock.getAsLong()); }
            catch (IOException failure) { plugin.getLogger().warning("Cannot save energy.yml: " + failure.getMessage()); }
            Player opener = plugin.getServer().getPlayer(gate.opener);
            if (opener != null) plugin.say(opener, "gate.refunded", (long) gate.cost);
        }
    }

    /** Closes the player's own gate (on quit or a new gate). */
    void close(UUID opener, boolean effects) {
        Gate gate = gates.get(opener);
        if (gate == null) return;
        if (effects) breakDown(gate, plugin.getServer().getWorld(gate.shape.world()));
        else { gates.remove(opener); gate.views.values().forEach(PortalPreview::close); gate.views.clear(); }
    }

    boolean mayEnter(Player player, Gate gate) {
        if (player.getUniqueId().equals(gate.opener)) return true;
        return gate.kind == Kind.WARP && settings.shareWarps() && mayUseWarp(player, gate.name);
    }

    /** The nearest gate the player may use and sees from the front (within {@link #VIEW_RANGE}), or null. */
    Gate nearest(Player player, Point3 eye) {
        Gate best = null; double bestDistance = VIEW_RANGE * VIEW_RANGE;
        UUID world = player.getWorld().getUID();
        for (Gate gate : gates.values()) {
            if (!gate.shape.world().equals(world) || !mayEnter(player, gate)) continue;
            ViewFrame entry = gate.shape.entry();
            if (entry.local(eye).z() <= .05) continue;   // only from the front
            double d = eye.distanceSquared(entry.origin());
            if (d < bestDistance) { bestDistance = d; best = gate; }
        }
        return best;
    }

    /**
     * Shows {@code gate}'s window to the player (the caller has closed the player's other portal views first) and
     * releases the player's views of other gates.
     */
    void show(Player player, Gate gate, Point3 eye, long now) {
        for (Gate other : gates.values()) if (other != gate) release(other, player.getUniqueId());
        PortalPreview preview = gate.views.get(player.getUniqueId());
        if (preview == null || preview.isClosed()) {
            try { preview = new PortalPreview(plugin.core(), plugin, player, Material.CRYING_OBSIDIAN); gate.views.put(player.getUniqueId(), preview); }
            catch (IllegalStateException busy) { return; }
        }
        try { preview.upright(gate.shape.entry(), gate.exit, eye, gate.shape.inWall() ? Set.copyOf(gate.shape.blocks()) : Set.of(), now); }
        catch (RuntimeException failure) { release(gate, player.getUniqueId()); plugin.getLogger().warning("Gate view failed: " + failure.getMessage()); }
    }

    private static void release(Gate gate, UUID viewer) {
        PortalPreview preview = gate.views.remove(viewer);
        if (preview != null) preview.close();
    }

    /** Releases every gate view of a viewer (teleport, quit, world change). */
    void release(UUID viewer) { for (Gate gate : gates.values()) release(gate, viewer); }

    boolean covers(UUID viewer, BlockPosition position) {
        for (Gate gate : gates.values()) {
            PortalPreview preview = gate.views.get(viewer);
            if (preview != null && preview.covers(position)) return true;
        }
        return false;
    }

    /** A move into a gate: travels and returns true. */
    boolean enter(Player player, Location to) {
        if (gates.isEmpty() || blocked.getOrDefault(player.getUniqueId(), 0L) > tick) return false;
        UUID world = player.getWorld().getUID();
        for (Gate gate : List.copyOf(gates.values())) {
            if (!gate.shape.world().equals(world) || !gate.shape.contains(to.getX(), to.getY(), to.getZ()) || !mayEnter(player, gate)) continue;
            if (travel(player, gate)) return true;
            blocked.put(player.getUniqueId(), tick + 40);   // one message per 2 s while standing in a gate that cannot travel
            return false;
        }
        return false;
    }

    private boolean travel(Player player, Gate gate) {
        World world = plugin.getServer().getWorld(gate.target.world());
        if (world == null) { plugin.say(player, "unavailable"); return false; }
        Location location = new Location(world, gate.target.x(), gate.target.y(), gate.target.z(), gate.target.yaw(), gate.target.pitch());
        world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (!plugin.safe(location, player.getHeight())) { plugin.say(player, "unsafe"); return false; }
        release(player.getUniqueId());
        if (!player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN)) { plugin.say(player, "cancelled"); return false; }
        gate.used = true;
        player.setFallDistance(0);
        world.spawnParticle(Particle.REVERSE_PORTAL, location.clone().add(0, 1, 0), 40, 0.4, 0.7, 0.4, 0.05);
        world.playSound(location, "minecraft:entity.enderman.teleport", 0.5f, 1.3f);
        if (gate.kind == Kind.HOME) close(gate.opener, true);   // nobody else may use it
        return true;
    }

    void shutdown() {
        for (Gate gate : List.copyOf(gates.values())) {
            gate.views.values().forEach(PortalPreview::close);
            if (!gate.used && gate.cost > 0) try { energy.refund(gate.opener, gate.cost, clock.getAsLong()); } catch (IOException ignored) { }
        }
        gates.clear();
    }
}
