package at.esoren.vystormportals;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** One preparation at a time, four async requests in flight, <=400 tickets, no off-thread world access. */
final class PortalPreparation implements AutoCloseable {
    record ChunkKey(int x, int z) {}
    private final Plugin plugin;
    private final World world;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    private final DestinationChunks leases;
    private final ArrayDeque<ChunkKey> queue;
    private final Set<ChunkKey> tickets = new HashSet<>();
    private int running;
    private boolean closed, failed;

    PortalPreparation(Plugin plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface platform, World world, int x, int z, int radius, DestinationChunks leases) {
        this.plugin = plugin; this.platform = platform; this.world = world;
        this.leases = leases;
        queue = new ArrayDeque<>(area(x, z, radius));
    }

    static List<ChunkKey> area(int x, int z, int radius) {
        if (radius < 0 || radius > 128) throw new IllegalArgumentException("Preparation radius out of bounds");
        var result = new java.util.ArrayList<ChunkKey>();
        // One guard chunk around Paper's lookup/creation area prevents synchronous border reads.
        for (int cx = ((x - radius) >> 4) - 1; cx <= ((x + radius) >> 4) + 1; cx++)
            for (int cz = ((z - radius) >> 4) - 1; cz <= ((z + radius) >> 4) + 1; cz++) result.add(new ChunkKey(cx, cz));
        if (result.size() > 400) throw new IllegalArgumentException("Preparation chunk budget exceeded");
        return List.copyOf(result);
    }

    void tick() {
        if (closed || failed) return;
        if (plugin.getServer().getWorld(world.getUID()) != world) { failed = true; return; }
        while (!queue.isEmpty()) {
            ChunkKey key = queue.peekFirst();
            // Already loaded chunks need no async round trip or four-per-tick throttle.
            if (world.isChunkLoaded(key.x, key.z)) {
                queue.removeFirst(); leases.hold(world,key.x,key.z); tickets.add(key); continue;
            }
            if (running >= 4) break;
            queue.removeFirst(); running++;
            try {
                platform.getChunkAtAsync(world, key.x, key.z, true).whenComplete((chunk, error) -> {
                    if (!plugin.isEnabled()) return;
                    try { plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (closed) return;
                        running--;
                        if (error != null || chunk == null || plugin.getServer().getWorld(world.getUID()) != world
                                || !world.isChunkLoaded(key.x, key.z)) { failed = true; return; }
                        leases.hold(world,key.x,key.z);tickets.add(key);
                    }); } catch (org.bukkit.plugin.IllegalPluginAccessException stopping) { /* plugin disabled */ }
                });
            } catch (RuntimeException failure) { running--; failed = true; break; }
        }
    }

    boolean ready() { return !closed && !failed && running == 0 && queue.isEmpty(); }
    boolean failed() { return failed; }
    @Override public void close() {
        closed = true; queue.clear();
        for (ChunkKey key : tickets) leases.release(world,key.x,key.z);
        tickets.clear();
    }
}
