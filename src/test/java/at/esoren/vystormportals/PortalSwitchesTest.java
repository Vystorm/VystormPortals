package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core.visual.Point3;
import Vystorm.vystorm_core.visual.ViewFrame;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 1.24.0 feature switches: off stops the feature, on behaves as before, the reload hook applies them at once. */
class PortalSwitchesTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID(), worldId = UUID.randomUUID();

    private static YamlConfiguration shipped() { return YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")); }

    private static YamlConfiguration with(Object... pathValue) {
        YamlConfiguration config = shipped();
        for (int i = 0; i < pathValue.length; i += 2) config.set((String) pathValue[i], pathValue[i + 1]);
        return config;
    }

    private Portal portal(String id, String target) {
        return new Portal(id, owner, new ViewFrame(worldId, new Point3(.5, 64, .5), 0, 1.5, 3), target, true, 40);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = PortalsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        var field = PortalsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private PortalsPlugin plugin(YamlConfiguration config, Map<String, Portal> portals) throws Exception {
        PortalsPlugin plugin = mock(PortalsPlugin.class, CALLS_REAL_METHODS);
        doReturn(config).when(plugin).getConfig();
        doReturn(Logger.getLogger("switches-test")).when(plugin).getLogger();
        set(plugin, "portals", portals);
        set(plugin, "store", new PortalStore(directory.resolve("portals.yml")));
        set(plugin, "trust", new PortalsPlugin.Trust(true, 16, true));
        set(plugin, "core", TestCore.api());
        for (String field : List.of("views", "pending", "corrections", "cooldown")) set(plugin, field, new HashMap<>());
        return plugin;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        return player;
    }

    // ---- shipped file, old files, descriptions -------------------------------------------------------------------

    @Test void givenShippedConfig_whenRead_thenEverySwitchIsABoolean() {
        YamlConfiguration config = shipped();
        for (String key : PortalSwitches.all()) assertTrue(config.isBoolean(key), key);
        for (String key : PortalSwitches.NEW) assertTrue(config.getBoolean(key), key + " ships on");
        assertFalse(config.getBoolean("travel-gates.energy.enabled"), "energy still ships off");
    }

    @Test void givenEverySwitch_whenDescribed_thenAllSevenLanguagesHaveTheLine() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String key : PortalSwitches.all()) assertFalse(lang.getString(PortalSwitches.descriptionKey(key), "").isBlank(), code + " " + key);
            for (String key : PortalsPlaceholders.KEYS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder " + key);
            assertFalse(lang.getString(PortalSwitches.OFF, "").isBlank(), code + " off message");
        }
    }

    @Test void givenOldConfigWithoutNewKeys_whenRead_thenEverythingIsOn() {
        YamlConfiguration old = shipped();
        for (String key : PortalSwitches.NEW) old.set(key, null);
        for (String key : PortalSwitches.NEW) assertTrue(PortalSwitches.on(old, key), key);
        assertTrue(PortalSwitches.menu(old) && PortalSwitches.designer(old) && PortalSwitches.maintenance(old));
    }

    // ---- manual portals and maintenance --------------------------------------------------------------------------

    @Test void givenManualPortalsOff_whenPortalCommandsRun_thenOffMessageAndNothingChanges() throws Exception {
        Map<String, Portal> portals = Map.of("a", portal("a", "b"), "b", portal("b", "a"));
        PortalsPlugin off = plugin(with(PortalSwitches.MANUAL, false), portals);
        Player player = player();
        for (String[] args : List.of(new String[]{"list"}, new String[]{"create", "c"}, new String[]{"unlink", "a"},
                new String[]{"delete", "a"}, new String[]{"pause", "a"}, new String[]{"menu"})) off.run(player, args);
        verify(off, times(6)).say(player, PortalSwitches.OFF);
        assertEquals(portals, off.portals(), "saved portals stay");
        assertFalse(PortalSwitches.menu(off.getConfig()), "the portal menu needs manual portals");
        PortalsPlugin on = plugin(shipped(), portals);
        on.run(player, "list");
        verify(on, never()).say(player, PortalSwitches.OFF);
    }

    @Test void givenMaintenanceOff_whenPaused_thenPortalIsOpenAndPauseCommandIsOff() throws Exception {
        Portal paused = portal("a", "b").pause(true), b = portal("b", "a");
        PortalsPlugin on = plugin(shipped(), Map.of("a", paused, "b", b));
        assertFalse(on.routeOpenNow(paused, b), "maintenance on: paused stays closed");
        PortalsPlugin off = plugin(with(PortalSwitches.MAINTENANCE, false), new TreeMap<>(Map.of("a", paused, "b", b)));
        assertTrue(off.routeOpenNow(paused, b), "maintenance off: pause flags are ignored");
        assertFalse(off.routeOpenNow(paused, null));
        Player player = player();
        off.run(player, "resume", "a");
        verify(off).say(player, PortalSwitches.OFF);
        assertTrue(off.portals().get("a").paused(), "the flag stays saved");
    }

    @Test void givenFeaturesSwitched_whenTabCompleting_thenTheSameTreeFollowsTheSwitchAtOnce() throws Exception {
        YamlConfiguration config = with(PortalSwitches.TRIPS, false, PortalSwitches.HOMES, false, PortalSwitches.IMPORT, false);
        PortalsPlugin plugin = plugin(config, Map.of());
        var spec = PortalsPlugin.commandSpec(plugin);
        var gates = PortalsPlugin.gateCommands(plugin);
        List<String> switched = List.of("trip", "home", "sethome", "delhome", "homes", "import", "energy");
        for (String name : switched) {
            assertFalse(spec.children().get(name).visibleTo(null), name);
            assertFalse(spec.children().get(name).hidden(), name + " is not hidden for good");
        }
        for (String shown : List.of("list", "create", "warp", "warps", "menu", "pause"))
            assertTrue(spec.children().get(shown).visibleTo(null), shown);
        for (var root : gates) assertEquals(root.name().contains("warp"), root.visibleTo(null), "/" + root.name());
        config.set(PortalSwitches.TRIPS, true);
        config.set(PortalSwitches.HOMES, true);
        config.set(PortalSwitches.IMPORT, true);
        config.set(PortalSwitches.WARPS, false);
        for (String name : List.of("trip", "home", "sethome", "delhome", "homes", "import"))
            assertTrue(spec.children().get(name).visibleTo(null), name + " after switching on");
        assertFalse(spec.children().get("warp").visibleTo(null), "warp after switching off");
        for (var root : gates) assertEquals(root.name().contains("home"), root.visibleTo(null), "/" + root.name() + " after the switch");
    }

    @Test void givenReload_whenASwitchChanged_thenCommandListsAreRefreshedAndNothingIsRegisteredAgain() throws Exception {
        YamlConfiguration config = shipped();
        PortalsPlugin plugin = plugin(config, Map.of());
        var core = mock(Vystorm.vystorm_core.API.class, RETURNS_DEEP_STUBS);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        set(plugin, "core", core);
        doNothing().when(plugin).reloadConfig();
        Server server = mock(Server.class);
        when(server.getWorlds()).thenReturn(List.of());
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        doReturn(server).when(plugin).getServer();
        plugin.reloadSettings();
        verify(commands, never()).refresh();
        doAnswer(invocation -> { config.set(PortalSwitches.WARPS, false); return null; }).when(plugin).reloadConfig();
        plugin.reloadSettings();
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    // ---- designer, trips, gates, world links -----------------------------------------------------------------------

    @Test void givenDesignerTripsImportOff_whenAsked_thenOff() throws Exception {
        assertFalse(PortalSwitches.designer(with(PortalSwitches.DESIGNER, false)));
        assertFalse(PortalSwitches.designer(with(PortalSwitches.MANUAL, false)), "styles belong to manual portals");
        assertTrue(PortalSwitches.designer(shipped()));
        PortalsPlugin plugin = plugin(with(PortalSwitches.IMPORT, false), Map.of());
        Player admin = player();
        plugin.importHuskHomes(admin);
        verify(plugin).say(admin, PortalSwitches.OFF);
        assertEquals(PortalSwitches.MANUAL, plugin(with(PortalSwitches.MANUAL, false), Map.of()).switchOf("info"));
        assertNull(plugin(shipped(), Map.of()).switchOf("info"));
    }

    @Test void givenWorldLinksSwitch_whenRead_thenTopLevelBoolean() {
        assertTrue(shipped().isBoolean(PortalSwitches.WORLD_LINKS));
        assertFalse(PortalSwitches.on(with(PortalSwitches.WORLD_LINKS, false), PortalSwitches.WORLD_LINKS));
    }

    // ---- reload hook -------------------------------------------------------------------------------------------------

    @Test void givenReload_whenInstantTravelOrManualPortalsChange_thenAppliedAtOnce() throws Exception {
        YamlConfiguration config = shipped();
        PortalsPlugin plugin = plugin(config, Map.of());
        doNothing().when(plugin).reloadConfig();
        Server server = mock(Server.class);
        when(server.getWorlds()).thenReturn(List.of());
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        doReturn(server).when(plugin).getServer();
        plugin.reloadSettings();
        assertNotNull(get(plugin, "instantTravel"), "instant-travel on: active after the reload");
        assertNotNull(get(plugin, "entityTravel"));
        config.set("instant-travel", false);
        config.set(PortalSwitches.MANUAL, false);
        @SuppressWarnings("unchecked") Map<UUID, Object> pending = (Map<UUID, Object>) get(plugin, "pending");
        pending.put(owner, new Object());
        plugin.reloadSettings();
        assertNull(get(plugin, "instantTravel"), "instant-travel off: gamerules restored, listener gone");
        assertTrue(pending.isEmpty(), "manual portals off: waiting passages dropped");
        config.set("trust.enabled", false);
        plugin.reloadSettings();
        assertFalse(plugin.trust().enabled(), "trust read again");
    }

    // ---- placeholders ------------------------------------------------------------------------------------------------

    private final class Fake implements PortalsPlaceholders.Source {
        boolean gates = true, energy = true, homes = true, warps = true, manual = true;
        final Map<String, Portal> portals = Map.of("market", portal("market", null), "spawn", portal("spawn", null).pause(true));
        public boolean gatesOn() { return gates; }
        public boolean energyOn() { return energy; }
        public boolean homesOn() { return homes; }
        public boolean warpsOn() { return warps; }
        public boolean manualOn() { return manual; }
        public double energy(UUID player) { return 42.7; }
        public double energyMax() { return 100; }
        public long secondsUntilFull(UUID player) { return 75; }
        public int homes(UUID player) { return 2; }
        public int maxHomes(Player player) { return 3; }
        public int warps() { return 5; }
        public Map<String, Portal> portals() { return portals; }
        public boolean open(Portal portal) { return !portal.paused(); }
    }

    @Test void givenRegisteredValues_whenResolved_thenPlainTextAndEmptyWhenOff() {
        PlaceholderTesting testing = PlaceholderTesting.detached();
        Fake source = new Fake();
        PortalsPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormPortals"), null, source);
        var alex = PlaceholderTesting.viewer("Alex");
        assertEquals("42 100 43 true", testing.resolve(alex, "{portals:energy} {portals:energy_max} {portals:energy_percent} {portals:energy_enabled}"));
        assertEquals("2 5 2", testing.resolve(alex, "{portals:homes_count} {portals:warps_count} {portals:portals_total}"));
        assertEquals("true false", testing.resolve(alex, "%vystorm_portals_portal_open_market% {portals:portal_open:spawn}"));
        assertEquals("{portals:portal_open:nowhere}", testing.resolve(alex, "{portals:portal_open:nowhere}"), "unknown portal stays");
        source.energy = false;
        source.homes = false;
        source.warps = false;
        source.manual = false;
        assertEquals("[][][][][]false", testing.resolve(alex, "[{portals:energy}][{portals:homes_count}][{portals:warps_count}][{portals:portals_total}][{portals:portal_open:market}]{portals:energy_enabled}"));
        assertEquals("", testing.resolveOffThread(alex, "{portals:portals_total}"), "portals_total answers off the server thread");
    }
}
