package at.esoren.vystormportals;

import Vystorm.vystorm_core.compat.ServerCompat;
import java.util.List;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Only the selected adapter links to the newer Paper API. */
interface PortalApi {
    PortalApi INSTANCE = select(ServerCompat.hasClass("org.bukkit.GameRules"), hasRespawnLookup(Player.class));

    List<GameRule<Integer>> delays();
    Location respawn(Player player, boolean resolve);

    static boolean hasRespawnLookup(Class<?> type) {
        try { type.getMethod("getRespawnLocation", boolean.class); return true; }
        catch (NoSuchMethodException missing) { return false; }
    }

    static PortalApi select(boolean gameRules, boolean respawnLookup) {
        if (gameRules && respawnLookup) {
            try {
                return (PortalApi) Class.forName("at.esoren.vystormportals.ModernPortalApi").getConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot load bundled Paper adapter", failure);
            }
        }
        return new LegacyPortalApi();
    }
}
