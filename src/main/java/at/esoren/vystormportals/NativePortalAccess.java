package at.esoren.vystormportals;

import Vystorm.vystorm_core.visual.BlockPosition;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Isolated Mojang-mapped Paper adapter. Never teleports; Paper owns portal selection and creation events. */
final class NativePortalAccess {
    private final Vystorm.vystorm_core.compat.WorldCompatInterface worlds;
    /** @param worlds Core's world part, {@code api.compat().world()} */
    NativePortalAccess(Vystorm.vystorm_core.compat.WorldCompatInterface worlds) { this.worlds = worlds; }
    record Plan(World world, Object level, Object position, Object border, Object forcer,
                int x, int z, int searchRadius, int createRadius) {}

    Plan plan(World source, World target, Location entrance) throws ReflectiveOperationException {
        Object level = target.getClass().getMethod("getHandle").invoke(target);
        Object sourceLevel = source.getClass().getMethod("getHandle").invoke(source);
        Object environment = environment(level), sourceEnvironment = environment(sourceLevel);
        int search = environment.getClass().getField("portalSearchRadius").getInt(environment);
        if (target.getEnvironment() == World.Environment.NETHER
                && sourceEnvironment.getClass().getField("portalSearchVanillaDimensionScaling").getBoolean(sourceEnvironment))
            search = (int) (search / worlds.coordinateScale(target));
        int create = environment.getClass().getField("portalCreateRadius").getInt(environment);
        if (search < 0 || search > 128 || create < 0 || create > 32)
            throw new IllegalStateException("Portal search/create radius exceeds preview preparation limit (128/32)");
        double scale = worlds.coordinateScale(source) / worlds.coordinateScale(target);
        Object border = level.getClass().getMethod("getWorldBorder").invoke(level);
        Object position = border.getClass().getMethod("clampToBounds", double.class, double.class, double.class)
                .invoke(border, entrance.getX() * scale, entrance.getY(), entrance.getZ() * scale);
        Object forcer = level.getClass().getMethod("getPortalForcer").invoke(level);
        return new Plan(target, level, position, border, forcer, coordinate(position, "getX"), coordinate(position, "getZ"), search, create);
    }

    private static Object environment(Object level) throws ReflectiveOperationException {
        Object config = level.getClass().getMethod("paperConfig").invoke(level);
        return config.getClass().getField("environment").get(config);
    }

    BlockPosition resolve(Plan plan, NetherSurface entrance, Player actor, boolean create) throws ReflectiveOperationException {
        Method search = searchMethod(plan.forcer.getClass());
        Optional<?> found = (Optional<?>) search.invoke(plan.forcer, plan.position, plan.border, plan.searchRadius);
        Object position = found.orElse(null);
        if (position != null && search.getName().equals("findPortalAround"))
            position = position.getClass().getField("minCorner").get(position);
        if (position == null && create) {
            Object entity = actor.getClass().getMethod("getHandle").invoke(actor);
            Method creator = null;
            for (Method method : plan.forcer.getClass().getMethods())
                if (method.getName().equals("createPortal") && method.getParameterCount() == 4) { creator = method; break; }
            if (creator == null) throw new NoSuchMethodException("Paper PortalForcer.createPortal");
            Object axis = null;
            for (Object constant : creator.getParameterTypes()[1].getEnumConstants())
                if (((Enum<?>) constant).name().equals(entrance.axis().name())) axis = constant;
            Optional<?> rectangle = (Optional<?>) creator.invoke(plan.forcer, plan.position, axis, entity, plan.createRadius);
            if (rectangle.isPresent()) position = rectangle.get().getClass().getField("minCorner").get(rectangle.get());
        }
        return position == null ? null : new BlockPosition(coordinate(position, "getX"), coordinate(position, "getY"), coordinate(position, "getZ"));
    }

    static Method searchMethod(Class<?> forcer) throws NoSuchMethodException {
        for (String name : new String[]{"findClosestPortalPosition", "findPortalAround"})
            for (Method method : forcer.getMethods())
                if (method.getName().equals(name) && method.getParameterCount() == 3
                        && method.getParameterTypes()[2] == int.class) return method;
        throw new NoSuchMethodException("Paper PortalForcer portal search");
    }

    private static int coordinate(Object position, String getter) throws ReflectiveOperationException {
        return (int) position.getClass().getMethod(getter).invoke(position);
    }
}
