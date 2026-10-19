package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link KnowledgeMetadataFilter} turns caller-supplied key/value pairs into
 * SQL, so the tests that matter are the injection-safety ones: the SQL text
 * is a fixed shape regardless of input, every value travels as a bind
 * parameter, and keys outside {@code [a-zA-Z0-9_.-]{1,64}} never get through.
 */
class KnowledgeMetadataFilterTest {

    @Test
    void nullOrEmptyFilterProducesNoPredicate() {
        assertNull(KnowledgeMetadataFilter.toSql(null, "metadata"));
        assertNull(KnowledgeMetadataFilter.toSql(Map.of(), "metadata"));
    }

    @Test
    void sqlTextIsAFixedParameterisedContainmentPredicate() {
        KnowledgeMetadataFilter.SqlPredicate p = KnowledgeMetadataFilter.toSql(Map.of("source_type", "text"), "c.metadata");
        assertEquals(" AND c.metadata @> ?::jsonb", p.sql());
        assertEquals("{\"source_type\":\"text\"}", p.jsonParam());
    }

    @Test
    void hostileValuesStayInsideTheJsonBindParameter() {
        String hostile = "x'} OR 1=1; DROP TABLE knowledge_chunks; --\"";
        KnowledgeMetadataFilter.SqlPredicate p = KnowledgeMetadataFilter.toSql(Map.of("author", hostile), "metadata");
        assertEquals(" AND metadata @> ?::jsonb", p.sql());
        // Jackson escapes the quote; the value is data inside one JSON string.
        assertEquals("{\"author\":\"x'} OR 1=1; DROP TABLE knowledge_chunks; --\\\"\"}", p.jsonParam());
    }

    @Test
    void numbersAndBooleansKeepTheirJsonTypes() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("page", 3);
        filter.put("reviewed", true);
        filter.put("score", 0.5);
        assertEquals("{\"page\":3,\"reviewed\":true,\"score\":0.5}", KnowledgeMetadataFilter.toContainmentJson(filter));
    }

    @Test
    void dottedKeysAddressNestedObjects() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("author.team", "legal");
        filter.put("author.name", "Asha");
        filter.put("lang", "hi");
        assertEquals(
            "{\"author\":{\"team\":\"legal\",\"name\":\"Asha\"},\"lang\":\"hi\"}",
            KnowledgeMetadataFilter.toContainmentJson(filter)
        );
    }

    @Test
    void keysOutsideTheWhitelistAreRejected() {
        for (String bad : List.of("", "has space", "quote'", "semi;colon", "brace{", "a".repeat(65), "नाम", "a\"b")) {
            assertThrows(IllegalArgumentException.class,
                () -> KnowledgeMetadataFilter.toSql(Map.of(bad, "v"), "metadata"), "key should be rejected: " + bad);
        }
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put(null, "v");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toSql(nullKey, "metadata"));
    }

    @Test
    void keysAtTheWhitelistEdgesAreAccepted() {
        String key = "A-z_0.9" + "x".repeat(57); // exactly 64 chars
        assertEquals(64, key.length());
        assertTrue(KnowledgeMetadataFilter.toContainmentJson(Map.of(key, "v")).contains("\"A-z_0\""));
    }

    @Test
    void emptyPathSegmentsAndLeafParentConflictsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(Map.of("a..b", "v")));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(Map.of(".a", "v")));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(Map.of("a.", "v")));
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("a", "v");
        conflict.put("a.b", "w");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(conflict));
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("a.b", "w");
        reverse.put("a", "v");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(reverse));
    }

    @Test
    void nonScalarValuesAreRejected() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("k", null);
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(nullValue));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(Map.of("k", List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(Map.of("k", Map.of("x", 1))));
    }

    @Test
    void tooManyKeysAreRejected() {
        Map<String, Object> filter = new HashMap<>();
        for (int i = 0; i <= KnowledgeMetadataFilter.MAX_KEYS; i++) {
            filter.put("k" + i, i);
        }
        assertThrows(IllegalArgumentException.class, () -> KnowledgeMetadataFilter.toContainmentJson(filter));
    }
}
