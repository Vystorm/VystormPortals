package at.esoren.vystormportals;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Prepare only a completely empty first-arrival volume; never clear existing player blocks for a preview. */
final class EndArrivalPlatform {
    private final JavaPlugin plugin;
    private final Set<UUID> attempted = new HashSet<>();
    private boolean unavailable;
    EndArrivalPlatform(JavaPlugin plugin) { this.plugin = plugin; }

    void prepare(World world, Player actor) {
        if (!plugin.getConfig().getBoolean("end-portals.prepare-arrival-platform", true)
                || unavailable || attempted.contains(world.getUID()) || !emptyAndLoaded(world)) return;
        attempted.add(world.getUID());
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            ClassLoader loader = level.getClass().getClassLoader();
            Object pos = Class.forName("net.minecraft.core.BlockPos", true, loader).getConstructor(int.class, int.class, int.class).newInstance(100, 49, 0);
            Object entity = actor.getClass().getMethod("getHandle").invoke(actor);
            Class<?> feature;
            try { feature = Class.forName("net.minecraft.world.level.levelgen.feature.EndPlatformFeature", true, loader); }
            catch (ClassNotFoundException olderPaper) {
                legacyPlatformMethod(level.getClass()).invoke(null, level, entity); return;
            }
            for (Method method : feature.getMethods()) if (method.getName().equals("createEndPlatform") && method.getParameterCount() == 4) {
                // The entity-bearing Paper overload emits the cancellable END_PLATFORM PortalCreateEvent.
                method.invoke(null, level, pos, false, entity); return;
            }
            throw new NoSuchMethodException("Paper EndPlatformFeature.createEndPlatform");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            unavailable = true;
            plugin.getLogger().warning("End arrival preview preparation unavailable; vanilla arrival retained: " + failure);
        }
    }

    static Method legacyPlatformMethod(Class<?> level) throws NoSuchMethodException {
        for (Method method : level.getMethods())
            if (method.getName().equals("makeObsidianPlatform") && method.getParameterCount() == 2
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())) return method;
        throw new NoSuchMethodException("Paper ServerLevel.makeObsidianPlatform with entity");
    }

    static boolean emptyAndLoaded(World world) {
        if (world.getEnvironment() != World.Environment.THE_END || world.getMinHeight() > 48 || world.getMaxHeight() <= 51) return false;
        for (int x = 98; x <= 102; x++) for (int z = -2; z <= 2; z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            for (int y = 48; y <= 51; y++) {
                Material type = world.getBlockAt(x, y, z).getType();
                if (type != Material.AIR && type != Material.CAVE_AIR && type != Material.VOID_AIR) return false;
            }
        }
        return true;
    }
}
