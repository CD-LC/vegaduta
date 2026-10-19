package com.agenticai.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Each factory must be byte-behaviour-identical to the inline construction it replaced (ADR-0012 P1b).
 * Parity is asserted by comparing the full effective configuration (every feature flag, registered
 * modules, default inclusion) with a mapper built the old way, plus behavioural round trips.
 */
class JsonMappersTest {

    /** Everything observable about a mapper's configuration. */
    private static Map<String, Object> describe(ObjectMapper m) {
        Map<String, Object> d = new TreeMap<>();
        for (DeserializationFeature f : DeserializationFeature.values()) {
            d.put("D." + f, m.isEnabled(f));
        }
        for (SerializationFeature f : SerializationFeature.values()) {
            d.put("S." + f, m.isEnabled(f));
        }
        for (MapperFeature f : MapperFeature.values()) {
            d.put("M." + f, m.isEnabled(f));
        }
        d.put("modules", m.getRegisteredModuleIds().toString());
        d.put("inclusion", m.getSerializationConfig().getDefaultPropertyInclusion().toString());
        return d;
    }

    private static void assertSameConfig(ObjectMapper expected, ObjectMapper actual) {
        assertEquals(describe(expected), describe(actual));
    }

    @Test
    void plainEqualsNewObjectMapper() {
        assertSameConfig(new ObjectMapper(), JsonMappers.plain());
        assertTrue(JsonMappers.plain().getRegisteredModuleIds().isEmpty(), "plain() registers no modules");
    }

    @Test
    void withJavaTimeEqualsRegisterModule() {
        assertSameConfig(new ObjectMapper().registerModule(new JavaTimeModule()), JsonMappers.withJavaTime());
    }

    @Test
    void lenientEqualsConfigureFailOnUnknownFalse() {
        assertSameConfig(
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            JsonMappers.lenient());
    }

    @Test
    void nonNullEqualsSetDefaultPropertyInclusion() {
        assertSameConfig(
            new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL), JsonMappers.nonNull());
    }

    @Test
    void sortedMapKeysEqualsEnableOrderMapEntries() {
        assertSameConfig(
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS), JsonMappers.sortedMapKeys());
    }

    @Test
    void strictTrailingTokensEqualsEnableFailOnTrailingTokens() {
        assertSameConfig(
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
            JsonMappers.strictTrailingTokens());
    }

    @Test
    void everyCallReturnsAFreshMapper() {
        assertNotSame(JsonMappers.plain(), JsonMappers.plain());
        assertNotSame(JsonMappers.lenient(), JsonMappers.lenient());
    }

    record Point(int x, String label) {
    }

    @Test
    void plainRoundTripsRecordsAndTrees() throws JsonProcessingException {
        ObjectMapper m = JsonMappers.plain();
        String json = m.writeValueAsString(new Point(3, "a"));
        assertEquals("{\"x\":3,\"label\":\"a\"}", json);
        assertEquals(new Point(3, "a"), m.readValue(json, Point.class));
        JsonNode tree = m.readTree("{\"a\":[1,2,{\"b\":null}]}");
        assertEquals("{\"a\":[1,2,{\"b\":null}]}", m.writeValueAsString(tree));
        assertThrows(UnrecognizedPropertyException.class, () -> m.readValue("{\"x\":1,\"zzz\":2}", Point.class));
    }

    @Test
    void lenientIgnoresUnknownProperties() throws JsonProcessingException {
        assertEquals(new Point(1, null), JsonMappers.lenient().readValue("{\"x\":1,\"zzz\":2}", Point.class));
    }

    @Test
    void nonNullOmitsNullProperties() throws JsonProcessingException {
        assertEquals("{\"x\":1}", JsonMappers.nonNull().writeValueAsString(new Point(1, null)));
        assertEquals("{\"x\":1,\"label\":null}", JsonMappers.plain().writeValueAsString(new Point(1, null)));
    }

    @Test
    void sortedMapKeysOrdersMapEntries() throws JsonProcessingException {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("b", 1);
        map.put("a", 2);
        assertEquals("{\"a\":2,\"b\":1}", JsonMappers.sortedMapKeys().writeValueAsString(map));
        assertEquals("{\"b\":1,\"a\":2}", JsonMappers.plain().writeValueAsString(map));
    }

    @Test
    void strictTrailingTokensRejectsTrailingContent() throws JsonProcessingException {
        assertThrows(JsonProcessingException.class, () -> JsonMappers.strictTrailingTokens().readTree("{} {}"));
        assertEquals("{}", JsonMappers.plain().readTree("{} {}").toString());
    }

    @Test
    void withJavaTimeWritesInstantsAndPlainCannot() throws JsonProcessingException {
        Instant t = Instant.parse("2026-01-02T03:04:05Z");
        // With the module and default WRITE_DATES_AS_TIMESTAMPS: numeric seconds.nanos.
        assertEquals("1767323045.000000000", JsonMappers.withJavaTime().writeValueAsString(t));
        assertThrows(JsonProcessingException.class, () -> JsonMappers.plain().writeValueAsString(t));
    }
}
