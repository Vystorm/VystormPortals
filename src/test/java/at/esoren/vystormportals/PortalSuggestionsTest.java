package at.esoren.vystormportals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalSuggestionsTest {
    private static final List<String> IDS=List.of("east","west");
    private static final List<String> VISIBLE=List.of("east","plaza","west");

    @Test void completesTheArgumentExpectedByEachAction() {
        assertEquals(List.of("public","private"),suggest("access","east",""));
        assertEquals(List.of("private"),suggest("ACCESS","east","PR"));
        assertEquals(IDS,suggest("delete",""));
        assertEquals(List.of("west"),suggest("link","east",""));
        assertEquals(List.of("west"),suggest("link","EAST","W"));
        assertEquals(IDS,suggest("unlink",""));
        assertEquals(List.of("plaza"),suggest("info","P"));
    }

    @Test void doesNotSuggestPortalNamesForNumericOrUnknownArguments() {
        assertEquals(List.of(),suggest("create","new",""));
        assertEquals(List.of(),suggest("warmup","east",""));
        assertEquals(List.of(),suggest("list",""));
        assertEquals(List.of(),suggest("unknown",""));
        assertEquals(List.of("link","list"),suggest("LI").stream().sorted().toList());
    }

    private static List<String> suggest(String... args) { return PortalsPlugin.suggestions(args,IDS,VISIBLE); }
}
