package at.esoren.vystormportals;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/** Resolve the normal dimension family without guessing between unrelated multiworld instances. */
final class NativeWorlds {
    private NativeWorlds() {}

    static World destination(World source, World.Environment environment, List<World> worlds, String configured) {
        if (configured != null && !configured.isBlank()) {
            return worlds.stream().filter(world -> world.getName().equals(configured)
                    && world.getEnvironment() == environment).findFirst().orElse(null);
        }
        String family = source.getName().replaceFirst("_(nether|the_end)$", "");
        String name = family + switch (environment) {
            case NETHER -> "_nether";
            case THE_END -> "_the_end";
            default -> "";
        };
        for (World world : worlds)
            if (world.getEnvironment() == environment && world.getName().equals(name)) return world;
        // Canonical dimension IDs are safe only when the source belongs to the vanilla family.
        var canonical = List.of(NamespacedKey.minecraft("overworld"), NamespacedKey.minecraft("the_nether"), NamespacedKey.minecraft("the_end"));
        String dimension = switch (environment) { case NETHER -> "the_nether"; case THE_END -> "the_end"; default -> "overworld"; };
        if (canonical.contains(source.getKey())) for (World world : worlds)
            if (world.getEnvironment() == environment && NamespacedKey.minecraft(dimension).equals(world.getKey())) return world;
        return null;
    }
}
