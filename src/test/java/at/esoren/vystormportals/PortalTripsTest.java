package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalTripsTest {
    @TempDir Path folder;
    private record Fixture(PortalsPlugin plugin,Player player,World world,PortalTrips trips,Map<String,Portal> portals,
                           AtomicLong now,AtomicReference<Location> location,List<String> messages) {}
    private Fixture fixture() throws Exception {
        PortalsPlugin plugin=mock(PortalsPlugin.class);when(plugin.core()).thenReturn(TestCore.api());Player player=mock(Player.class);World world=mock(World.class);Server server=mock(Server.class);
        UUID owner=UUID.randomUUID(),worldId=UUID.randomUUID();when(world.getUID()).thenReturn(worldId);when(world.getName()).thenReturn("test");
        when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getServer()).thenReturn(server);
        when(server.getWorld(worldId)).thenReturn(world);when(server.getPlayer(owner)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(owner);when(player.getWorld()).thenReturn(world);when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("vystormportals.use")).thenReturn(true);when(player.getHeight()).thenReturn(1.8);
        when(player.getPassengers()).thenReturn(List.of());when(plugin.allowed(eq(player),any())).thenReturn(true);when(plugin.hoursOpen(any())).thenReturn(true);
        when(plugin.safe(any(),eq(1.8))).thenReturn(true);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        AtomicReference<Location> location=new AtomicReference<>(new Location(world,0,64,0));
        when(player.getLocation()).thenAnswer(call->location.get().clone());
        when(player.teleport(any(Location.class),eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenAnswer(call->{location.set(call.getArgument(0));return true;});
        Map<String,Portal> portals=new LinkedHashMap<>();int x=0;
        for(String id:List.of("a","b","c")){portals.put(id,new Portal(id,owner,new ViewFrame(worldId,new Point3(x,64,0),0,1.5,3),null,true,0));x+=16;}
        when(plugin.portals()).thenReturn(portals);AtomicLong now=new AtomicLong(100000);List<String> messages=new ArrayList<>();
        doAnswer(call->{messages.add(call.getArgument(1));return null;}).when(plugin).say(eq(player),anyString(),any(Object[].class));
        return new Fixture(plugin,player,world,new PortalTrips(plugin,mock(DestinationChunks.class),now::get),portals,now,location,messages);
    }
    private void begin(Fixture f) {f.trips.command(f.player,"save tour a b c");f.trips.command(f.player,"start tour");f.trips.command(f.player,"confirm");}
    @Test void surplusDeleteArgumentsDoNotDeleteThePlan() throws Exception {
        Fixture f=fixture();f.trips.command(f.player,"save tour a b c");
        f.trips.command(f.player,"delete tour unexpected");
        assertNotNull(new TripStore(folder.resolve("trips.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(f.player.getUniqueId(),"tour"));
        assertTrue(f.messages.contains("trip-usage"));
    }
    @Test void surplusConfirmArgumentsDoNotStartTravel() throws Exception {
        Fixture f=fixture();f.trips.command(f.player,"save tour a b c");f.trips.command(f.player,"start tour");
        f.trips.command(f.player,"confirm unexpected");
        assertFalse(f.trips.active(f.player));assertTrue(f.messages.contains("trip-usage"));
    }
    /** The itinerary's teleport handler behind Core's stage dispatcher, next to a plugin that may deny the teleport. */
    private Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting staged(Fixture f, boolean[] deny) {
        when(f.plugin.getName()).thenReturn("VystormPortals");when(f.plugin.isEnabled()).thenReturn(true);
        var stages=Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.detached();
        f.trips.handleSharedEvents(stages.stages());
        stages.handle(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.plugin("AnotherPlugin"),PlayerTeleportEvent.class,
                Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT,event->event.setCancelled(deny[0]));
        return stages;
    }
    @Test void foreignTeleportEndsTheItineraryOnlyIfItReallyHappens() throws Exception {
        Fixture f=fixture();begin(f);boolean[] deny={true};var stages=staged(f,deny);
        Location from=new Location(f.world,0,64,0),to=new Location(f.world,300,64,0);
        stages.fire(new PlayerTeleportEvent(f.player,from,to,PlayerTeleportEvent.TeleportCause.COMMAND));
        assertTrue(f.trips.active(f.player),"a denied teleport does not end the itinerary");assertFalse(f.messages.contains("trip-cancelled"));
        deny[0]=false;
        stages.fire(new PlayerTeleportEvent(f.player,from,to,PlayerTeleportEvent.TeleportCause.COMMAND));
        assertFalse(f.trips.active(f.player));assertTrue(f.messages.contains("trip-cancelled"));
    }
    @Test void theItinerarysOwnTeleportDoesNotEndIt() throws Exception {
        Fixture f=fixture();begin(f);var stages=staged(f,new boolean[]{false});
        when(f.player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(call->{
            Location before=f.location.get();f.location.set(call.getArgument(0));
            return !stages.fire(new PlayerTeleportEvent(f.player,before,call.getArgument(0),call.getArgument(1))).isCancelled();
        });
        f.now.addAndGet(1000);f.trips.tick(20);
        verify(f.player,times(1)).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
        assertFalse(f.messages.contains("trip-cancelled"));
    }
    @Test void cancellationDuringTeleportDoesNotAnnounceArrivalOrApplyMomentum() throws Exception {
        Fixture f=fixture();begin(f);
        when(f.player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(call->{
            f.location.set(call.getArgument(0));f.trips.command(f.player,"cancel");return true;
        });
        f.now.addAndGet(1000);f.trips.tick(20);
        assertFalse(f.trips.active(f.player));assertFalse(f.messages.contains("trip-arrived"));
        verify(f.player,never()).setVelocity(any());
    }
    @Test void disconnectDuringTeleportDoesNotContinueTheJourney() throws Exception {
        Fixture f=fixture();begin(f);
        when(f.player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenAnswer(call->{
            f.location.set(call.getArgument(0));when(f.player.isOnline()).thenReturn(false);return true;
        });
        f.now.addAndGet(1000);f.trips.tick(20);
        assertFalse(f.trips.active(f.player));assertFalse(f.messages.contains("trip-arrived"));
        verify(f.player,never()).setVelocity(any());
    }
    @Test void teleportExceptionStopsTheJourneyWithoutRetryingOrEscapingTheTick() throws Exception {
        Fixture f=fixture();begin(f);when(f.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(f.player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenThrow(new IllegalStateException("teleport failed"));
        f.now.addAndGet(1000);assertDoesNotThrow(()->f.trips.tick(20));f.trips.tick(40);
        assertFalse(f.trips.active(f.player));
        verify(f.player,times(1)).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void movingAwayAndBackBetweenTicksStillCancelsTravel() throws Exception {
        Fixture f=fixture();begin(f);
        var event=new org.bukkit.event.player.PlayerMoveEvent(f.player,f.location.get().clone(),f.location.get().clone().add(2,0,0));
        f.trips.move(event);
        f.now.addAndGet(1000);f.trips.tick(20);
        assertFalse(f.trips.active(f.player));
        verify(f.player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void tripCompletionOffersActionsOwnedPlansAndAccessibleUnusedStops() throws Exception {
        Fixture f=fixture();f.trips.command(f.player,"save tour a b c");
        when(f.plugin.isEnabled()).thenReturn(true);when(f.plugin.getName()).thenReturn("VystormPortals");
        when(f.player.locale()).thenReturn(Locale.US);
        var field=PortalsPlugin.class.getDeclaredField("trips");field.setAccessible(true);field.set(f.plugin,f.trips);
        String english;
        try(var in=getClass().getResourceAsStream("/lang/en.yml")) {english=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var lang=Vystorm.vystorm_core.lang.Lang.create("VystormPortals",Map.of("en",english));
        var registration=Vystorm.vystorm_core.commands.CommandTesting.detached(f.plugin,lang,PortalsPlugin.commandSpec(f.plugin));
        assertEquals(List.of("save","list","show","start","confirm","cancel","delete"),registration.suggest(f.player,"vportal",new String[]{"trip",""}));
        assertEquals(List.of("tour"),registration.suggest(f.player,"vportal",new String[]{"trip","start","t"}));
        when(f.plugin.allowed(f.player,f.portals.get("c"))).thenReturn(false);
        assertEquals(List.of("b"),registration.suggest(f.player,"vportal",new String[]{"trip","save","new","a",""}));
        assertEquals(List.of(),registration.suggest(f.player,"vportal",new String[]{"trip","confirm",""}));
        Player other=mock(Player.class);when(other.getUniqueId()).thenReturn(UUID.randomUUID());when(other.locale()).thenReturn(Locale.US);
        when(other.hasPermission("vystormportals.use")).thenReturn(true);
        assertEquals(List.of(),registration.suggest(other,"vportal",new String[]{"trip","show",""}));
    }
    @Test void fullPreviewPrecedesConfirmationThenAllStopsAreVisitedInOrder() throws Exception {
        Fixture f=fixture();f.trips.command(f.player,"save tour a b c");f.trips.command(f.player,"start tour");
        assertEquals(3,Collections.frequency(f.messages,"trip-stop"));
        verify(f.player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
        f.trips.command(f.player,"confirm");assertTrue(f.trips.active(f.player));
        f.now.addAndGet(1000);f.trips.tick(20);assertEquals(16,f.location.get().getBlockX());
        f.now.addAndGet(4000);f.trips.tick(100);assertEquals(32,f.location.get().getBlockX());
        assertFalse(f.trips.active(f.player));assertTrue(f.messages.contains("trip-complete"));
        verify(f.player,times(2)).teleport(any(Location.class),eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }
    @Test void stalePreviewAndChangedAccessPreventTravel() throws Exception {
        Fixture f=fixture();f.trips.command(f.player,"save tour a b c");f.trips.command(f.player,"start tour");
        f.now.addAndGet(30000);f.trips.command(f.player,"confirm");assertFalse(f.trips.active(f.player));
        f.trips.command(f.player,"start tour");f.portals.put("b",f.portals.get("b").pause(true));
        f.trips.command(f.player,"confirm");assertFalse(f.trips.active(f.player));
        verify(f.player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void revokedLaterStopAccessAbortsBeforeAnyAdditionalTeleport() throws Exception {
        Fixture f=fixture();begin(f);f.now.addAndGet(1000);f.trips.tick(20);
        when(f.plugin.allowed(f.player,f.portals.get("c"))).thenReturn(false);f.now.addAndGet(4000);f.trips.tick(100);
        assertFalse(f.trips.active(f.player));assertEquals(16,f.location.get().getBlockX());
        verify(f.player,times(1)).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void movementExplicitCancellationAndTimeoutNeverTeleportAfterAbort() throws Exception {
        Fixture f=fixture();begin(f);f.location.get().add(2,0,0);f.trips.tick(20);assertFalse(f.trips.active(f.player));
        f.location.set(new Location(f.world,0,64,0));f.trips.command(f.player,"start tour");f.trips.command(f.player,"confirm");
        f.trips.command(f.player,"cancel");f.now.addAndGet(1000);f.trips.tick(40);assertFalse(f.trips.active(f.player));
        f.trips.command(f.player,"start tour");f.trips.command(f.player,"confirm");when(f.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        f.now.addAndGet(16000);f.trips.tick(60);assertFalse(f.trips.active(f.player));
        assertTrue(f.messages.contains("trip-timeout"));verify(f.player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void cancelledTeleportIsNeverRetriedAndUnsafeDestinationIsRejected() throws Exception {
        Fixture f=fixture();begin(f);when(f.player.teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class))).thenReturn(false);
        f.now.addAndGet(1000);f.trips.tick(20);f.trips.tick(40);assertFalse(f.trips.active(f.player));
        verify(f.player,times(1)).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
        f.trips.command(f.player,"start tour");f.trips.command(f.player,"confirm");when(f.plugin.safe(any(),eq(1.8))).thenReturn(false);
        f.now.addAndGet(1000);f.trips.tick(60);assertFalse(f.trips.active(f.player));
        verify(f.player,times(1)).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void savedPlansSurviveRestartButArePrivateAndActiveSessionsDoNotResume() throws Exception {
        Fixture f=fixture();begin(f);
        TripStore restored=new TripStore(folder.resolve("trips.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(List.of("a","b","c"),restored.get(f.player.getUniqueId(),"tour").stops());
        assertNull(restored.get(UUID.randomUUID(),"tour"));
        PortalTrips restarted=new PortalTrips(f.plugin,mock(DestinationChunks.class),f.now::get);
        assertFalse(restarted.active(f.player));restarted.command(f.player,"confirm");
        verify(f.player,never()).teleport(any(Location.class),any(PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void loopsTooManyStopsAndCorruptPersistenceAreRejectedWithoutOverwriting() throws Exception {
        UUID owner=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new PortalTrip("tour",owner,List.of("a","a")));
        assertThrows(IllegalArgumentException.class,()->new PortalTrip("../bad",owner,List.of("a","b")));
        assertThrows(IllegalArgumentException.class,()->new PortalTrip("tour",owner,List.of("a","b","c","d","e","f","g","h","i")));
        Path file=folder.resolve("trips.yml");Files.writeString(file,"schema: 99\n");
        assertThrows(Exception.class,()->new TripStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));assertEquals("schema: 99\n",Files.readString(file));
    }
}
