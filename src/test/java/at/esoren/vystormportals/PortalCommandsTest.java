package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /vportal through Core's command tree with the real language files: names, routing, completion and help per language. */
class PortalCommandsTest {
    private static final String[] LANGUAGES = {"en", "de", "zh_cn"};
    private final List<String> chat = new ArrayList<>();
    private final PortalsPlugin plugin = mock(PortalsPlugin.class);

    private static String read(String name) throws Exception {
        try (InputStream in = PortalCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CommandRegistration registration(PortalsPlugin handlers) throws Exception {
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("VystormPortals");
        Lang lang = Lang.create("VystormPortals", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(owner, lang, PortalsPlugin.commandSpec(handlers));
    }

    private Player player(Locale locale) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration(null);
        String[] none = {""};
        assertEquals(List.of("list", "nearby", "trip", "menu", "info", "create", "link", "unlink", "delete", "access", "warmup", "trust", "untrust", "rename", "pause", "resume", "home", "sethome", "delhome", "homes", "warp", "setwarp", "delwarp", "warps", "energy", "import", "help"),
                r.suggest(player(Locale.US), "vportal", none));
        assertEquals(List.of("liste", "umgebung", "reise", "menü", "info", "erstellen", "verbinden", "trennen", "entfernen", "zugang", "wartezeit", "vertrauen", "entziehen", "umbenennen", "pausieren", "fortsetzen", "home", "sethome", "delhome", "homes", "warp", "setwarp", "delwarp", "warps", "energie", "import", "hilfe"),
                r.suggest(player(Locale.GERMANY), "portale", none));
        assertEquals(List.of("列表", "附近", "旅程", "菜单", "信息", "创建", "连接", "断开", "删除", "访问", "预热", "信任", "取消信任", "重命名", "暂停", "恢复", "家", "设置家", "删除家", "家列表", "传送点", "设置传送点", "删除传送点", "传送点列表", "能量", "导入", "帮助"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE), "传送门", none));
    }

    @Test
    void givenEveryOldEnglishPath_whenRun_thenSameEnglishActionReachesTheOldLogic() throws Exception {
        CommandRegistration r = registration(plugin);
        Player p = player(Locale.GERMANY);
        String[][] paths = {
                {}, {"list"}, {"nearby"}, {"menu"}, {"info", "a"}, {"create", "a"}, {"create", "a", "4"}, {"create", "a", "4", "5"},
                {"link", "a", "b"}, {"unlink", "a"}, {"delete", "a"}, {"access", "a", "public"}, {"access", "a", "PRIVATE"},
                {"warmup", "a", "40"}, {"trust", "a"}, {"trust", "a", "Bob"}, {"untrust", "a", "Bob"}, {"rename", "a", "b"}, {"pause", "a"}, {"resume", "a"}};
        String[][] expected = {
                {}, {"list"}, {"nearby"}, {"menu"}, {"info", "a"}, {"create", "a"}, {"create", "a", "4"}, {"create", "a", "4", "5"},
                {"link", "a", "b"}, {"unlink", "a"}, {"delete", "a"}, {"access", "a", "public"}, {"access", "a", "private"},
                {"warmup", "a", "40"}, {"trust", "a"}, {"trust", "a", "Bob"}, {"untrust", "a", "Bob"}, {"rename", "a", "b"}, {"pause", "a"}, {"resume", "a"}};
        for (int i = 0; i < paths.length; i++) {
            clearInvocations(plugin);
            r.execute(p, "vportal", paths[i]);
            verify(plugin).run(p, expected[i]);
        }
        assertEquals(List.of(), chat, "no error messages");
    }

    @Test
    void givenLocalizedNames_whenRun_thenEnglishIdsAndKeywords() throws Exception {
        CommandRegistration r = registration(plugin);
        Player de = player(Locale.GERMANY);
        r.execute(de, "portale", new String[]{"zugang", "a", "öffentlich"});
        verify(plugin).run(de, "access", "a", "public");
        r.execute(de, "portale", new String[]{"verbinden", "a", "b"});
        verify(plugin).run(de, "link", "a", "b");
        r.execute(de, "portale", new String[]{"pausieren", "a"});
        verify(plugin).run(de, "pause", "a");
        Player zh = player(Locale.SIMPLIFIED_CHINESE);
        r.execute(zh, "传送门", new String[]{"恢复", "a"});
        verify(plugin).run(zh, "resume", "a");
        r.execute(zh, "传送门", new String[]{"访问", "a", "私有"});
        verify(plugin).run(zh, "access", "a", "private");
        r.execute(player(Locale.US), "vportal", new String[]{"anlegen", "x"});
        verify(plugin).run(any(Player.class), eq("create"), eq("x"));
    }

    @Test
    void givenBadInput_whenRun_thenRefusedWithoutReachingTheLogic() throws Exception {
        CommandRegistration r = registration(plugin);
        r.execute(player(Locale.US), "vportal", new String[]{"create", "a", "9"});
        r.execute(player(Locale.US), "vportal", new String[]{"access", "a", "maybe"});
        r.execute(player(Locale.US), "vportal", new String[]{"link", "a"});
        verify(plugin, never()).run(any(Player.class), any(String[].class));
        assertFalse(chat.isEmpty());
    }

    @Test
    void givenConsole_whenRun_thenPlayersOnly() throws Exception {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        CommandRegistration r = registration(plugin);
        clearInvocations(plugin); // building the tree asks which subcommands are switched off (1.24.0)
        r.execute(console, "vportal", new String[]{"list"});
        r.execute(console, "vportal", new String[0]);
        assertEquals(List.of("Only players can do that.", "Only players can do that."), chat);
        verifyNoInteractions(plugin);
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration(null).execute(player(Locale.GERMANY), "portale", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/portale erstellen NAME [BREITE] [HÖHE] – Portal dort anlegen"), all);
        assertTrue(all.contains("/portale verbinden PORTAL ZIEL – Zwei deiner Portale"), all);
    }

    @Test
    void givenHelp_whenChinese_thenKeywordUsage() throws Exception {
        CommandRegistration r = registration(null);
        CommandSpec access = r.spec().children().get("access");
        assertEquals("/传送门 访问 传送门 公开|私有", r.usage(player(Locale.SIMPLIFIED_CHINESE), "传送门", access));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflictsAndRootAliases() throws Exception {
        CommandRegistration r = registration(null);
        assertEquals(List.of(), r.conflicts());
        assertEquals("portale", r.lang().raw("de", "commands.vportal.aliases"));
        assertEquals("传送门", r.lang().raw("zh_cn", "commands.vportal.aliases"));
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndADescriptionForEveryNode() throws Exception {
        Set<String> english = null;
        for (String code : LANGUAGES) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    PortalCommandsTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
            Set<String> keys = new TreeSet<>();
            for (String key : yaml.getKeys(true)) if (key.startsWith("commands.")) keys.add(key);
            if (english == null) english = keys; else assertEquals(english, keys, code);
            List<CommandSpec> nodes = new ArrayList<>();
            collect(PortalsPlugin.commandSpec(null), nodes);
            for (CommandSpec node : nodes) assertFalse(yaml.getString(node.langPath() + ".description", "").isBlank(), code + " " + node.langPath());
        }
    }

    private static void collect(CommandSpec node, List<CommandSpec> out) {
        out.add(node);
        for (CommandSpec child : node.children().values()) collect(child, out);
    }
}
