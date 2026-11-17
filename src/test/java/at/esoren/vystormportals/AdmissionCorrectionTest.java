package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

class AdmissionCorrectionTest {
    @Test
    void matchesOnlyTheExactPluginTeleport() {
        World world = mock(World.class);
        World otherWorld = mock(World.class);
        Player player = mock(Player.class);
        Location from = new Location(world, 1.5, 64, -2.5, 0, 0);
        Location target = new Location(world, 20.25, 72, -13.75, 35, -12);
        AdmissionCorrection correction = new AdmissionCorrection(target);

        assertTrue(correction.matches(event(player, from, target.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)));
        assertFalse(correction.matches(event(player, from, target.clone(), PlayerTeleportEvent.TeleportCause.COMMAND)));
        assertFalse(correction.matches(event(player, from, new Location(world, 20.25, 72, -13.5, 35, -12),
                PlayerTeleportEvent.TeleportCause.PLUGIN)));
        assertFalse(correction.matches(event(player, from, new Location(otherWorld, 20.25, 72, -13.75, 35, -12),
                PlayerTeleportEvent.TeleportCause.PLUGIN)));
        assertFalse(correction.matches(event(player, from, new Location(world, 20.25, 72, -13.75, 36, -12),
                PlayerTeleportEvent.TeleportCause.PLUGIN)));
        assertFalse(correction.matches(event(player, from, new Location(world, 20.25, 72, -13.75, 35, -11),
                PlayerTeleportEvent.TeleportCause.PLUGIN)));
        assertFalse(correction.matches(null));
    }

    private static PlayerTeleportEvent event(Player player, Location from, Location to,
                                               PlayerTeleportEvent.TeleportCause cause) {
        return new PlayerTeleportEvent(player, from, to, cause);
    }
}
