package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativePortalsTest {
    @Test void litPortalDetectsBothAxesAndPreservesActualSize() {
        for (Axis axis : List.of(Axis.X, Axis.Z)) {
            Fixture f = new Fixture(); f.fill(axis, 2, 3);
            var portal = NetherSurface.detect(f.world, new BlockPosition(-1, 64, -1));
            assertNotNull(portal); assertEquals(6, portal.cells().size());
            assertEquals(axis, portal.axis()); assertEquals(1, portal.frame().halfWidth());
            assertEquals(3, portal.frame().height()); assertTrue(portal.intact(f.world));
            f.cells.remove(new BlockPosition(-1, 65, -1));
            assertFalse(portal.intact(f.world));
            assertNull(NetherSurface.detect(f.world, new BlockPosition(-1, 64, -1)));
        }
    }

    @Test void unloadedOrOversizedPortalNeverForcesChunks() {
        Fixture f = new Fixture(); f.fill(Axis.X, 22, 3);
        assertNull(NetherSurface.detect(f.world, new BlockPosition(-1, 64, -1)));
        when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(f.world);
        assertNull(NetherSurface.detect(f.world, new BlockPosition(-1, 64, -1)));
        verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void preparationAreaIsBoundedAndIncludesNegativeCoordinateBorders() {
        var chunks = PortalPreparation.area(-1, -1, 128);
        assertEquals(361, chunks.size());
        assertTrue(chunks.contains(new PortalPreparation.ChunkKey(-10, -10)));
        assertTrue(chunks.contains(new PortalPreparation.ChunkKey(8, 8)));
        assertThrows(IllegalArgumentException.class, () -> PortalPreparation.area(0, 0, 129));
    }

    @Test void dimensionFamiliesAndExplicitWorldLinksDoNotPickUnrelatedWorlds() {
        World source = world("survival", World.Environment.NORMAL);
        World nether = world("survival_nether", World.Environment.NETHER);
        World other = world("creative_nether", World.Environment.NETHER);
        assertSame(nether, NativeWorlds.destination(source, World.Environment.NETHER, List.of(other, nether), null));
        assertSame(other, NativeWorlds.destination(source, World.Environment.NETHER, List.of(other, nether), "creative_nether"));
        assertNull(NativeWorlds.destination(source, World.Environment.NETHER, List.of(other), null));
        assertNull(NativeWorlds.destination(source, World.Environment.NETHER, List.of(nether), "missing"));
    }

    @Test void endPlatformPreparationRejectsPlayerBlocksAndUnloadedChunks() {
        Fixture f = new Fixture(); when(f.world.getEnvironment()).thenReturn(World.Environment.THE_END);
        assertTrue(EndArrivalPlatform.emptyAndLoaded(f.world));
        f.solid.add(new BlockPosition(100, 49, 0));
        assertFalse(EndArrivalPlatform.emptyAndLoaded(f.world));
        f.solid.clear(); when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(f.world);
        assertFalse(EndArrivalPlatform.emptyAndLoaded(f.world));
        verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    static World world(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name); when(world.getEnvironment()).thenReturn(environment);
        when(world.getKey()).thenReturn(new NamespacedKey("test", name)); return world;
    }

    private static final class Fixture {
        final World world = mock(World.class);
        final Map<BlockPosition, Axis> cells = new HashMap<>();
        final Set<BlockPosition> solid = new HashSet<>();
        Fixture() {
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                BlockPosition p = new BlockPosition(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                Block block = mock(Block.class); Axis axis = cells.get(p);
                when(block.getType()).thenReturn(axis == null ? solid.contains(p) ? Material.STONE : Material.AIR : Material.NETHER_PORTAL);
                if (axis != null) { Orientable data = mock(Orientable.class); when(data.getAxis()).thenReturn(axis); when(block.getBlockData()).thenReturn(data); }
                return block;
            });
        }
        void fill(Axis axis, int width, int height) {
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
                cells.put(new BlockPosition(-1 + (axis == Axis.X ? x : 0), 64 + y, -1 + (axis == Axis.Z ? x : 0)), axis);
        }
    }
}
