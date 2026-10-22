package com.agenticai.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * The factories carry the Jackson 2 wire defaults into Jackson 3 (ADR-0012 P2): parity is asserted
 * on the observable behaviour (features that differ between the majors), plus behavioural round
 * trips.
 */
class JsonMappersTest {

    @Test
    void jackson2DefaultsHold() {
        for (ObjectMapper m :
                new ObjectMapper[] {
                    JsonMappers.plain(),
                    JsonMappers.withJavaTime(),
                    JsonMappers.lenient(),
                    JsonMappers.nonNull(),
                    JsonMappers.sortedMapKeys(),
                    JsonMappers.strictTrailingTokens()
                }) {
            assertTrue(
                    m.isEnabled(
                            tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS),
                    "dates as timestamps");
            assertTrue(
                    !m.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY),
                    "declaration order");
        }
        assertTrue(!JsonMappers.plain().isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        assertTrue(
                JsonMappers.plain().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(
                !JsonMappers.lenient()
                        .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(
                JsonMappers.strictTrailingTokens()
                        .isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        assertTrue(
                JsonMappers.sortedMapKeys()
                        .isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
    }

    @Test
    void everyCallReturnsAFreshMapper() {
        assertNotSame(JsonMappers.plain(), JsonMappers.plain());
        assertNotSame(JsonMappers.lenient(), JsonMappers.lenient());
    }

    record Point(int x, String label) {}

    @Test
    void plainRoundTripsRecordsAndTrees() throws JacksonException {
        ObjectMapper m = JsonMappers.plain();
        String json = m.writeValueAsString(new Point(3, "a"));
        assertEquals("{\"x\":3,\"label\":\"a\"}", json);
        assertEquals(new Point(3, "a"), m.readValue(json, Point.class));
        JsonNode tree = m.readTree("{\"a\":[1,2,{\"b\":null}]}");
        assertEquals("{\"a\":[1,2,{\"b\":null}]}", m.writeValueAsString(tree));
        assertThrows(
                UnrecognizedPropertyException.class,
                () -> m.readValue("{\"x\":1,\"zzz\":2}", Point.class));
    }

    @Test
    void lenientIgnoresUnknownProperties() throws JacksonException {
        assertEquals(
                new Point(1, null),
                JsonMappers.lenient().readValue("{\"x\":1,\"zzz\":2}", Point.class));
    }

    @Test
    void nonNullOmitsNullProperties() throws JacksonException {
        assertEquals("{\"x\":1}", JsonMappers.nonNull().writeValueAsString(new Point(1, null)));
        assertEquals(
                "{\"x\":1,\"label\":null}",
                JsonMappers.plain().writeValueAsString(new Point(1, null)));
    }

    @Test
    void sortedMapKeysOrdersMapEntries() throws JacksonException {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put("a", 2);
        assertEquals("{\"a\":2,\"b\":1}", JsonMappers.sortedMapKeys().writeValueAsString(map));
        assertEquals("{\"b\":1,\"a\":2}", JsonMappers.plain().writeValueAsString(map));
    }

    @Test
    void strictTrailingTokensRejectsTrailingContent() throws JacksonException {
        assertThrows(
                JacksonException.class, () -> JsonMappers.strictTrailingTokens().readTree("{} {}"));
        assertEquals("{}", JsonMappers.plain().readTree("{} {}").toString());
    }

    @Test
    void withJavaTimeWritesInstantsAndPlainCannot() throws JacksonException {
        Instant t = Instant.parse("2026-01-02T03:04:05Z");
        // With the module and default WRITE_DATES_AS_TIMESTAMPS: numeric seconds.nanos.
        assertEquals("1767323045.000000000", JsonMappers.withJavaTime().writeValueAsString(t));
        // Jackson 3 has java.time built in, so plain() can write it too (Jackson 2 could not).
        assertEquals("1767323045.000000000", JsonMappers.plain().writeValueAsString(t));
    }
}
