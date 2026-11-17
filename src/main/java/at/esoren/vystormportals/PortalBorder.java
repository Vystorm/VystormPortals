package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;

/** One-cell client-only rim, including corners, outside the portal aperture. */
final class PortalBorder {
    private PortalBorder() {}

    static Set<BlockPosition> upright(ViewFrame frame, Set<BlockPosition> opening) {
        Set<BlockPosition> cells = new HashSet<>(opening);
        if (cells.isEmpty()) {
            for (double x = -frame.halfWidth() + .5; x < frame.halfWidth(); x++)
                for (double y = .5; y < frame.height(); y++)
                    cells.add(BlockPosition.containing(frame.global(new Point3(x, y, 0))));
        }
        return rim(cells, frame.quarterTurns() % 2 == 0 ? 0 : 1);
    }

    static Set<BlockPosition> horizontal(Set<BlockPosition> opening) { return rim(opening, 2); }

    private static Set<BlockPosition> rim(Set<BlockPosition> cells, int plane) {
        Set<BlockPosition> result = new HashSet<>();
        int minX = cells.stream().mapToInt(BlockPosition::x).min().orElse(0);
        int maxX = cells.stream().mapToInt(BlockPosition::x).max().orElse(0);
        int minY = cells.stream().mapToInt(BlockPosition::y).min().orElse(0);
        int maxY = cells.stream().mapToInt(BlockPosition::y).max().orElse(0);
        int minZ = cells.stream().mapToInt(BlockPosition::z).min().orElse(0);
        int maxZ = cells.stream().mapToInt(BlockPosition::z).max().orElse(0);
        for (BlockPosition p : cells) for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            BlockPosition neighbour = switch (plane) {
                case 0 -> new BlockPosition(p.x() + a, p.y() + b, p.z());
                case 1 -> new BlockPosition(p.x(), p.y() + b, p.z() + a);
                default -> new BlockPosition(p.x() + a, p.y(), p.z() + b);
            };
            // Leave interior holes (notably the End fountain pillar) unchanged.
            boolean interior = neighbour.x() >= minX && neighbour.x() <= maxX
                    && neighbour.y() >= minY && neighbour.y() <= maxY
                    && neighbour.z() >= minZ && neighbour.z() <= maxZ;
            if (!interior) result.add(neighbour);
        }
        return Set.copyOf(result);
    }
}
