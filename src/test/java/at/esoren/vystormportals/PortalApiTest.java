package at.esoren.vystormportals;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalApiTest {
    @Test void adapterRequiresBothModernFeatures() {
        assertInstanceOf(LegacyPortalApi.class, PortalApi.select(false, false));
        assertInstanceOf(LegacyPortalApi.class, PortalApi.select(false, true));
        assertInstanceOf(LegacyPortalApi.class, PortalApi.select(true, false));
        assertEquals("ModernPortalApi", PortalApi.select(true, true).getClass().getSimpleName());
        assertFalse(PortalApi.hasRespawnLookup(OldPlayer.class));
        assertTrue(PortalApi.hasRespawnLookup(NewPlayer.class));
    }

    @Test void legacyRespawnUsesAvailableLookupForBothPhases() {
        Player player=mock(Player.class);
        Location location=new Location(mock(World.class),1,64,2);
        when(player.getRespawnLocation()).thenReturn(location);
        PortalApi api=PortalApi.select(false,false);
        assertSame(location,api.respawn(player,false));
        assertSame(location,api.respawn(player,true));
        verify(player,times(2)).getRespawnLocation();
        verify(player,never()).getRespawnLocation(anyBoolean());
    }

    @Test void modernRespawnPreservesNonLoadingLookup() {
        Player player=mock(Player.class);
        PortalApi api=PortalApi.select(true,true);
        api.respawn(player,false);
        api.respawn(player,true);
        verify(player).getRespawnLocation(false);
        verify(player).getRespawnLocation(true);
        verify(player,never()).getRespawnLocation();
    }

    @Test void nativeSearchSelectsOldSignatureAndPrefersNewSignature() throws Exception {
        assertEquals("findPortalAround",NativePortalAccess.searchMethod(OldForcer.class).getName());
        assertEquals("findClosestPortalPosition",NativePortalAccess.searchMethod(NewForcer.class).getName());
        assertThrows(NoSuchMethodException.class,()->NativePortalAccess.searchMethod(Object.class));
    }

    @Test void endPlatformFallbackRequiresEntityOverload() throws Exception {
        assertEquals(2,EndArrivalPlatform.legacyPlatformMethod(OldLevel.class).getParameterCount());
        assertThrows(NoSuchMethodException.class,()->EndArrivalPlatform.legacyPlatformMethod(Object.class));
    }

    public static class OldPlayer { public Location getRespawnLocation() { return null; } }
    public static class NewPlayer extends OldPlayer { public Location getRespawnLocation(boolean resolve) { return null; } }
    public static class OldForcer { public void findPortalAround(Object pos,Object border,int radius) {} }
    public static class NewForcer extends OldForcer { public void findClosestPortalPosition(Object pos,Object border,int radius) {} }
    public static class OldLevel {
        public static void makeObsidianPlatform(Object level) {}
        public static void makeObsidianPlatform(Object level,Object entity) {}
    }
}
