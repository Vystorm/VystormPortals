package at.esoren.vystormportals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortalHoursTest {
    // 2026-11-09 is a Monday.
    private static LocalDateTime at(int day, int hour, int minute) { return LocalDateTime.of(2026, 11, 8 + day, hour, minute); }

    @Test
    void givenWeekdayWindow_whenChecked_thenOpenOnlyInside() {
        PortalHours.Schedule s = new PortalHours.Schedule(0b0011111, 8 * 60, 18 * 60);
        assertTrue(s.openAt(at(1, 8, 0)));
        assertFalse(s.openAt(at(1, 18, 0)));
        assertFalse(s.openAt(at(6, 12, 0)), "Saturday closed");
        assertEquals(Duration.ofHours(2), s.untilChange(at(1, 16, 0)).orElseThrow());
        assertEquals(Duration.ofHours(14 + 48), s.untilChange(at(5, 18, 0)).orElseThrow(), "Friday evening until Monday 08:00");
    }

    @Test
    void givenOvernightWindow_whenChecked_thenRunsPastMidnight() {
        PortalHours.Schedule s = new PortalHours.Schedule(0b0000001, 22 * 60, 2 * 60);
        assertTrue(s.openAt(at(1, 23, 0)));
        assertTrue(s.openAt(at(2, 1, 30)), "Tuesday 01:30 still belongs to Monday night");
        assertFalse(s.openAt(at(2, 23, 0)));
        assertEquals(Duration.ofMinutes(30), s.untilChange(at(2, 1, 30)).orElseThrow());
    }

    @Test
    void givenAllDayEveryDay_whenAsked_thenNeverChanges() {
        assertTrue(new PortalHours.Schedule(0b1111111, 0, 0).untilChange(at(3, 12, 0)).isEmpty());
        assertTrue(new PortalHours.Schedule(0, 480, 600).untilChange(at(3, 12, 0)).isEmpty());
    }

    @Test
    void givenHours_whenSavedAndLoaded_thenSame(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("hours.yml");
        PortalHours.Schedule s = new PortalHours.Schedule(0b1100000, 9 * 60 + 15, 21 * 60);
        PortalHours.write(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, file, Map.of("hub", s));
        assertEquals(Map.of("hub", s), PortalHours.load(file));
        assertEquals("4 h 05 min", PortalHours.duration(Duration.ofMinutes(245)));
        assertEquals(555, PortalHours.parseClock("09:15", 0));
        assertEquals(7, PortalHours.parseClock("25:00", 7));
    }
}
