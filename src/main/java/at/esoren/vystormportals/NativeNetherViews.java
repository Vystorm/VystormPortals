package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Automatic native portal previews. All actual dimension transitions remain vanilla-owned. */
final class NativeNetherViews implements Listener, AutoCloseable {
    private record Search(long until, UUID world, BlockPosition position, NetherSurface surface) {}
    private record Request(NetherSurface surface, UUID actor) {}
    private record Job(Request request, NativePortalAccess.Plan plan, PortalPreparation preparation, long started) {}
    private final JavaPlugin plugin;
    private final DestinationChunks chunks;
    private final NativePortalAccess access;
    private final Map<UUID, Search> searches = new HashMap<>();
    private final Map<UUID, PortalPreview> views = new HashMap<>();
    private final Map<ViewFrame, NetherSurface> destinations = new LinkedHashMap<>();
    private final Map<ViewFrame, Request> queue = new LinkedHashMap<>();
    private final Map<ViewFrame, Long> retryAt = new LinkedHashMap<>();
    private Job job;
    private long tick;
    private boolean warned, unavailable;

    private final Vystorm.vystorm_core.API core;
    NativeNetherViews(JavaPlugin plugin, Vystorm.vystorm_core.API core, DestinationChunks chunks) { this.plugin = plugin; this.core = core; this.chunks = chunks; this.access = new NativePortalAccess(core.compat().world()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void created(PortalCreateEvent event) {
        destinations.clear(); searches.clear();
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE || !(event.getEntity() instanceof Player actor)) return;
        // FIRE is emitted before the real portal cells are installed. Recheck cancellation and cells next tick.
        Location at = event.getBlocks().stream().filter(block -> block.getType() == Material.NETHER_PORTAL)
                .map(org.bukkit.block.BlockState::getLocation).findFirst().orElse(null);
        if (at == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !eligible(actor)) return;
            NetherSurface surface = NetherSurface.detect(at.getWorld(), new BlockPosition(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
            if (surface != null) request(surface, actor);
        });
    }

    private boolean eligible(Player player) {
        return !unavailable && plugin.getConfig().getBoolean("nether-portals.enabled", true)
                && player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && player.hasPermission("vystormportals.use") && player.hasPermission("vystormportals.nether-view");
    }

    private void request(NetherSurface source, Player actor) {
        if (destinations.containsKey(source.frame()) || queue.containsKey(source.frame())
                || job != null && job.request.surface.frame().equals(source.frame())
                || tick < retryAt.getOrDefault(source.frame(), 0L) || queue.size() >= 16) return;
        queue.put(source.frame(), new Request(source, actor.getUniqueId()));
    }

    void tick(long tick) {
        this.tick = tick;
        if (job != null) {
            Player actor = plugin.getServer().getPlayer(job.request.actor);
            World origin = plugin.getServer().getWorld(job.request.surface.frame().world());
            if (actor == null || !eligible(actor) || origin == null || !job.request.surface.intact(origin)
                    || tick - job.started > 1200 || job.preparation.failed()) { finish(null); }
            else {
                job.preparation.tick();
                if (job.preparation.ready()) {
                    try {
                        BlockPosition position = access.resolve(job.plan, job.request.surface, actor,
                                plugin.getConfig().getBoolean("nether-portals.prepare-counterpart", true));
                        finish(position == null ? null : NetherSurface.detect(job.plan.world(), position));
                    } catch (ReflectiveOperationException | RuntimeException failure) { warn(failure); finish(null); }
                }
            }
        }
        if (job == null && !queue.isEmpty() && !unavailable) {
            Request request = queue.values().iterator().next(); queue.remove(request.surface.frame());
            Player actor = plugin.getServer().getPlayer(request.actor);
            World origin = plugin.getServer().getWorld(request.surface.frame().world());
            if (actor == null || !eligible(actor) || origin == null || !request.surface.intact(origin)) return;
            World.Environment type = origin.getEnvironment() == World.Environment.NETHER ? World.Environment.NORMAL : World.Environment.NETHER;
            if (origin.getEnvironment() == World.Environment.THE_END) return;
            World target = NativeWorlds.destination(origin, type, plugin.getServer().getWorlds(),
                    plugin.getConfig().getString("world-links." + origin.getUID() + "." + (type == World.Environment.NETHER ? "nether" : "overworld")));
            if (target == null) { retry(request.surface.frame()); return; }
            Point3 p = request.surface.frame().origin();
            try {
                var plan = access.plan(origin, target, new Location(origin, p.x(), p.y(), p.z()));
                int radius = Math.max(plan.searchRadius(), plan.createRadius() + 4);
                job = new Job(request, plan, new PortalPreparation(plugin, core.compat().platform(), target, plan.x(), plan.z(), radius, chunks), tick);
            } catch (ReflectiveOperationException | RuntimeException failure) { warn(failure); retry(request.surface.frame()); }
        }
    }

    private void retry(ViewFrame frame) {
        if (retryAt.size() >= 256) retryAt.remove(retryAt.keySet().iterator().next());
        retryAt.put(frame, tick + 600);
    }

    private void finish(NetherSurface destination) {
        Job finished = job; job = null;
        finished.preparation.close();
        if (destination == null) { retry(finished.request.surface.frame()); return; }
        if (destinations.size() >= 256) destinations.remove(destinations.keySet().iterator().next());
        destinations.put(finished.request.surface.frame(), destination);
        chunks.touch(destination.frame(), tick);
    }

    private void warn(Exception failure) {
        if (failure instanceof ReflectiveOperationException) unavailable = true;
        if (!warned) { warned = true; plugin.getLogger().warning("Native Nether preview unavailable: " + failure + "; vanilla portals remain active."); }
    }

    boolean update(Player player, long tick) {
        UUID id = player.getUniqueId();
        if (!eligible(player)) { close(id); return false; }
        Search search = searches.get(id);
        if (search == null || tick >= search.until || !search.world.equals(player.getWorld().getUID())
                || !search.position.equals(BlockPosition.containing(NetherSurface.point(player.getLocation())))) {
            search = new Search(tick + 20, player.getWorld().getUID(), BlockPosition.containing(NetherSurface.point(player.getLocation())), NetherSurface.find(player.getLocation(), 5)); searches.put(id, search);
        }
        NetherSurface source = search.surface;
        if (source == null || !source.intact(player.getWorld())) { stop(id); return false; }
        Point3 eye = NetherSurface.point(player.getEyeLocation());
        if (eye.distanceSquared(source.frame().origin()) > 144 || Math.abs(source.frame().local(eye).z()) < .05) { stop(id); return true; }
        NetherSurface target = destinations.get(source.frame());
        if (target == null) { request(source, player); stop(id); return true; }
        World targetWorld = plugin.getServer().getWorld(target.frame().world());
        chunks.touch(target.frame(), tick);
        if (targetWorld != null && target.cells().stream().anyMatch(p -> !targetWorld.isChunkLoaded(p.x() >> 4, p.z() >> 4))) {
            stop(id); return true;
        }
        if (targetWorld == null || !target.intact(targetWorld)) {
            destinations.remove(source.frame()); request(source, player); stop(id); return true;
        }
        boolean reversed = source.frame().local(eye).z() < 0;
        ViewFrame entry = reversed ? reverse(source.frame()) : source.frame();
        ViewFrame exit = reversed ? reverse(target.frame()) : target.frame();
        PortalPreview view = views.get(id);
        if (view == null || view.isClosed()) {
            try { view = new PortalPreview(core, plugin, player); views.put(id, view); }
            catch (IllegalStateException busy) { return true; }
        }
        view.upright(entry, exit, eye, source.cells(), tick);
        return true;
    }

    private static ViewFrame reverse(ViewFrame frame) { return new ViewFrame(frame.world(), frame.origin(), frame.quarterTurns() + 2, frame.halfWidth(), frame.height()); }
    boolean covers(UUID player, BlockPosition position) { var view = views.get(player); return view != null && view.covers(position); }
    private void stop(UUID player) { var view = views.remove(player); if (view != null) view.close(); }
    void close(UUID player) { stop(player); searches.remove(player); }
    @Override public void close() {
        if (job != null) { job.preparation.close(); job = null; }
        for (UUID player : List.copyOf(views.keySet())) stop(player);
        searches.clear(); destinations.clear(); queue.clear(); retryAt.clear();
    }
}
