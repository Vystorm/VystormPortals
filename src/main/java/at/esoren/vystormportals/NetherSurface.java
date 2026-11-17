package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;

/** A real, loaded, rectangular vanilla Nether portal. Detection never loads chunks. */
record NetherSurface(ViewFrame frame, Axis axis, Set<BlockPosition> cells) {
    static NetherSurface find(Location at, int radius) {
        World world = at.getWorld();
        NetherSurface nearest = null;
        double distance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            for (int y = -3; y <= 3; y++) {
                BlockPosition p = new BlockPosition(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
                if (!portal(world, p, null)) continue;
                double d = p.centre().distanceSquared(point(at));
                if (d >= distance) continue;
                NetherSurface found = detect(world, p);
                if (found != null) { nearest = found; distance = d; }
            }
        return nearest;
    }

    static NetherSurface detect(World world, BlockPosition start) {
        if (!portal(world, start, null)) return null;
        Axis axis = ((Orientable) world.getBlockAt(start.x(), start.y(), start.z()).getBlockData()).getAxis();
        if (axis == Axis.Y) return null;
        int dx = axis == Axis.X ? 1 : 0, dz = axis == Axis.Z ? 1 : 0;
        BlockPosition bottom = start;
        for (int i = 0; i < 21 && portal(world, offset(bottom, 0, -1, 0), axis); i++) bottom = offset(bottom, 0, -1, 0);
        if (portal(world, offset(bottom, 0, -1, 0), axis)) return null;
        for (int i = 0; i < 21 && portal(world, offset(bottom, -dx, 0, -dz), axis); i++) bottom = offset(bottom, -dx, 0, -dz);
        if (portal(world, offset(bottom, -dx, 0, -dz), axis)) return null;
        int width = 0, height = 0;
        while (width <= 21 && portal(world, offset(bottom, width * dx, 0, width * dz), axis)) width++;
        while (height <= 21 && portal(world, offset(bottom, 0, height, 0), axis)) height++;
        if (width < 2 || width > 21 || height < 3 || height > 21) return null;
        Set<BlockPosition> cells = new HashSet<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) {
            BlockPosition p = offset(bottom, x * dx, y, x * dz);
            if (!portal(world, p, axis)) return null;
            cells.add(p);
        }
        Point3 origin = new Point3(bottom.x() + (axis == Axis.X ? width / 2.0 : .5), bottom.y(),
                bottom.z() + (axis == Axis.Z ? width / 2.0 : .5));
        return new NetherSurface(new ViewFrame(world.getUID(), origin, axis == Axis.X ? 0 : 1, width / 2.0, height), axis, Set.copyOf(cells));
    }

    boolean intact(World world) { return cells.stream().allMatch(p -> portal(world, p, axis)); }
    static Point3 point(Location at) { return new Point3(at.getX(), at.getY(), at.getZ()); }
    private static BlockPosition offset(BlockPosition p, int x, int y, int z) { return new BlockPosition(p.x() + x, p.y() + y, p.z() + z); }
    private static boolean portal(World world, BlockPosition p, Axis axis) {
        if (world == null || p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight() || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return false;
        var block = world.getBlockAt(p.x(), p.y(), p.z());
        return block.getType() == Material.NETHER_PORTAL && block.getBlockData() instanceof Orientable data
                && (axis == null || data.getAxis() == axis);
    }
}
