package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortalTest {
    private static final UUID OWNER = UUID.fromString("ae77dd8b-3bb4-4e79-9f54-900477d10230");
    private static final UUID WORLD = UUID.fromString("02e66380-761b-4e67-953a-363fa21a3eb8");

    @Test
    void givenPortalWhenChangingSettingsThenReturnsValidatedCopies() {
        Portal original = portal("west_gate", null, false, 0);

        assertEquals(new Portal("west_gate", OWNER, frame(2), null, false, 0), original);
        assertEquals(new Portal("west_gate", OWNER, frame(2), "east_gate", false, 0), original.link("east_gate"));
        assertEquals(new Portal("west_gate", OWNER, frame(2), null, true, 0), original.access(true));
        assertEquals(new Portal("west_gate", OWNER, frame(2), null, false, 200), original.warmup(200));
    }

    @Test
    void givenInvalidIdentityLinkOrWarmupWhenCreatingPortalThenRejectsIt() {
        assertThrows(IllegalArgumentException.class, () -> portal("Bad Name", null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> portal("valid", "Bad Target", false, 0));
        assertThrows(IllegalArgumentException.class, () -> portal("same", "same", false, 0));
        assertThrows(IllegalArgumentException.class, () -> portal("valid", null, false, -1));
        assertThrows(IllegalArgumentException.class, () -> portal("valid", null, false, 201));
        assertEquals(0, portal("minimum", null, true, 0).warmupTicks());
        assertEquals(200, portal("maximum", null, true, 200).warmupTicks());
    }

    private static Portal portal(String id, String target, boolean publicAccess, int warmup) {
        return new Portal(id, OWNER, frame(2), target, publicAccess, warmup);
    }

    private static ViewFrame frame(int turns) {
        return new ViewFrame(WORLD, new Point3(123.5, 72, -81.25), turns, 1.75, 3.5);
    }
}
