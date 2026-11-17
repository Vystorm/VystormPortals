package at.esoren.vystormportals;

import Vystorm.vystorm_core.packet.ClientBiomes;
import Vystorm.vystorm_core.visual.*;
import java.util.*;
import java.util.function.Function;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Per-view sampling cache; every tick drains at most 512 visible/restore changes. */
final class PortalPreview implements AutoCloseable {
    private final Vystorm.vystorm_core.visual.VisualsInterface visuals;
    private final Vystorm.vystorm_core.packet.ClientBiomesInterface biomeViews;
    static final int BLOCK_BUDGET = 512;
    static final int VIEW_DEPTH = 16;
    private final Plugin owner;
    private final Player player;
    private final BlockView view;
    private Object entry, exit;
    private Point3 eye;
    private Set<BlockPosition> aperture = Set.of();
    private long sampled = Long.MIN_VALUE;
    private Map<BlockPosition, BlockData> desired = Map.of();
    private final Material rim;

    PortalPreview(Vystorm.vystorm_core.API core, Plugin owner, Player player) { this(core, owner, player, Material.BLACK_CONCRETE); }

    /** {@code rim}: block of the client-only rim around the opening (travel gates use a portal-like frame). */
    PortalPreview(Vystorm.vystorm_core.API core, Plugin owner, Player player, Material rim) {
        this.visuals = core.particle().visuals(); this.biomeViews = core.packages().clientBiomes();
        this.owner = owner; this.player = player; this.rim = rim; view = visuals.open(owner, player);
    }
    boolean isClosed() { return view.isClosed(); }
    boolean covers(BlockPosition p) { return view.covers(p); }

    void upright(ViewFrame entrance, ViewFrame destination, Point3 eye, Set<BlockPosition> opening, long tick) {
        if (resample(entrance, destination, eye, opening, tick)) {
            Set<BlockPosition> border = PortalBorder.upright(entrance, opening);
            Map<BlockPosition, BlockData> blocks = new LinkedHashMap<>();
            var air = Material.AIR.createBlockData();
            // Open the actual aperture first, instead of queueing it behind the terrain.
            opening.stream().sorted(Comparator.comparingDouble(p -> p.centre().distanceSquared(eye)))
                    .forEach(p -> blocks.put(p, air));
            visuals.projection(entrance, destination, eye, 8, VIEW_DEPTH, 4096 - opening.size() - border.size()).forEach((p, data) -> {
                if (!opening.contains(p)) blocks.put(p, data.getMaterial() == Material.NETHER_PORTAL ? air : data);
            });
            addBorder(blocks, border);
            sample(blocks, opening, eye, destination.world(), p -> entrance.through(destination, p));
        }
        view.replacePrioritized(desired, BLOCK_BUDGET);
    }

    boolean horizontal(HorizontalFrame entrance, HorizontalFrame destination, Point3 eye,
            Set<BlockPosition> opening, long tick) {
        if (resample(entrance, destination, eye, opening, tick)) {
            Set<BlockPosition> border = PortalBorder.horizontal(opening);
            var blocks = new LinkedHashMap<>(visuals.projection(entrance, destination, eye, 8, VIEW_DEPTH, 4096 - border.size(), opening));
            if (blocks.values().stream().noneMatch(data -> !data.getMaterial().isAir())) return false;
            addBorder(blocks, border);
            sample(blocks, opening, eye, destination.world(), p -> entrance.through(destination, p));
        }
        view.replacePrioritized(desired, BLOCK_BUDGET);
        return true;
    }

    private void addBorder(Map<BlockPosition, BlockData> blocks, Set<BlockPosition> border) {
        World world = player.getWorld();
        BlockData concrete = rim.createBlockData();
        for (BlockPosition p : border) {
            if (p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight()
                    && world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) blocks.put(p, concrete);
        }
    }

    private boolean resample(Object entry, Object exit, Point3 eye, Set<BlockPosition> aperture, long tick) {
        if (entry.equals(this.entry) && exit.equals(this.exit) && eye.equals(this.eye)
                && aperture.equals(this.aperture) && tick - sampled < 10) return false;
        this.entry = entry; this.exit = exit; this.eye = eye; this.aperture = aperture; sampled = tick;
        return true;
    }

    private void sample(Map<BlockPosition, BlockData> blocks, Set<BlockPosition> opening, Point3 eye,
            UUID target, Function<Point3, Point3> transform) {
        desired = prioritize(blocks, opening, eye);
        World world = Bukkit.getWorld(target);
        Map<BlockPosition, NamespacedKey> biomes = new LinkedHashMap<>();
        if (world != null) for (BlockPosition p : desired.keySet()) {
            BlockPosition quart = new BlockPosition(p.x() & ~3, p.y() & ~3, p.z() & ~3);
            if (biomes.containsKey(quart)) continue;
            BlockPosition sample = BlockPosition.containing(transform.apply(new Point3(quart.x() + 2, quart.y() + 2, quart.z() + 2)));
            if (sample.y() < world.getMinHeight() || sample.y() >= world.getMaxHeight()
                    || !world.isChunkLoaded(sample.x() >> 4, sample.z() >> 4)) continue;
            biomes.put(quart, ((Keyed) world.getBiome(sample.x(), sample.y(), sample.z())).getKey());
        }
        biomeViews.project(owner, player, biomes);
    }

    /** Unchanged cells cost no budget in Core, so newly revealed near-side cells win immediately. */
    static Map<BlockPosition, BlockData> prioritize(Map<BlockPosition, BlockData> blocks,
            Set<BlockPosition> opening, Point3 eye) {
        List<BlockPosition> positions = new ArrayList<>(blocks.keySet());
        positions.sort(Comparator.<BlockPosition>comparingInt(p -> opening.contains(p) ? 0 : 1)
                .thenComparingDouble(p -> p.centre().distanceSquared(eye)));
        Map<BlockPosition, BlockData> result = new LinkedHashMap<>();
        for (BlockPosition p : positions) result.put(p, blocks.get(p));
        return result;
    }

    @Override public void close() {
        view.close(); biomeViews.clearProjection(owner, player);
    }
}
