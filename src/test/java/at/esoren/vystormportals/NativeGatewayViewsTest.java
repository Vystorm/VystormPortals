package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeGatewayViewsTest {
    private static final UUID END = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");
    private static final BlockPosition GATEWAY = new BlockPosition(96, 75, 0);
    private static final Point3 EXIT = new Point3(1024.7, 70, 13.2);

    @Test
    void givenEyeInFrontOfGatewayWhenSightingThenOneBlockWindowFacesTheViewer() {
        NativeGatewayViews.Sight south = NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 76.6, 3), END, EXIT);
        NativeGatewayViews.Sight west = NativeGatewayViews.sight(END, GATEWAY, new Point3(93, 76.6, 0.8), END, EXIT);
        NativeGatewayViews.Sight north = NativeGatewayViews.sight(END, GATEWAY, new Point3(96.2, 75.4, -2), END, EXIT);
        NativeGatewayViews.Sight east = NativeGatewayViews.sight(END, GATEWAY, new Point3(99.5, 76, 0.1), END, EXIT);

        assertEquals(0, south.entry().quarterTurns());
        assertEquals(1, west.entry().quarterTurns());
        assertEquals(2, north.entry().quarterTurns());
        assertEquals(3, east.entry().quarterTurns());
        assertEquals(Set.of(GATEWAY), south.cell());
        assertEquals(new Point3(96.5, 75, 0.5), south.entry().origin());
        assertEquals(0.5, south.entry().halfWidth());
        assertEquals(1, south.entry().height());
        assertTrue(south.entry().visible(new Point3(96.5, 76.6, 3), new Point3(96.5, 72, -5)), "the window opens towards the viewer");
    }

    @Test
    void givenExitWhenSightingThenViewKeepsTheLookingDirectionAtTheExitBlock() {
        NativeGatewayViews.Sight sight = NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 76.6, 3), END, EXIT);

        assertEquals(new Point3(1024.5, 70, 13.5), sight.exit().origin());
        assertEquals(2, sight.exit().quarterTurns());
        // looking north (-z) through the gateway keeps looking north (-z) at the exit
        Point3 behind = sight.entry().through(sight.exit(), new Point3(96.5, 75.5, -4.5));
        assertTrue(behind.z() < sight.exit().origin().z(), "behind the gateway maps to ahead of the exit: " + behind);
        assertEquals(1024.5, behind.x(), 1e-9);
    }

    @Test
    void givenEyeTooFarAboveOrInsideWhenSightingThenNoView() {
        assertNull(NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 76.6, 6), END, EXIT), "beyond 4 blocks");
        assertNull(NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 79, 2), END, EXIT), "above the gateway");
        assertNull(NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 73, 2), END, EXIT), "below the gateway");
        assertNull(NativeGatewayViews.sight(END, GATEWAY, new Point3(96.6, 75.5, 0.4), END, EXIT), "inside the block");
        assertNotNull(NativeGatewayViews.sight(END, GATEWAY, new Point3(96.5, 74.5, 2), END, EXIT), "slightly below still looks in");
    }
}
