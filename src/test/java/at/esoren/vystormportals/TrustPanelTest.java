package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.panel.PanelTesting;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustPanelTest {
    @Test void givenSelectedPortal_whenRendered_thenTargetTrustAndPagingAreShown() throws Exception {
        var lang = PortalPanelTest.lang();
        var first = new TrustPanel.Row("home", false, true);
        var chosen = new TrustPanel.Row("market", true, false);
        var data = new TrustPanel.Overview("Alex", List.of(first, chosen), chosen, 1, 6);
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            var doc = PanelTesting.render(TrustPanel.panel((k, args) -> lang.plain(code, k, args), data, mock(TrustPanel.Actions.class)));
            assertTrue(PanelTesting.ids(doc).containsAll(List.of("trust-portals", "trusted", "previous", "next", "close")));
            assertTrue(PanelTesting.find(doc, "trusted").get("value").getAsBoolean());
            assertEquals("market", PanelTesting.find(doc, "trust-portals").get("selected").getAsString());
            assertTrue(doc.toString().contains("Alex"));
            assertFalse(doc.toString().contains("{player}") || doc.toString().contains("panel.trust-help"));
        }
    }
    @Test void givenNoOwnedPortals_whenRendered_thenTrustToggleIsAbsent() throws Exception {
        var lang = PortalPanelTest.lang();
        var data = new TrustPanel.Overview("Alex", List.of(), null, 0, 1);
        var doc = PanelTesting.render(TrustPanel.panel((k, args) -> lang.plain("en", k, args), data, mock(TrustPanel.Actions.class)));
        assertTrue(doc.toString().contains("No portals yet"));
        assertNull(PanelTesting.find(doc, "trusted"));
        assertNull(PanelTesting.find(doc, "next"));
    }
}
