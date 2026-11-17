package at.esoren.vystormportals;

import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Recognizes only the exact correction requested by a portal admission move event. */
record AdmissionCorrection(Location target) {
    boolean matches(PlayerTeleportEvent event) {
        return event != null && event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                && target.equals(event.getTo());
    }
}
