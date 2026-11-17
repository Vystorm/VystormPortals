package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PortalLangTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z0-9_-]+}");

    private static YamlConfiguration load(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PortalLangTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }

    private static String text(YamlConfiguration yaml, String key) {
        return yaml.isList(key) ? String.join("\n", yaml.getStringList(key)) : yaml.getString(key);
    }

    @Test
    void everyFormerConfigTextHasAnEnglishAndAGermanLanguageKey() throws Exception {
        for (String code : new String[]{"en", "de", "zh_cn"}) {
            YamlConfiguration lang = load(code);
            for (String key : PortalsPlugin.OLD_DEFAULTS.keySet()) assertTrue(lang.isString(key), code + " lacks " + key);
            for (String key : new String[]{"unlinked", "info"}) assertTrue(lang.isString(key), code + " lacks " + key);
        }
    }

    @Test
    void translationsHaveTheEnglishKeysPlaceholdersAndListSizes() {
        YamlConfiguration en = load("en");
        for (String code : new String[]{"de", "zh_cn"}) {
            YamlConfiguration other = load(code);
            assertEquals(en.getKeys(true), other.getKeys(true), code);
            for (String key : en.getKeys(true)) {
                if (en.isConfigurationSection(key)) continue;
                if (en.isList(key) && !key.startsWith("commands.")) assertEquals(en.getStringList(key).size(), other.getStringList(key).size(), code + " " + key);
                Set<String> a = new TreeSet<>(), b = new TreeSet<>();
                PLACEHOLDER.matcher(text(en, key)).results().forEach(m -> a.add(m.group()));
                PLACEHOLDER.matcher(text(other, key)).results().forEach(m -> b.add(m.group()));
                assertEquals(a, b, code + " " + key);
            }
        }
    }

    @Test
    void noRawArgumentTags() {
        for (String code : new String[]{"en", "de", "zh_cn"}) {
            YamlConfiguration yaml = load(code);
            for (String key : yaml.getKeys(true))
                if (!yaml.isConfigurationSection(key))
                    assertFalse(text(yaml, key).matches("(?s).*<(arg|argument)[:>].*"), code + " " + key);
        }
    }
}
