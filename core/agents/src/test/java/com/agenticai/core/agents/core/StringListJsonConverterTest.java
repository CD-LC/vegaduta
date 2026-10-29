package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link StringListJsonConverter} stores a {@code List<String>} as a JSON array in a TEXT column.
 * These pin the round trip and the read-side posture: blank or unparseable column content reads as
 * an empty list rather than an exception, since a conversion failure would take down the whole
 * entity load.
 */
class StringListJsonConverterTest {

    private final StringListJsonConverter converter = new StringListJsonConverter();

    @Test
    void aListSerialisesToAJsonArrayAndBack() {
        String column = converter.convertToDatabaseColumn(List.of("END", "DONE", "###"));

        assertEquals("[\"END\",\"DONE\",\"###\"]", column);
        assertEquals(List.of("END", "DONE", "###"), converter.convertToEntityAttribute(column));
    }

    @Test
    void aNullAttributeWritesAnEmptyArray() {
        assertEquals("[]", converter.convertToDatabaseColumn(null));
    }

    @Test
    void unreadableColumnContentReadsAsEmpty() {
        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
        assertTrue(converter.convertToEntityAttribute("").isEmpty());
        assertTrue(converter.convertToEntityAttribute("   ").isEmpty());
        assertTrue(converter.convertToEntityAttribute("not json").isEmpty());
        assertTrue(
                converter.convertToEntityAttribute("[{\"a\":1}]").isEmpty(),
                "a JSON array of objects is unreadable as List<String>");
        assertTrue(converter.convertToEntityAttribute("{\"a\":1}").isEmpty());
    }

    @Test
    void theReadListIsMutableLikeHibernateExpects() {
        List<String> read = converter.convertToEntityAttribute("[\"a\"]");
        read.add("b");
        assertEquals(List.of("a", "b"), read);
    }
}
