package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class DestinationChunksTest {
    private static final UUID WORLD_ID = UUID.fromString("a32e705e-314b-4934-9d88-65a3c251897e");

    @Test
    void givenDistinctViewFramesWhenTouchingThenCapsDemandAt72WithoutGeneration() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);

        for (int i = 0; i < 9; i++) {
            destinations.touch(frame(i * 64.0 + 8), i);
        }

        verify(fixture.world, times(72)).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        verify(fixture.world, never()).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        verify(fixture.world, never()).getChunkAt(anyInt(), anyInt());
        assertEquals(0, fixture.schedulerTasks.size());
        destinations.close();
    }

    @Test
    void givenExplicitGenerationRequestWhenTouchingThenRequestsNineGeneratingChunks() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);

        destinations.touch(WORLD_ID, new Point3(8, 64, 8), 1, true);

        verify(fixture.world, times(9)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        verify(fixture.world, never()).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        destinations.close();
    }

    @Test
    void givenPendingNonGeneratingRequestsWhenGenerationIsRequestedThenUpgradesOnceAndIgnoresStaleCompletions() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        ViewFrame frame = frame(8);
        destinations.touch(frame, 1);
        ChunkKey key = fixture.futures.keySet().iterator().next();

        fixture.futures.get(key).complete(mock(Chunk.class));
        assertEquals(1, fixture.schedulerTasks.size());
        destinations.touch(WORLD_ID, frame.origin(), 2, true);
        destinations.touch(WORLD_ID, frame.origin(), 3, true);

        verify(fixture.world, times(9)).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        verify(fixture.world, times(9)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        fixture.runNextSchedulerTask(); // callback from the replaced false request
        verify(fixture.world, never()).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));

        ChunkKey generatingKey = new ChunkKey(key.x, key.z, true);
        fixture.futures.get(generatingKey).complete(mock(Chunk.class));
        fixture.runNextSchedulerTask();
        verify(fixture.world, times(1)).addPluginChunkTicket(key.x, key.z, fixture.plugin);
        destinations.close();
    }

    @Test
    void givenSuccessfulAsyncLoadWhenTouchingThenTicketWaitsForScheduledCallbackAndExpires() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(frame(8), 10);
        Map.Entry<ChunkKey, CompletableFuture<Chunk>> selected = fixture.futures.entrySet().iterator().next();
        selected.getValue().complete(mock(Chunk.class));

        verify(fixture.world, never()).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        assertEquals(1, fixture.schedulerTasks.size());
        fixture.runNextSchedulerTask();
        verify(fixture.world).addPluginChunkTicket(selected.getKey().x, selected.getKey().z, fixture.plugin);

        destinations.expire(211);

        verify(fixture.world).removePluginChunkTicket(selected.getKey().x, selected.getKey().z, fixture.plugin);
        destinations.close();
    }

    @Test
    void givenAbsentOrFailedChunkWhenTouchedRepeatedlyThenDoesNotRequestItEveryTick() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        ViewFrame frame = frame(24);
        destinations.touch(frame, 0);
        List<CompletableFuture<Chunk>> requests = new ArrayList<>(fixture.futures.values());
        requests.get(0).complete(null);
        requests.get(1).completeExceptionally(new IllegalStateException("chunk load failed"));
        fixture.runNextSchedulerTask();
        fixture.runNextSchedulerTask();

        for (int tick = 1; tick <= 100; tick++) {
            destinations.touch(frame, tick);
        }

        verify(fixture.world, times(9)).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        assertEquals(0, fixture.schedulerTasks.size());
        destinations.close();
    }

    @Test
    void givenCloseRacingScheduledAndLateCompletionsThenReleasesTicketsAndNeverAcquiresMore() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(WORLD_ID, frame(40).origin(), 1, true);
        List<CompletableFuture<Chunk>> requests = new ArrayList<>(fixture.futures.values());

        requests.get(0).complete(mock(Chunk.class));
        fixture.runNextSchedulerTask();
        requests.get(1).complete(mock(Chunk.class)); // callback queued but close wins before it runs
        assertEquals(1, fixture.schedulerTasks.size());
        destinations.close();
        fixture.runNextSchedulerTask();
        requests.get(2).complete(mock(Chunk.class)); // completion arrives after close

        verify(fixture.world, times(1)).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        verify(fixture.world, times(1)).removePluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        assertEquals(0, fixture.schedulerTasks.size());
    }

    @Test
    void givenExplicitGeneratingRequestWhenPluginDisablesBeforeCompletionThenNeverAcquiresTicket() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(WORLD_ID, new Point3(8, 64, 8), 1, true);
        CompletableFuture<Chunk> completion = fixture.futures.values().iterator().next();

        when(fixture.plugin.isEnabled()).thenReturn(false);
        completion.complete(mock(Chunk.class));
        destinations.close();

        verify(fixture.world, never()).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        assertEquals(0, fixture.schedulerTasks.size());
    }

    @Test
    void givenChunkBecomingUnloadedBeforeCallbackThenDoesNotAcquireTicket() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(frame(56), 0);
        Map.Entry<ChunkKey, CompletableFuture<Chunk>> selected = fixture.futures.entrySet().iterator().next();
        selected.getValue().complete(mock(Chunk.class));
        when(fixture.world.isChunkLoaded(selected.getKey().x, selected.getKey().z)).thenReturn(false);

        fixture.runNextSchedulerTask();

        verify(fixture.world, never()).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        destinations.close();
    }

    @Test
    void sharedPreparationAndViewLeaseIsOnlyReleasedAfterBothOwnersFinish() {
        Fixture fixture = new Fixture();
        DestinationChunks destinations = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.hold(fixture.world, 1, 2);
        destinations.hold(fixture.world, 1, 2);
        destinations.release(fixture.world, 1, 2);
        verify(fixture.world, never()).removePluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        destinations.release(fixture.world, 1, 2);
        verify(fixture.world, times(1)).addPluginChunkTicket(1, 2, fixture.plugin);
        verify(fixture.world, times(1)).removePluginChunkTicket(1, 2, fixture.plugin);
    }

    @Test
    void preparationLimitsAsyncRequestsAndIgnoresCompletionsAfterClose() {
        Fixture fixture = new Fixture();
        when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        DestinationChunks leases = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        PortalPreparation preparation = new PortalPreparation(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, fixture.world, 0, 0, 16, leases);
        preparation.tick(); preparation.tick();
        verify(fixture.world, times(4)).getChunkAtAsync(anyInt(), anyInt(), eq(true));
        var requests = new ArrayList<>(fixture.futures.values());
        when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        requests.getFirst().complete(mock(Chunk.class)); fixture.runNextSchedulerTask();
        preparation.close();
        requests.get(1).complete(mock(Chunk.class)); fixture.runNextSchedulerTask();
        verify(fixture.world, times(1)).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        verify(fixture.world, times(1)).removePluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
    }

    @Test
    void loadedPreparationFinishesInOneTickWithoutAsyncRoundTrips() {
        Fixture fixture = new Fixture();
        DestinationChunks leases = new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        PortalPreparation preparation = new PortalPreparation(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, fixture.world, 0, 0, 128, leases);
        preparation.tick();
        org.junit.jupiter.api.Assertions.assertTrue(preparation.ready());
        verify(fixture.world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
        int count = PortalPreparation.area(0, 0, 128).size();
        verify(fixture.world, times(count)).addPluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
        preparation.close();
        verify(fixture.world, times(count)).removePluginChunkTicket(anyInt(), anyInt(), eq(fixture.plugin));
    }

    private static ViewFrame frame(double x) {
        return new ViewFrame(WORLD_ID, new Point3(x, 64, 8), 0, 2, 4);
    }

    @Test void failedRequestsAreRetriedAfterBackoffEvenWhileViewedContinuously() {
        Fixture fixture=new Fixture();DestinationChunks destinations=new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(frame(8),0);
        fixture.futures.values().forEach(future->future.completeExceptionally(new IllegalStateException("temporary failure")));
        while(!fixture.schedulerTasks.isEmpty())fixture.runNextSchedulerTask();
        fixture.futures.clear();
        for(int tick=1;tick<=201;tick++)destinations.touch(frame(8),tick);
        verify(fixture.world,times(18)).getChunkAtAsync(anyInt(),anyInt(),eq(false));
        destinations.close();
    }

    @Test void completedRequestCannotAcquireTicketsInAnUnloadedWorld() {
        Fixture fixture=new Fixture();DestinationChunks destinations=new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(frame(8),0);
        fixture.futures.values().iterator().next().complete(mock(Chunk.class));
        when(fixture.server.getWorld(WORLD_ID)).thenReturn(null);
        fixture.runNextSchedulerTask();
        verify(fixture.world,never()).addPluginChunkTicket(anyInt(),anyInt(),eq(fixture.plugin));
        destinations.close();
    }

    @Test void worldUnloadReleasesLeasesAndReloadRequestsFreshChunks() throws Exception {
        Fixture fixture=new Fixture();DestinationChunks destinations=new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        destinations.touch(frame(8),0);
        fixture.futures.values().iterator().next().complete(mock(Chunk.class));fixture.runNextSchedulerTask();
        destinations.hold(fixture.world,20,20);
        destinations.unloaded(new org.bukkit.event.world.WorldUnloadEvent(fixture.world));
        verify(fixture.world,times(2)).removePluginChunkTicket(anyInt(),anyInt(),eq(fixture.plugin));
        fixture.futures.clear();destinations.touch(frame(8),1);
        verify(fixture.world,times(18)).getChunkAtAsync(anyInt(),anyInt(),eq(false));
        destinations.close();
    }

    @Test void preparationStopsWhenItsDestinationWorldUnloads() {
        Fixture fixture=new Fixture();DestinationChunks leases=new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        PortalPreparation preparation=new PortalPreparation(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, fixture.world,0,0,16,leases);
        when(fixture.server.getWorld(WORLD_ID)).thenReturn(null);
        preparation.tick();
        org.junit.jupiter.api.Assertions.assertTrue(preparation.failed());
        verify(fixture.world,never()).addPluginChunkTicket(anyInt(),anyInt(),eq(fixture.plugin));
        verify(fixture.world,never()).getChunkAtAsync(anyInt(),anyInt(),anyBoolean());
        preparation.close();leases.close();
    }

    @Test void pendingPreparationCannotAcquireTicketsAfterWorldUnload() {
        Fixture fixture=new Fixture();DestinationChunks leases=new DestinationChunks(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        when(fixture.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
        PortalPreparation preparation=new PortalPreparation(fixture.plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, fixture.world,0,0,16,leases);
        preparation.tick();fixture.futures.values().iterator().next().complete(mock(Chunk.class));
        when(fixture.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(fixture.server.getWorld(WORLD_ID)).thenReturn(null);
        fixture.runNextSchedulerTask();
        org.junit.jupiter.api.Assertions.assertTrue(preparation.failed());
        verify(fixture.world,never()).addPluginChunkTicket(anyInt(),anyInt(),eq(fixture.plugin));
        preparation.close();leases.close();
    }

    private record ChunkKey(int x, int z, boolean generate) { }

    private static final class Fixture {
        final Plugin plugin = mock(Plugin.class);
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Map<ChunkKey, CompletableFuture<Chunk>> futures = new LinkedHashMap<>();
        final List<Runnable> schedulerTasks = new ArrayList<>();

        Fixture() {
            when(world.getUID()).thenReturn(WORLD_ID);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getServer()).thenReturn(server);
            when(server.getWorld(WORLD_ID)).thenReturn(world);
            when(server.getScheduler()).thenReturn(scheduler);
            when(world.getChunkAtAsync(anyInt(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
                ChunkKey key = new ChunkKey(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return futures.computeIfAbsent(key, ignored -> new CompletableFuture<>());
            });
            when(scheduler.runTask(eq(plugin), ArgumentMatchers.<Runnable>any())).thenAnswer(invocation -> {
                schedulerTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin))).thenReturn(true);
        }

        void runNextSchedulerTask() {
            schedulerTasks.remove(0).run();
        }
    }
}
