package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

class NativeEndViewsTest {
    private static final UUID WORLD_ID = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");

    @Test
    void givenConnectedThreeByThreePortalRingWhenDetectingThenReturnsBoundedMaskWithoutPillar() {
        Fixture fixture = new Fixture();
        BlockPosition centre = new BlockPosition(10, 70, -4);
        BlockPosition start = new BlockPosition(9, 70, -4);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            fixture.put(new BlockPosition(centre.x() + x, centre.y(), centre.z() + z),
                    x == 0 && z == 0 ? Material.BEDROCK : Material.END_PORTAL);
        }
        fixture.put(new BlockPosition(centre.x(), centre.y() + 1, centre.z()), Material.END_PORTAL);

        NativeEndViews.Surface surface = NativeEndViews.detect(fixture.world, start);

        assertEquals(8, surface.cells().size());
        assertFalse(surface.cells().contains(centre), "the bedrock pillar is not part of the aperture");
        assertFalse(surface.cells().contains(new BlockPosition(centre.x(), centre.y() + 1, centre.z())), "detection is limited to the portal plane");
        assertTrue(surface.cells().stream().allMatch(cell -> cell.y() == 70
                && Math.abs(cell.x() - centre.x()) <= 1 && Math.abs(cell.z() - centre.z()) <= 1));
        assertEquals(WORLD_ID, surface.frame().world());
        assertEquals(10.5, surface.frame().origin().x());
        assertEquals(70.75, surface.frame().origin().y());
        assertEquals(-3.5, surface.frame().origin().z());
        assertEquals(1.5, surface.frame().halfWidth());
        assertEquals(1.5, surface.frame().halfDepth());
    }

    @Test
    void givenNonPortalOrEndGatewayStartWhenDetectingThenReturnsNoSurface() {
        Fixture fixture = new Fixture();
        BlockPosition start = new BlockPosition(0, 64, 0);

        fixture.put(start, Material.END_PORTAL_FRAME);
        assertNull(NativeEndViews.detect(fixture.world, start));
        fixture.put(start, Material.END_GATEWAY);
        assertNull(NativeEndViews.detect(fixture.world, start));
        fixture.put(start, Material.STONE);
        assertNull(NativeEndViews.detect(fixture.world, start));
    }

    @Test
    void givenUnloadedStartChunkWhenDetectingThenNeverReadsOrLoadsChunkBlocks() {
        Fixture fixture = new Fixture();
        when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);

        assertNull(NativeEndViews.detect(fixture.world, new BlockPosition(0, 64, 0)));

        verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(fixture.world, never()).getChunkAt(anyInt(), anyInt());
        verify(fixture.world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void givenOversizedConnectedClusterWhenDetectingThenRejectsItWithinFiniteReads() {
        Fixture fixture = new Fixture();
        BlockPosition start = new BlockPosition(0, 64, 0);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            fixture.put(new BlockPosition(x, 64, z), Material.END_PORTAL);
        }

        assertNull(NativeEndViews.detect(fixture.world, start));

        verify(fixture.world, atMost(256)).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void givenConnectedPortalExceedingSearchRadiusWhenDetectingThenRejectsWithinBoundedWork() {
        Fixture fixture = new Fixture();
        BlockPosition start = new BlockPosition(0, 64, 0);
        for (int x = 0; x <= 12; x++) fixture.put(new BlockPosition(x, 64, 0), Material.END_PORTAL);

        assertNull(NativeEndViews.detect(fixture.world, start));

        verify(fixture.world, atMost(128)).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final Map<BlockPosition, Material> materials = new HashMap<>();

        Fixture() {
            when(world.getUID()).thenReturn(WORLD_ID);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
                BlockPosition position = new BlockPosition(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(materials.getOrDefault(position, Material.STONE));
                return block;
            });
        }

        void put(BlockPosition position, Material material) {
            materials.put(position, material);
        }
    }
}
