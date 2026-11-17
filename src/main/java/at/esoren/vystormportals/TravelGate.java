package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Geometry of a travel gate (1.21.0): a door-sized 1x2x1 portal for /home and /warp. It forms preferably in the wall in
 * front of the player (the two wall blocks become a client-only window, nothing is broken), otherwise it stands free
 * in front of the player. {@code base} is the lower of the two blocks; {@code facing} is the quarter turn of the
 * player's view (0 = south/+z, 1 = west/-x, 2 = north, 3 = east, as Minecraft yaw / 90). The window's +z side faces the
 * player. Pure and tested without a server.
 */
record TravelGate(UUID world, BlockPosition base, int facing, boolean inWall) {
    static final int HEIGHT = 2;
    /**
     * How far in front of the window plane a player counts as "walking in": half a block before the wall face. The
     * server never lets a player into the real wall, and a walking player skips the last few centimetres before it.
     */
    static final double REACH = 1.3;
    /** Wall gates up to this many blocks ahead. */
    static final int WALL_RANGE = 3;

    TravelGate { facing = Math.floorMod(facing, 4); }

    /** Quarter turn for a yaw in degrees. */
    static int facing(float yaw) { return Math.floorMod(Math.round(yaw / 90f), 4); }

    /** Unit forward vector (x, z) for a quarter turn. */
    static int[] forward(int facing) {
        return switch (Math.floorMod(facing, 4)) { case 0 -> new int[]{0, 1}; case 1 -> new int[]{-1, 0}; case 2 -> new int[]{0, -1}; default -> new int[]{1, 0}; };
    }

    /** What the placement needs to know about blocks (server-free for tests). */
    interface Probe {
        /** A full, solid wall block (opaque cube) a gate may form in. */
        boolean wall(int x, int y, int z);
        /** Passable, no liquid, loaded: a player can stand in it. */
        boolean free(int x, int y, int z);
        /** Solid ground to stand on. */
        boolean floor(int x, int y, int z);
    }

    /**
     * Where the gate forms for a player at feet block {@code x, y, z} looking along {@code facing}: the first wall
     * within {@link #WALL_RANGE} blocks ahead that is two blocks high with free space in front of it, else a free
     * standing gate 2 (then 1) blocks ahead on solid ground, else one block higher (a step), else anywhere free.
     * Null = no room.
     */
    static TravelGate place(UUID world, int x, int y, int z, int facing, Probe probe) {
        int[] f = forward(facing);
        for (int d = 1; d <= WALL_RANGE; d++) {
            int cx = x + f[0] * d, cz = z + f[1] * d;
            if (probe.wall(cx, y, cz) && probe.wall(cx, y + 1, cz)) {
                boolean open = true;
                for (int k = 1; k < d && open; k++) open = probe.free(x + f[0] * k, y, z + f[1] * k) && probe.free(x + f[0] * k, y + 1, z + f[1] * k);
                if (open && probe.free(x + f[0] * (d - 1), y, z + f[1] * (d - 1))) return new TravelGate(world, new BlockPosition(cx, y, cz), facing, true);
                break;   // the first obstacle decides: a wall with something in front of it is no doorway
            }
            if (!probe.free(cx, y, cz) || !probe.free(cx, y + 1, cz)) break;
        }
        TravelGate floating = null;
        for (int raise = 0; raise <= 1; raise++) for (int d : new int[]{2, 1}) {
            int cx = x + f[0] * d, cz = z + f[1] * d, by = y + raise;
            if (!probe.free(cx, by, cz) || !probe.free(cx, by + 1, cz)) continue;
            TravelGate gate = new TravelGate(world, new BlockPosition(cx, by, cz), facing, false);
            if (probe.floor(cx, by - 1, cz)) return gate;
            if (floating == null) floating = gate;
        }
        return floating;
    }

    /** The 2 blocks of the gate. */
    List<BlockPosition> blocks() {
        return List.of(base, new BlockPosition(base.x(), base.y() + 1, base.z()));
    }

    /** The window into the destination: plane through the gate blocks' centres, +z towards the player. */
    ViewFrame entry() {
        return new ViewFrame(world, new Point3(base.x() + .5, base.y(), base.z() + .5), facing + 2, .5, HEIGHT);
    }

    /** True when a player's feet position touches or enters the gate from the front. */
    boolean contains(double x, double y, double z) {
        Point3 local = entry().local(new Point3(x, y, z));
        return Math.abs(local.x()) < .5 && local.z() > -.5 && local.z() < REACH && local.y() >= -1 && local.y() < HEIGHT;
    }

    /** Centre of the gate. */
    Point3 centre() { return new Point3(base.x() + .5, base.y() + HEIGHT / 2.0, base.z() + .5); }

    /**
     * The window's counterpart at a destination: a frame whose +z is the arrival view direction ({@code yaw}) and whose
     * plane lies just behind the arrival point, so the window shows what the player will see after walking in.
     */
    static ViewFrame exit(UUID world, double x, double y, double z, float yaw) {
        int q = facing(yaw);
        int[] f = forward(q);
        return new ViewFrame(world, new Point3(x - f[0] * .8, y, z - f[1] * .8), q, .5, HEIGHT);
    }

    /** Points on the outline of the gate's front face for particles. */
    List<Point3> edges(int perEdge) {
        ViewFrame frame = entry();
        double front = inWall ? .52 : 0;
        double[][] c = {{-.5, 0}, {.5, 0}, {.5, HEIGHT}, {-.5, HEIGHT}};
        List<Point3> out = new ArrayList<>(4 * perEdge);
        for (int e = 0; e < 4; e++) for (int i = 0; i < perEdge; i++) {
            double t = perEdge == 1 ? .5 : i / (double) (perEdge - 1);
            double[] a = c[e], b = c[(e + 1) % 4];
            out.add(frame.global(new Point3(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, front)));
        }
        return out;
    }
}
