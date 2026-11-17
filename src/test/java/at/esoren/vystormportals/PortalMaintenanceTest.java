package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalMaintenanceTest {
    @TempDir Path directory;
    private final UUID owner=UUID.randomUUID(), worldId=UUID.randomUUID();

    private Portal portal(String id,String target) {
        return new Portal(id,owner,new ViewFrame(worldId,new Point3(.5,64,.5),0,1.5,3),target,true,40);
    }

    @Test void bothEndsMustBeOpenAndEditsPreserveMaintenance() {
        Portal a=portal("a","b"), b=portal("b","a");
        assertTrue(a.routeOpen(b));
        assertFalse(a.routeOpen(null));
        assertFalse(a.pause(true).routeOpen(b));
        assertFalse(a.routeOpen(b.pause(true)));
        assertFalse(a.pause(true).routeOpen(b.pause(true)));
        UUID friend=UUID.randomUUID();
        Portal paused=a.pause(true).rename("c").link("d").access(false).warmup(80).trust(friend).untrust(friend);
        assertTrue(paused.paused());
        assertEquals(a,a.pause(true).pause(false));
    }

    @Test void stateSurvivesRestartAndOldFilesDefaultToOpenButBadTypesFail() throws Exception {
        Path file=directory.resolve("portals.yml");
        PortalStore store=new PortalStore(file);
        Portal a=portal("a",null).pause(true);
        store.save(Map.of("a",a));
        assertEquals(a,store.load().get("a"));
        YamlConfiguration yaml=new YamlConfiguration();yaml.load(file.toFile());
        yaml.set("portals.a.paused",null);yaml.save(file.toFile());
        assertFalse(store.load().get("a").paused());
        for(Object invalid:List.of("false",42,List.of(true))) {
            yaml.set("portals.a.paused",invalid);yaml.save(file.toFile());
            assertThrows(Exception.class,store::load);
        }
    }

    @Test void commandsPersistCancelPendingAndKeepLinksAndTrust() throws Exception {
        Portal a=portal("a","b").trust(UUID.randomUUID()), b=portal("b","a");
        PortalsPlugin plugin=plugin(Map.of("a",a,"b",b));
        Player player=player(owner,true,false);
        Map<UUID,Object> pending=new HashMap<>();pending.put(owner,new Object());set(plugin,"pending",pending);
        plugin.run(player,"pause","A");
        assertEquals(a.pause(true),plugin.portals().get("a"));
        assertEquals(b,plugin.portals().get("b"));
        assertTrue(pending.isEmpty());
        assertTrue(new PortalStore(directory.resolve("portals.yml")).load().get("a").paused());
        plugin.run(player,"rename","a","renamed");
        assertTrue(plugin.portals().get("renamed").paused());
        assertEquals("renamed",plugin.portals().get("b").target());
        plugin.run(player,"resume","renamed");
        assertFalse(plugin.portals().get("renamed").paused());
    }

    @Test void onlyEditorsCanPauseAndFailedSaveKeepsRuntimeState() throws Exception {
        Portal a=portal("a",null);
        PortalsPlugin plugin=plugin(Map.of("a",a));
        plugin.run(player(owner,false,false),"pause","a");
        plugin.run(player(UUID.randomUUID(),true,false),"pause","a");
        assertEquals(a,plugin.portals().get("a"));
        plugin.run(player(UUID.randomUUID(),false,true),"pause","a");
        assertTrue(plugin.portals().get("a").paused());
        PortalStore broken=mock(PortalStore.class);doThrow(new java.io.IOException("test")).when(broken).save(anyMap());
        set(plugin,"store",broken);
        plugin.run(player(owner,true,false),"resume","a");
        assertTrue(plugin.portals().get("a").paused());
    }

    @Test void pausedEntranceOrExitBlocksEvenAdminMovementWithInstantOrWarmupTravel() throws Exception {
        for(boolean instant:List.of(true,false)) for(boolean entrance:List.of(true,false)) {
            Portal a=portal("a","b").pause(entrance), b=portal("b","a").pause(!entrance);
            PortalsPlugin plugin=plugin(Map.of("a",a,"b",b));
            plugin.getConfig().set("instant-travel",instant);
            Player player=player(owner,true,true);
            World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
            when(player.getWorld()).thenReturn(world);when(player.getHeight()).thenReturn(1.8);
            when(player.getPassengers()).thenReturn(List.of());
            Location from=new Location(world,.5,64,.7),to=new Location(world,.5,64,.4);
            PlayerMoveEvent event=new PlayerMoveEvent(player,from,to);
            plugin.move(event);
            assertEquals(from,event.getTo());
            verify(player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
        }
    }

    @Test void pausedRoutesDoNotAskForEntitiesOrWorlds() throws Exception {
        Portal a=portal("a","b"),b=portal("b","a").pause(true);
        PortalsPlugin plugin=mock(PortalsPlugin.class);
        when(plugin.destination(a)).thenReturn(b);when(plugin.destination(b)).thenReturn(a);
        EntityTravel travel=new EntityTravel(plugin,new EntityTravel.Options(true,true,true,true,true,16));
        try(var bukkit=mockStatic(Bukkit.class)) {
            travel.tick(1,List.of(a,b));
            bukkit.verifyNoInteractions();
        }
    }

    @Test void completionOffersEditablePortalsOnly() {
        for(String action:List.of("pause","resume"))
            assertEquals(List.of("mine"),PortalsPlugin.suggestions(new String[]{action,""},List.of("mine"),List.of("mine","other")));
    }

    @Test void revokedEditingPermissionRemovesOwnedPortalsFromCompletion() throws Exception {
        PortalsPlugin plugin=plugin(Map.of("a",portal("a",null)));
        Player player=player(owner,false,false);
        assertEquals(List.of(),plugin.complete(player,"rename",""));
        when(player.hasPermission("vystormportals.create")).thenReturn(true);
        assertEquals(List.of("a"),plugin.complete(player,"rename",""));
    }

    @Test void infoAccessUsesThePlayersLanguageForPublicAndPrivate() throws Exception {
        var translations=new HashMap<String,String>();
        for(String code:List.of("en","de","fr","it","pt_br","ru","zh_cn")) {
            try(var in=getClass().getResourceAsStream("/lang/"+code+".yml")) {
                translations.put(code,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        var lang=Vystorm.vystorm_core.lang.Lang.create("VystormPortals",translations);
        for(String code:translations.keySet()) for(boolean publicAccess:List.of(true,false)) {
            Portal portal=portal("a",null).access(publicAccess);PortalsPlugin plugin=plugin(Map.of("a",portal));
            Player player=player(owner,true,false);when(player.locale()).thenReturn(Locale.forLanguageTag(code.replace('_','-')));
            Server server=mock(Server.class);doReturn(server).when(plugin).getServer();
            var offline=mock(OfflinePlayer.class);when(server.getOfflinePlayer(owner)).thenReturn(offline);
            set(plugin,"lang",lang);doNothing().when(plugin).say(any(),anyString(),any(Object[].class));
            plugin.run(player,"info","a");
            Object[] expected=PortalsPlugin.info(portal,worldId.toString(),null);
            expected[4]=lang.plain(player,publicAccess?"menu.public":"menu.private");
            verify(plugin).say(player,"info",expected);
        }
    }

    @Test void validationErrorsAreTranslatedInEveryBundledLanguage() throws Exception {
        var translations=new HashMap<String,String>();
        for(String code:List.of("en","de","fr","it","pt_br","ru","zh_cn")) {
            try(var in=getClass().getResourceAsStream("/lang/"+code+".yml")) {
                translations.put(code,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        var lang=Vystorm.vystorm_core.lang.Lang.create("VystormPortals",translations);
        for(String code:translations.keySet()) {
            PortalsPlugin plugin=plugin(Map.of("a",portal("a",null)));set(plugin,"lang",lang);
            Player player=player(owner,true,false);when(player.locale()).thenReturn(Locale.forLanguageTag(code.replace('_','-')));
            doNothing().when(plugin).say(any(),anyString(),any(Object[].class));
            plugin.run(player,"unlink","a");
            verify(plugin).say(player,"invalid",lang.plain(player,"errors.not-linked","0","a"));
            clearInvocations(plugin);plugin.run(player,"warmup","a","-1");
            verify(plugin).say(player,"invalid",lang.plain(player,"errors.warmup"));
        }
    }

    @Test void evenWidthCreationChecksOnlyTheActualAperture() throws Exception {
        PortalsPlugin plugin=plugin(Map.of());Player player=player(owner,true,false);
        World world=mock(World.class);when(world.getUID()).thenReturn(worldId);
        when(player.getWorld()).thenReturn(world);when(player.getLocation()).thenReturn(new Location(world,.5,64,.5));
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            var block=mock(org.bukkit.block.Block.class);
            when(block.isPassable()).thenReturn((int)call.getArgument(0)>=-1 && (int)call.getArgument(0)<=2);
            return block;
        });
        try(var visuals=mockStatic(Visuals.class)) {plugin.run(player,"create","wide","4","2");}
        assertTrue(plugin.portals().containsKey("wide"));
        verify(world,never()).getBlockAt(eq(-2),anyInt(),anyInt());
    }

    private PortalsPlugin plugin(Map<String,Portal> portals) throws Exception {
        PortalsPlugin plugin=mock(PortalsPlugin.class,CALLS_REAL_METHODS);
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        doReturn(Logger.getLogger("maintenance-test")).when(plugin).getLogger();
        set(plugin,"portals",portals);set(plugin,"store",new PortalStore(directory.resolve("portals.yml")));
        set(plugin,"trust",new PortalsPlugin.Trust(true,16,true));
        set(plugin,"core",TestCore.api());
        for(String field:List.of("views","pending","corrections","cooldown"))set(plugin,field,new HashMap<>());
        return plugin;
    }

    private Player player(UUID id,boolean create,boolean admin) {
        Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission("vystormportals.use")).thenReturn(true);
        when(player.hasPermission("vystormportals.create")).thenReturn(create);
        when(player.hasPermission("vystormportals.admin")).thenReturn(admin);
        return player;
    }

    private static void set(Object target,String name,Object value) throws Exception {
        var field=PortalsPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
