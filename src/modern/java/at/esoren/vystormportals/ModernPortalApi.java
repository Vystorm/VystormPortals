package at.esoren.vystormportals;

import java.util.List;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class ModernPortalApi implements PortalApi {
    @Override public List<GameRule<Integer>> delays() {
        return List.of(GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY, GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY);
    }

    @Override public Location respawn(Player player, boolean resolve) { return player.getRespawnLocation(resolve); }
}
