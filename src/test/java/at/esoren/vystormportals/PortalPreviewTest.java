package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import Vystorm.vystorm_core.packet.ClientBiomes;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalPreviewTest {
    @Test void stationaryViewDrainsEveryTickButDoesNotResampleUnchangedTerrainEveryTick() {
        Plugin owner = mock(Plugin.class); Player player = mock(Player.class); BlockView view = mock(BlockView.class);
        var source = new HorizontalFrame(UUID.randomUUID(), new Point3(0, 64, 0), 1, 1);
        var target = new HorizontalFrame(UUID.randomUUID(), new Point3(100, 64, 0), 1, 1);
        var eye = new Point3(0, 66, 0); var cell = new BlockPosition(0, 63, 0);
        var opening = Set.of(new BlockPosition(0, 64, 0));
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        BlockData concrete = mock(BlockData.class);
        BlockData data = mock(BlockData.class); when(data.getMaterial()).thenReturn(mock(Material.class));
        try (var visuals = mockStatic(Visuals.class); var biomes = mockStatic(ClientBiomes.class); var bukkit = mockStatic(Bukkit.class)) {
            visuals.when(() -> Visuals.open(owner, player)).thenReturn(view);
            visuals.when(() -> Visuals.projection(source, target, eye, 8, 16, 4088, opening)).thenReturn(Map.of(cell, data));
            bukkit.when(() -> Bukkit.createBlockData(Material.BLACK_CONCRETE)).thenReturn(concrete);
            PortalPreview preview = new PortalPreview(TestCore.api(), owner, player);
            assertTrue(preview.horizontal(source, target, eye, opening, 1));
            assertTrue(preview.horizontal(source, target, eye, opening, 2));
            assertTrue(preview.horizontal(source, target, eye, opening, 11));
            visuals.verify(() -> Visuals.projection(source, target, eye, 8, 16, 4088, opening), times(2));
            Map<BlockPosition, BlockData> expected = new HashMap<>();
            expected.put(cell, data);
            PortalBorder.horizontal(opening).forEach(p -> expected.put(p, concrete));
            verify(view, times(3)).replacePrioritized(expected, 512);
            preview.close(); verify(view).close(); biomes.verify(() -> ClientBiomes.clearProjection(owner, player));
        }
    }

    @Test void movementPrioritizesTheNewlyVisibleSideAndKeepsApertureFirst() {
        var left = new BlockPosition(-3, 64, -2);
        var right = new BlockPosition(3, 64, -2);
        var opening = new BlockPosition(0, 64, 0);
        var data = mock(BlockData.class);
        var blocks = Map.of(left, data, right, data, opening, data);
        assertEquals(List.of(opening, left, right), new ArrayList<>(PortalPreview.prioritize(
                blocks, Set.of(opening), new Point3(-4, 65, 3)).keySet()));
        assertEquals(List.of(opening, right, left), new ArrayList<>(PortalPreview.prioritize(
                blocks, Set.of(opening), new Point3(4, 65, 3)).keySet()));
    }
}
