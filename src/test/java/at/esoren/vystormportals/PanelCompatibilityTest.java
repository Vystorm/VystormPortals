package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class PanelCompatibilityTest {
    @Test void givenCoreWithoutPanelClasses_whenPluginAndChestClassesLoad_thenPanelApiIsNotRequired() throws Exception {
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader withoutPanels = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("Vystorm.vystorm_core.panel.")) throw new ClassNotFoundException(name);
                if (!name.startsWith("at.esoren.vystormportals.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var in = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null) throw new ClassNotFoundException(name);
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        assertThrows(ClassNotFoundException.class, () -> withoutPanels.loadClass("Vystorm.vystorm_core.panel.Panel"));
        for (String name : new String[]{"PortalsPlugin", "PortalMenu", "TrustMenuEntries"}) {
            Class<?> type = Class.forName("at.esoren.vystormportals." + name, true, withoutPanels);
            assertTrue(type.getDeclaredMethods().length > 0, name);
        }
    }
}
