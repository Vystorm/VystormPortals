package at.esoren.vystormportals;

import java.util.*;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.event.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Removes native dwell time while retaining vanilla travel and cancellable portal events. */
final class InstantPortalTravel implements Listener, AutoCloseable {
    private final Map<World, Map<GameRule<Integer>, Integer>> previous = new HashMap<>();

    void apply(World world) {
        if (previous.containsKey(world)) return;
        Map<GameRule<Integer>, Integer> values = new HashMap<>();
        for (GameRule<Integer> rule : PortalApi.INSTANCE.delays()) {
            Integer old = world.getGameRuleValue(rule);
            if (old != null && old != 0 && world.setGameRule(rule, 0)) values.put(rule, old);
        }
        previous.put(world, values);
    }

    @EventHandler public void loaded(WorldLoadEvent event) { apply(event.getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unloaded(WorldUnloadEvent event) { restore(event.getWorld()); }

    private void restore(World world) {
        Map<GameRule<Integer>, Integer> values = previous.remove(world);
        if (values != null) values.forEach((rule, old) -> {
            if (Objects.equals(world.getGameRuleValue(rule), 0)) world.setGameRule(rule, old);
        });
    }

    @Override public void close() { for (World world : List.copyOf(previous.keySet())) restore(world); }
}
