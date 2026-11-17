package at.esoren.vystormportals;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.entity.Player;

final class LegacyPortalApi implements PortalApi {
    @Override public List<GameRule<Integer>> delays() {
        return Stream.of("playersNetherPortalDefaultDelay", "playersNetherPortalCreativeDelay")
                .map(GameRule::getByName).filter(Objects::nonNull).filter(rule -> rule.getType() == Integer.class)
                .map(LegacyPortalApi::integerRule).toList();
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Integer> integerRule(GameRule<?> rule) { return (GameRule<Integer>) rule; }

    @Override public Location respawn(Player player, boolean resolve) {
        // Older Paper exposes only the resolving lookup; it may synchronously load the respawn chunk.
        return player.getRespawnLocation();
    }
}
