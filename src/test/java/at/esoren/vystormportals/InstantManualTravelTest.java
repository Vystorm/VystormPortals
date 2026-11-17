package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstantManualTravelTest {
    @Test void crossingTeleportsInTheSameEventWithoutMovingPlayerBackOrQueueing() throws Exception {
        PortalsPlugin plugin = mock(PortalsPlugin.class, CALLS_REAL_METHODS);
        Server server = mock(Server.class); World world = mock(World.class); Player player = mock(Player.class);
        UUID worldId = UUID.randomUUID(), playerId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId); when(player.getUniqueId()).thenReturn(playerId);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        var border = mock(WorldBorder.class); when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any())).thenReturn(true);
        Block floor = mock(Block.class), air = mock(Block.class);
        Material solid = mock(Material.class); when(solid.isSolid()).thenReturn(true);
        when(floor.getType()).thenReturn(solid);
        when(air.getType()).thenReturn(Material.AIR); when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int)call.getArgument(1) == 63 ? floor : air);
        when(player.getWorld()).thenReturn(world); when(player.getHeight()).thenReturn(1.8);
        when(player.getPassengers()).thenReturn(List.of()); when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getVelocity()).thenReturn(new Vector(0, 0, -.2));
        Location from = new Location(world, .5, 64, .7), to = new Location(world, .5, 64, .4);
        when(player.getLocation()).thenReturn(from);
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        when(server.getWorld(worldId)).thenReturn(world);
        doReturn(server).when(plugin).getServer(); doReturn(new YamlConfiguration()).when(plugin).getConfig();
        Portal source = new Portal("source", playerId, new ViewFrame(worldId, new Point3(.5,64,.5),0,1.5,3),"target",true,80);
        Portal target = new Portal("target", playerId, new ViewFrame(worldId, new Point3(100.5,64,.5),0,1.5,3),"source",true,80);
        set(plugin, "portals", Map.of("source", source, "target", target));
        for (String field : List.of("views", "pending", "corrections", "cooldown")) set(plugin, field, new HashMap<>());
        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        plugin.move(event);
        verify(player).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(player).setVelocity(new Vector(0,0,.2));
        assertEquals(to, event.getTo());
        var pending = PortalsPlugin.class.getDeclaredField("pending"); pending.setAccessible(true);
        assertTrue(((Map<?,?>)pending.get(plugin)).isEmpty());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = PortalsPlugin.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
