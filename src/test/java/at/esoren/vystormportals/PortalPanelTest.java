package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PortalPanelTest {
    static Lang lang() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            try (var in = PortalPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Lang.create("vystormportals", files);
    }
    static Portal portal(String id, UUID owner) {
        return new Portal(id, owner, new ViewFrame(UUID.randomUUID(), new Point3(0.5, 64, 0.5), 0, 1.5, 3), "home", true, 40);
    }
    private PortalPanel.Row row(boolean editable, PortalHours.Schedule hours) {
        return new PortalPanel.Row(portal("market", UUID.randomUUID()), "Overworld", "Alex", editable,
                new PortalStyles.Style("end_rod", "bell", "sparkle"), hours);
    }
    private JsonObject render(PortalPanel.Row r, String code) throws Exception {
        var o = new PortalPanel.Overview(List.of(r), r, 1, 3, r.editable(), List.of(r), "home", 0, 2);
        Lang lang = lang();
        return PanelTesting.render(PortalPanel.panel((k, args) -> lang.plain(code, k, args), o, mock(PortalPanel.Actions.class)));
    }
    @Test void givenEditablePortal_whenRendered_thenAllMenusHaveControlsAndDestructiveConfirmations() throws Exception {
        JsonObject doc = render(row(true, PortalHours.Schedule.DEFAULT), "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("portals", "details", "public", "paused", "warmup", "delete", "targets",
                "link", "unlink", "particle", "sound", "arrival", "preview", "always", "day-0", "day-6", "time-open", "time-close", "create", "close", "page-next", "page-previous", "target-page-next")));
        for (String id : List.of("delete", "link", "unlink")) assertNotNull(PanelTesting.find(doc, id).get("confirm"), id);
        assertEquals("number", PanelTesting.find(doc, "warmup").get("type").getAsString());
        String text = doc.toString();
        for (String value : List.of("Overworld", "Alex", "market", "White sparks", "Bell echo", "Opens at 08:00", "Closes at 22:00")) assertTrue(text.contains(value), text);
    }
    @Test void givenVisitor_whenRendered_thenOnlyDetailsAndLocateAreAvailable() throws Exception {
        var ids = PanelTesting.ids(render(row(false, null), "en"));
        assertTrue(ids.containsAll(List.of("portals", "details", "locate", "close")));
        for (String id : List.of("delete", "create", "public", "warmup", "targets", "particle", "always")) assertFalse(ids.contains(id), id);
    }
    @Test void givenAlwaysOpenPortal_whenRendered_thenDayAndTimeControlsAreHidden() throws Exception {
        var doc = render(row(true, null), "en");
        assertTrue(PanelTesting.find(doc, "always").get("value").getAsBoolean());
        assertNull(PanelTesting.find(doc, "day-0"));
        assertNull(PanelTesting.find(doc, "time-open"));
    }
    @Test void givenEmptyOverview_whenRendered_thenEmptyStateHasNoPortalActions() throws Exception {
        Lang lang = lang();
        var o = new PortalPanel.Overview(List.of(), null, 0, 1, false, List.of(), null, 0, 1);
        var doc = PanelTesting.render(PortalPanel.panel((k, args) -> lang.plain("en", k, args), o, mock(PortalPanel.Actions.class)));
        assertTrue(doc.toString().contains("No portals yet"));
        assertNull(PanelTesting.find(doc, "delete"));
        assertNull(PanelTesting.find(doc, "page-next"));
    }
    @Test void givenEveryLanguage_whenRendered_thenAllTabsValidateWithoutUnresolvedKeysOrPlaceholders() throws Exception {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            String doc = render(row(true, PortalHours.Schedule.DEFAULT), code).toString();
            assertFalse(doc.contains("panel.") || doc.contains("{name}") || doc.contains("{value}") || doc.contains("{time}"), code + doc);
        }
    }
    @Test void givenLargeCollection_whenPaged_thenEveryRowIsReachableAndShrinkingPagesClamp() {
        List<Integer> rows = IntStream.range(0, 256).boxed().toList();
        List<Integer> seen = new ArrayList<>();
        for (int page = 0; page < PortalPanel.pages(rows.size()); page++) {
            var slice = PortalPanel.slice(rows, page); assertTrue(slice.size() <= 9); seen.addAll(slice);
        }
        assertEquals(rows, seen);
        assertEquals(List.of(1), PortalPanel.slice(List.of(1), 90));
        assertEquals(List.of(), PortalPanel.slice(List.of(), -1));
    }
}
