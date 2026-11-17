package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalBorderTest {
    @Test void uprightRimIncludesCornersForEveryOrientationWithoutFillingOpening() {
        for (int turn = 0; turn < 4; turn++) {
            var frame = new ViewFrame(UUID.randomUUID(), new Point3(.5, 64, .5), turn, 1.5, 3);
            var rim = PortalBorder.upright(frame, Set.of());
            assertEquals(16, rim.size());
            assertTrue(rim.contains(BlockPosition.containing(frame.global(new Point3(-2, -.5, 0)))));
            assertFalse(rim.contains(BlockPosition.containing(frame.global(new Point3(0, .5, 0)))));
        }
    }

    @Test void horizontalRimPreservesTheCentralFountainPillar() {
        Set<BlockPosition> opening = new HashSet<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (x != 0 || z != 0) opening.add(new BlockPosition(x, 64, z));
        var rim = PortalBorder.horizontal(opening);
        assertEquals(16, rim.size());
        assertTrue(Collections.disjoint(opening, rim));
        assertFalse(rim.contains(new BlockPosition(0, 64, 0)));
        assertTrue(rim.contains(new BlockPosition(-2, 64, -2)));
    }
}
