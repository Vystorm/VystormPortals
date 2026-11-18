package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Visual-only native End support. Minecraft retains all portal/respawn/credits transitions. */
final class NativeEndViews implements AutoCloseable {
    record Surface(HorizontalFrame frame, Set<BlockPosition> cells) {}
    private record Search(long expires, Surface surface) {}
    private final JavaPlugin plugin;
    private final DestinationChunks chunks;
    private final EndArrivalPlatform platform;
    private final Map<UUID, PortalPreview> views = new HashMap<>();
    private final Map<UUID, Search> searches = new HashMap<>();
    private final Vystorm.vystorm_core.API core;
    NativeEndViews(JavaPlugin plugin, Vystorm.vystorm_core.API core, DestinationChunks chunks) {
        this.core = core;
        this.plugin = plugin;
        this.chunks = chunks;
        platform = new EndArrivalPlatform(plugin);
    }

    boolean update(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!plugin.getConfig().getBoolean("end-portals.enabled", true) || !player.hasPermission("vystormportals.use")
                || !player.hasPermission("vystormportals.end-view") || player.isDead()
                || (player.getWorld().getEnvironment() == World.Environment.THE_END && !plugin.getConfig().getBoolean("end-portals.return-view", true))) {
            close(id);
            return false;
        }
        Search cached = searches.get(id);
        if (cached == null || tick >= cached.expires) {
            cached = new Search(tick + 20, find(player));
            searches.put(id, cached);
        }
        Surface surface = cached.surface;
        if (surface == null || !surface.frame.world().equals(player.getWorld().getUID()) || !intact(player.getWorld(), surface)) {
            stopView(id);
            return false;
        }
        Point3 eye = point(player.getEyeLocation());
        if (eye.y() <= surface.frame.origin().y() || eye.distanceSquared(surface.frame.origin()) > 100) {
            stopView(id);
            return false;
        }
        Location destination = destination(player, tick);
        if (destination == null) {
            stopView(id);
            return false;
        }
        // A plane just above arrival height keeps the actual landing floor visible from the rim.
        Point3 target = new Point3(destination.getX(), Math.floor(destination.getY()) + .75, destination.getZ());
        chunks.touch(destination.getWorld().getUID(), target, tick, plugin.getConfig().getBoolean("end-portals.generate-destination-chunks", true));
        if (player.getWorld().getEnvironment() != World.Environment.THE_END) platform.prepare(destination.getWorld(), player);
        HorizontalFrame targetFrame = new HorizontalFrame(destination.getWorld().getUID(), target, surface.frame.halfWidth(), surface.frame.halfDepth());
        PortalPreview view = views.get(id);
        if (view == null || view.isClosed()) {
            try {
                view = new PortalPreview(core, plugin, player);
                views.put(id, view);
            } catch (IllegalStateException busy) {
                return false;
            }
        }
        if (!view.horizontal(surface.frame, targetFrame, eye, surface.cells, tick)) stopView(id);
        return true;
    }
    private Location destination(Player player, long tick) {
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            Location raw = PortalApi.INSTANCE.respawn(player, false);
            if (raw != null && raw.getWorld() != null) {
                chunks.touch(raw.getWorld().getUID(), point(raw), tick, plugin.getConfig().getBoolean("end-portals.generate-destination-chunks", true));
                int cx = raw.getBlockX()>>4, cz = raw.getBlockZ()>>4;
                boolean ready = true;
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) ready &= raw.getWorld().isChunkLoaded(cx + x, cz + z);
                if (!ready) return null; // keep the native surface until asynchronous preparation completes
                Location respawn = PortalApi.INSTANCE.respawn(player, true);
                if (respawn != null && respawn.getWorld() != null) return respawn;
            }
            World overworld = dimension(player.getWorld(), World.Environment.NORMAL, "overworld");
            return overworld == null ? null : overworld.getSpawnLocation();
        }
        World end = dimension(player.getWorld(), World.Environment.THE_END, "end");
        return end == null ? null : new Location(end, 100.5, 49, .5);
    }
    private World dimension(World source, World.Environment environment, String key) {
        return NativeWorlds.destination(source, environment, plugin.getServer().getWorlds(),
                !PortalSwitches.on(plugin.getConfig(), PortalSwitches.WORLD_LINKS) ? null : plugin.getConfig().getString("world-links." + source.getUID() + "." + key));
    }
    private static Point3 point(Location location) {
        return new Point3(location.getX(), location.getY(), location.getZ());
    }
    private static boolean loaded(World world, BlockPosition p) {
        return p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight() && world.isChunkLoaded(p.x()>>4, p.z()>>4);
    }
    private static boolean portal(World world, BlockPosition p) {
        return loaded(world, p) && world.getBlockAt(p.x(), p.y(), p.z()).getType() == Material.END_PORTAL;
    }
    private static boolean intact(World world, Surface surface) {
        for (BlockPosition p : surface.cells) if (!portal(world, p)) return false;
        return true;
    }
    private static Surface find(Player player) {
        World world = player.getWorld();
        Location at = player.getLocation();
        BlockPosition nearest = null;
        double best = Double.MAX_VALUE;
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -4; y <= 1; y++) {
            BlockPosition p = new BlockPosition(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
            if (!portal(world, p)) continue;
            double distance = p.centre().distanceSquared(point(at));
            if (distance < best) {
                best = distance;
                nearest = p;
            }
        }
        return nearest == null ? null : detect(world, nearest);
    }
    /** Connected portal cells preserve the central bedrock pillar of an End exit fountain. */
    static Surface detect(World world, BlockPosition start) {
        if (!portal(world, start)) return null;
        Set<BlockPosition> cells = new HashSet<>();
        ArrayDeque<BlockPosition> queue = new ArrayDeque<>();
        queue.add(start);
        int minX = start.x(), maxX = start.x(), minZ = start.z(), maxZ = start.z();
        while (!queue.isEmpty()) {
            BlockPosition p = queue.removeFirst();
            if (cells.contains(p) || !portal(world, p)) continue;
            if (cells.size() >= 64 || Math.abs(p.x() - start.x()) > 8 || Math.abs(p.z() - start.z()) > 8) return null;
            cells.add(p);
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            queue.add(new BlockPosition(p.x() + 1, p.y(), p.z()));
            queue.add(new BlockPosition(p.x() - 1, p.y(), p.z()));
            queue.add(new BlockPosition(p.x(), p.y(), p.z() + 1));
            queue.add(new BlockPosition(p.x(), p.y(), p.z() - 1));
        }
        Point3 centre = new Point3((minX + maxX + 1) / 2.0, start.y() + .75, (minZ + maxZ + 1) / 2.0);
        return new Surface(new HorizontalFrame(world.getUID(), centre, (maxX - minX + 1) / 2.0, (maxZ - minZ + 1) / 2.0), Set.copyOf(cells));
    }
    boolean covers(UUID player, BlockPosition position) {
        PortalPreview view = views.get(player);
        return view != null && view.covers(position);
    }
    private void stopView(UUID player) {
        PortalPreview view = views.remove(player);
        if (view != null) view.close();
    }
    void close(UUID player) {
        stopView(player);
        searches.remove(player);
    }
    @Override public void close() {
        for (UUID id : List.copyOf(views.keySet())) stopView(id);
        searches.clear();
    }
}
