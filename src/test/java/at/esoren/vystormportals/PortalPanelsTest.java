package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.panel.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class PortalPanelsTest {
    @TempDir Path dir;
    private final PortalsPlugin plugin = mock(PortalsPlugin.class);
    private final Player player = mock(Player.class), target = mock(Player.class);
    private final PortalMenu chest = mock(PortalMenu.class);
    private final Panels api = mock(Panels.class);
    private final Server server = mock(Server.class);
    private final UUID owner = UUID.randomUUID(), targetId = UUID.randomUUID();
    private final AtomicReference<Map<String, Portal>> portals = new AtomicReference<>(new TreeMap<>());
    private MockedStatic<Panels> staticPanels;
    private PortalPanels panels;

    @BeforeEach void setup() throws Exception {
        when(plugin.core()).thenReturn(TestCore.api());
        staticPanels = mockStatic(Panels.class);
        staticPanels.when(() -> Panels.of(plugin)).thenReturn(api);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.trust()).thenReturn(new PortalsPlugin.Trust(true, 16, true));
        when(plugin.portals()).thenAnswer(i -> portals.get());
        when(plugin.playerName(any())).thenReturn("Owner");
        when(plugin.canEdit(any(), any())).thenCallRealMethod();
        when(plugin.allowed(any(), any())).thenAnswer(i -> {
            Player viewer = i.getArgument(0); Portal p = i.getArgument(1);
            return viewer.hasPermission("vystormportals.use") && (p.publicAccess() || p.owner().equals(viewer.getUniqueId()));
        });
        when(player.getUniqueId()).thenReturn(owner);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Alex");
        when(target.isOnline()).thenReturn(true);
        when(server.getPlayer(targetId)).thenReturn(target);
        when(plugin.styles()).thenReturn(new PortalStyles(dir.resolve("styles.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        when(plugin.hours()).thenReturn(new PortalHours(dir.resolve("hours.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        portals.get().put("market", PortalPanelTest.portal("market", owner));
        panels = new PortalPanels(plugin, PortalPanelTest.lang(), chest);
    }
    @AfterEach void cleanup() { staticPanels.close(); }

    @Test void givenPanelRegistration_whenFallbackRuns_thenOriginalChestOpensWithPermissionGuard() {
        panels.register(true);
        var spec = org.mockito.ArgumentCaptor.forClass(PanelSpec.class);
        verify(api).register(eq(PortalPanel.NAME), spec.capture());
        assertTrue(spec.getValue().access().test(player));
        spec.getValue().fallback().accept(player);
        verify(chest).open(player);
        when(player.hasPermission("vystormportals.menu")).thenReturn(false);
        assertFalse(spec.getValue().access().test(player));
        verify(api).register(eq(TrustPanel.NAME), any());
    }
    @Test void givenUnavailablePanel_whenOpened_thenCallerIsToldToUseFallback() {
        when(api.open(player, PortalPanel.NAME)).thenReturn(PanelOpenResult.NONE);
        assertFalse(panels.open(player));
    }
    @Test void givenPanelOpenFailure_whenMenuCommandRuns_thenOriginalChestIsOpened() throws Exception {
        var menuField = PortalsPlugin.class.getDeclaredField("menu");
        menuField.setAccessible(true); menuField.set(plugin, chest);
        var panelsField = PortalsPlugin.class.getDeclaredField("panelMenus");
        panelsField.setAccessible(true); panelsField.set(plugin, panels);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        doCallRealMethod().when(plugin).run(player, "menu");
        when(api.open(player, PortalPanel.NAME)).thenThrow(new IllegalStateException("panel failure"));
        plugin.run(player, "menu");
        verify(chest).open(player);
        when(player.hasPermission("vystormportals.menu")).thenReturn(false);
        plugin.run(player, "menu");
        verify(chest, times(1)).open(player);
    }
    @Test void givenStalePortalOrRevokedPermission_whenActionRuns_thenNoMutationOccurs() {
        assertFalse(panels.change(player, "gone", "delete", "").success());
        when(player.hasPermission("vystormportals.create")).thenReturn(false);
        when(player.hasPermission("vystormportals.admin")).thenReturn(false);
        assertFalse(panels.change(player, "market", "delete", "").success());
        panels.leave(player, "market", "preview");
        verifyNoInteractions(chest);
        verify(plugin, never()).run(any(), any(String[].class));
    }
    @Test void givenFreshSettings_whenChanged_thenOtherCurrentFieldsArePreservedAndValuesValidated() throws Exception {
        plugin.styles().set("market", new PortalStyles.Style("portal", "bell", "flash"));
        assertTrue(panels.change(player, "market", "particle", "glow").success());
        assertEquals(new PortalStyles.Style("glow", "bell", "flash"), plugin.styles().of("market"));
        plugin.hours().set("market", new PortalHours.Schedule(127, 500, 1200));
        assertTrue(panels.change(player, "market", "day-0", false).success());
        assertTrue(panels.change(player, "market", "time-close", 1300.0).success());
        assertEquals(new PortalHours.Schedule(126, 500, 1300), plugin.hours().of("market").orElseThrow());
        assertFalse(panels.change(player, "market", "warmup", Double.NaN).success());
        assertFalse(panels.change(player, "market", "warmup", 201.0).success());
        assertFalse(panels.change(player, "market", "time-open", 1.5).success());
        assertFalse(panels.change(player, "market", "particle", "invalid").success());
        assertTrue(panels.change(player, "market", "always", true).success());
        assertTrue(plugin.hours().of("market").isEmpty());
    }
    @Test void givenFailedCommand_whenPanelActionRuns_thenResultIsAnError() {
        assertFalse(panels.change(player, "market", "delete", "").success());
        verify(plugin).run(player, "delete", "market");
        doAnswer(i -> { portals.set(Map.of()); return null; }).when(plugin).run(player, "delete", "market");
        assertTrue(panels.change(player, "market", "delete", "").success());
    }
    @Test void givenManyOwnedPortals_whenTrustPagesChange_thenPortalsBeyondFortyFiveAreReachableAndOfflineTargetsRejectActions() {
        for (int i = 0; i < 60; i++) { String id = String.format(Locale.ROOT, "p%02d", i); portals.get().put(id, PortalPanelTest.portal(id, owner)); }
        panels.openTrust(player, target);
        assertTrue(panels.page(player, 6).success());
        assertTrue(panels.trustOverview(player).rows().stream().anyMatch(r -> r.id().equals("p59")));
        assertTrue(panels.choose(player, "p59").success());
        assertEquals("p59", panels.trustOverview(player).selected().id());
        when(target.isOnline()).thenReturn(false);
        assertFalse(panels.trust(player, "p59", true).success());
        verify(plugin, never()).run(any(), any(String[].class));
    }
    @Test void givenSelection_whenPlayerQuits_thenNavigationAndTrustTargetAreForgotten() {
        portals.get().put("z-last", PortalPanelTest.portal("z-last", owner));
        assertTrue(panels.choose(player, "portal", "z-last").success());
        assertEquals("z-last", panels.overview(player).selected().portal().id());
        panels.openTrust(player, target);
        panels.forget(owner);
        assertEquals("market", panels.overview(player).selected().portal().id());
        assertFalse(panels.trust(player, "market", true).success());
    }
}
