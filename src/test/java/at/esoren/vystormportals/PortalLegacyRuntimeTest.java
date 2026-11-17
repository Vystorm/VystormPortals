package at.esoren.vystormportals;

import Vystorm.vystorm_core.compat.ServerCompat;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs with the actual 1.20.6 API and without the modern adapter on the classpath. */
class PortalLegacyRuntimeTest {
    @Test void oldApiSelectsFallbackWithoutLinkingModernClasses() throws Exception {
        assertFalse(ServerCompat.hasDialogs());
        assertFalse(ServerCompat.hasClass("org.bukkit.GameRules"));
        assertInstanceOf(LegacyPortalApi.class,PortalApi.INSTANCE);
        assertEquals(2,PortalApi.INSTANCE.delays().size());
        for(String name : new String[]{"PortalsPlugin","PortalMenu","PortalPreview","NativeEndViews","InstantPortalTravel"})
            assertNotNull(Class.forName("at.esoren.vystormportals."+name));
    }

    @Test void nativeDelayRulesAreAppliedAndRestoredOnOldApi() {
        World world=mock(World.class);
        for(GameRule<Integer> rule : PortalApi.INSTANCE.delays()) {
            when(world.getGameRuleValue(rule)).thenReturn(80,0);
            when(world.setGameRule(rule,0)).thenReturn(true);
        }
        InstantPortalTravel travel=new InstantPortalTravel();
        travel.apply(world);
        travel.apply(world);
        travel.close();
        for(GameRule<Integer> rule : PortalApi.INSTANCE.delays()) {
            verify(world).setGameRule(rule,0);
            verify(world).setGameRule(rule,80);
        }
    }
}
