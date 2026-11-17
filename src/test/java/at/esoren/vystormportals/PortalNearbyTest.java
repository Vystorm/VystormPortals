package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PortalNearbyTest {
    final UUID owner=UUID.randomUUID(), viewer=UUID.randomUUID(), worldId=UUID.randomUUID();
    Portal portal(String id,double x,UUID world,boolean visible) {
        return new Portal(id,owner,new ViewFrame(world,new Point3(x,64,0),0,1.5,3),null,visible,0);
    }
    @Test void filtersPrivateOtherWorldAndDistantPortalsAndUsesStableDistanceOrder() throws Exception {
        var plugin=mock(PortalsPlugin.class,CALLS_REAL_METHODS);
        var field=PortalsPlugin.class.getDeclaredField("trust");field.setAccessible(true);field.set(plugin,new PortalsPlugin.Trust(true,16,true));
        var world=mock(World.class);when(world.getUID()).thenReturn(worldId);
        var player=mock(Player.class);when(player.getUniqueId()).thenReturn(viewer);
        when(player.hasPermission("vystormportals.use")).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world,0,64,0));
        Map<String,Portal> values=new HashMap<>();
        for(Portal p:List.of(portal("z",5,worldId,true),portal("a",-5,worldId,true),
                portal("hidden",1,worldId,false),portal("other",0,UUID.randomUUID(),true),
                portal("far",257,worldId,true),portal("edge",256,worldId,true),
                portal("friend",2,worldId,false).trust(viewer))) values.put(p.id(),p);
        field=PortalsPlugin.class.getDeclaredField("portals");field.setAccessible(true);field.set(plugin,values);
        assertEquals(List.of("friend","a","z","edge"),plugin.nearby(player).stream().map(Portal::id).toList());
        when(player.hasPermission("vystormportals.use")).thenReturn(false);
        assertTrue(plugin.nearby(player).isEmpty());
        when(player.hasPermission("vystormportals.use")).thenReturn(true);
        for(int i=10;i<30;i++) {var p=portal("gate"+i,i,worldId,true);values.put(p.id(),p);}
        assertEquals(10,plugin.nearby(player).size());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
}
