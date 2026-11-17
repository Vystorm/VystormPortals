package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Breaking or placing where a portal view is shown, called by Vystorm Core's real stage dispatcher: Portals denies in
 * PROTECT, so no feature (ACT) and no bookkeeping (OBSERVE) of another plugin sees the block behind the illusion.
 */
class PortalIllusionStagesTest {
    private final UUID viewer = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final Plugin feature = EventStageTesting.plugin("AFeaturePlugin");
    private PortalsPlugin portals;
    private Player player;
    private Block shown, real;

    @BeforeEach
    void setUp() throws Exception {
        portals = mock(PortalsPlugin.class, CALLS_REAL_METHODS);
        doReturn("VystormPortals").when(portals).getName();
        doReturn(true).when(portals).isEnabled();
        NativeEndViews endViews = mock(NativeEndViews.class);
        when(endViews.covers(eq(viewer), any())).thenAnswer(call -> call.<BlockPosition>getArgument(1).equals(new BlockPosition(3, 70, 3)));
        set("views", new HashMap<>());
        set("endViews", endViews);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewer);
        shown = block(3, 70, 3);
        real = block(9, 70, 9);
    }

    private void set(String name, Object value) throws Exception {
        Field field = PortalsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(portals, value);
    }

    private static Block block(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    private EventStageTesting portalsAndAFeaturePlugin() {
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        stages.handle(feature, BlockBreakEvent.class, EventStage.ACT, event -> calls.add("break act"));
        stages.handle(feature, BlockBreakEvent.class, EventStage.OBSERVE, event -> calls.add("break observe"));
        stages.handle(feature, BlockPlaceEvent.class, EventStage.ACT, event -> calls.add("place act"));
        stages.handle(feature, BlockPlaceEvent.class, EventStage.OBSERVE, event -> calls.add("place observe"));
        portals.handleBlockEvents(stages);
        return harness;
    }

    private BlockPlaceEvent placing(Block block) {
        return new BlockPlaceEvent(block, mock(BlockState.class), block, mock(ItemStack.class), player, true, EquipmentSlot.HAND);
    }

    @Test
    void givenBlockBehindAPortalView_whenBrokenOrPlaced_thenItIsDeniedBeforeAnyFeatureOrBookkeepingRuns() {
        EventStageTesting harness = portalsAndAFeaturePlugin();

        assertTrue(harness.fire(new BlockBreakEvent(shown, player)).isCancelled());
        assertTrue(harness.fire(placing(shown)).isCancelled());

        assertEquals(List.of(), calls);
    }

    @Test
    void givenBlockOutsideEveryView_whenBrokenOrPlaced_thenTheOtherHandlersRun() {
        EventStageTesting harness = portalsAndAFeaturePlugin();

        assertFalse(harness.fire(new BlockBreakEvent(real, player)).isCancelled());
        assertFalse(harness.fire(placing(real)).isCancelled());

        assertEquals(List.of("break act", "break observe", "place act", "place observe"), calls);
    }
}
