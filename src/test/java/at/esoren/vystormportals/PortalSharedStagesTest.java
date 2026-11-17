package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Clicks and teleports of the portal plugin, called by Vystorm Core's real stage dispatcher next to a second plugin:
 * a click behind a portal view is denied in PROTECT; a teleport is only read in OBSERVE, so a teleport another plugin
 * denied closes nothing, and the teleport itself is never changed.
 */
class PortalSharedStagesTest {
    private final UUID viewer = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final NativeEndViews endViews = mock(NativeEndViews.class);
    private final NativeNetherViews netherViews = mock(NativeNetherViews.class);
    private final NativeGatewayViews gatewayViews = mock(NativeGatewayViews.class);
    private final TravelGates gates = mock(TravelGates.class);
    private final Map<UUID, Object> pending = new HashMap<>();
    private final Map<UUID, Object> corrections = new HashMap<>();
    private final Location from = new Location(null, 0, 64, 0), to = new Location(null, 40, 64, 0);
    private PortalsPlugin portals;
    private Player player;

    @BeforeEach
    void setUp() throws Exception {
        portals = mock(PortalsPlugin.class, CALLS_REAL_METHODS);
        doReturn("VystormPortals").when(portals).getName();
        doReturn(true).when(portals).isEnabled();
        when(endViews.covers(eq(viewer), any())).thenAnswer(call -> call.<BlockPosition>getArgument(1).equals(new BlockPosition(3, 70, 3)));
        set("views", new HashMap<>());
        set("pending", pending);
        set("corrections", corrections);
        set("cooldown", new HashMap<>());
        set("endViews", endViews);
        set("netherViews", netherViews);
        set("gatewayViews", gatewayViews);
        set("gates", gates);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewer);
    }

    private void set(String name, Object value) throws Exception {
        Field field = PortalsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(portals, value);
    }

    private EventStageTesting portalsAndAnotherPlugin(Class<? extends Event> type) {
        EventStageTesting stages = EventStageTesting.detached();
        stages.handle(other, type, EventStage.ADJUST, event -> calls.add("adjust"));
        stages.handle(other, type, EventStage.ACT, event -> calls.add("act"));
        stages.handle(other, type, EventStage.OBSERVE, event -> calls.add("observe"));
        portals.handleSharedEvents(stages.stages());
        return stages;
    }

    private PlayerInteractEvent click(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
    }

    @Test
    void givenBlockBehindAPortalView_whenClicked_thenItIsDeniedBeforeAnyFeatureOrBookkeepingRuns() {
        PlayerInteractEvent event = portalsAndAnotherPlugin(PlayerInteractEvent.class).fire(click(3, 70, 3));

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(List.of(), calls);
    }

    @Test
    void givenBlockOutsideEveryView_whenClicked_thenTheOtherHandlersRun() {
        PlayerInteractEvent event = portalsAndAnotherPlugin(PlayerInteractEvent.class).fire(click(9, 70, 9));

        assertEquals(Event.Result.ALLOW, event.useInteractedBlock());
        assertEquals(List.of("adjust", "act", "observe"), calls);
    }

    @Test
    void givenClickAlreadyDeniedForTheBlock_thenTheIllusionCheckIsSkippedAsBefore() {
        PlayerInteractEvent event = click(3, 70, 3);
        event.setUseInteractedBlock(Event.Result.DENY);

        portalsAndAnotherPlugin(PlayerInteractEvent.class).fire(event);

        verifyNoInteractions(endViews);
    }

    @Test
    void givenOpenViewsAndAPendingPassage_whenAnotherPluginDeniesTheTeleport_thenNothingIsClosed() {
        pending.put(viewer, new Object());
        AdmissionCorrection correction = new AdmissionCorrection(new Location(null, 7, 64, 7));
        corrections.put(viewer, correction);
        EventStageTesting stages = portalsAndAnotherPlugin(PlayerTeleportEvent.class);
        stages.handle(other, PlayerTeleportEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        PlayerTeleportEvent event = stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND));

        assertTrue(event.isCancelled());
        verify(endViews, never()).close(any());
        verify(netherViews, never()).close(any());
        verify(gatewayViews, never()).close(any());
        verify(gates, never()).release(any(UUID.class));
        assertTrue(pending.containsKey(viewer), "the passage is still pending");
        assertSame(correction, corrections.get(viewer), "the one-shot correction is not used up by a denied teleport");
    }

    @Test
    void givenOpenViewsAndAPendingPassage_whenAForeignTeleportHappens_thenEverythingIsClosedAndTheTeleportIsUntouched() {
        pending.put(viewer, new Object());
        EventStageTesting stages = portalsAndAnotherPlugin(PlayerTeleportEvent.class);

        PlayerTeleportEvent event = stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND));

        assertFalse(event.isCancelled());
        assertEquals(to, event.getTo());
        assertEquals(from, event.getFrom());
        verify(endViews).close(viewer);
        verify(netherViews).close(viewer);
        verify(gatewayViews).close(viewer);
        verify(gates).release(viewer);
        assertFalse(pending.containsKey(viewer));
        assertEquals(List.of("adjust", "act", "observe"), calls);
    }

    @Test
    void givenAdmissionCorrection_whenExactlyThatPluginTeleportArrives_thenThePassageStaysPending() {
        pending.put(viewer, new Object());
        corrections.put(viewer, new AdmissionCorrection(to.clone()));
        EventStageTesting stages = portalsAndAnotherPlugin(PlayerTeleportEvent.class);

        PlayerTeleportEvent event = stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.PLUGIN));

        assertFalse(event.isCancelled());
        verify(endViews, never()).close(any());
        verify(gates, never()).release(any(UUID.class));
        assertTrue(pending.containsKey(viewer));
        assertFalse(corrections.containsKey(viewer), "the correction is one-shot");

        stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.PLUGIN));
        verify(endViews).close(viewer);
        assertFalse(pending.containsKey(viewer), "a second teleport to the same spot is a foreign one");
    }

    @Test
    void givenPortalAndItineraryHandlers_thenThePluginHandlerRunsFirstAsBefore() throws Exception {
        PortalTrips trips = mock(PortalTrips.class, CALLS_REAL_METHODS);
        for (var field : Map.of("plugin", portals, "active", new HashMap<>(), "moving", new java.util.HashSet<>()).entrySet()) {
            Field target = PortalTrips.class.getDeclaredField(field.getKey());
            target.setAccessible(true);
            target.set(trips, field.getValue());
        }
        EventStageTesting stages = EventStageTesting.detached();
        portals.handleSharedEvents(stages.stages());
        trips.handleSharedEvents(stages.stages());

        PlayerTeleportEvent event = stages.fire(new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND));

        var order = org.mockito.Mockito.inOrder(portals, trips);
        order.verify(portals).teleport(event);
        order.verify(trips).teleport(event);
        assertTrue(stages.handlers(PlayerTeleportEvent.class).stream()
                .allMatch(handler -> handler.stage() == EventStage.OBSERVE && !handler.receivesCancelled()));
    }
}
