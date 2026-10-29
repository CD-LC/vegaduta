package com.agenticai.core.chat.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A per-call request for provider-enforced structured output, carried to the factories through the
 * {@code providerOptions} map under {@link StructuredSchemaSpec#OPTION_KEY}. {@code from} returns the
 * spec only when it is present, of the right type, and carries a non-blank schema.
 */
class StructuredSchemaSpecTest {

    @Test
    void fromReturnsTheSpecWhenTheKeyCarriesAUsableOne() {
        StructuredSchemaSpec spec = new StructuredSchemaSpec("answer", "{\"type\":\"object\"}");

        assertSame(spec, StructuredSchemaSpec.from(Map.of(StructuredSchemaSpec.OPTION_KEY, spec)));
    }

    @Test
    void fromReturnsNullForAbsentNullOrForeignValues() {
        assertNull(StructuredSchemaSpec.from(null));
        assertNull(StructuredSchemaSpec.from(Map.of()));
        assertNull(StructuredSchemaSpec.from(Map.of(StructuredSchemaSpec.OPTION_KEY, "not a spec")));
        assertNull(StructuredSchemaSpec.from(Map.of("other", new StructuredSchemaSpec("n", "{}"))));
    }

    @Test
    void fromRejectsASpecWhoseSchemaIsMissingOrBlank() {
        assertNull(StructuredSchemaSpec.from(
            Map.of(StructuredSchemaSpec.OPTION_KEY, new StructuredSchemaSpec("n", null))));
        assertNull(StructuredSchemaSpec.from(
            Map.of(StructuredSchemaSpec.OPTION_KEY, new StructuredSchemaSpec("n", "   "))));
    }

    @Test
    void optionsBuildsTheOneEntryMapFromReadsBack() {
        Map<String, Object> options = StructuredSchemaSpec.options("answer", "{\"type\":\"object\"}");

        StructuredSchemaSpec spec = StructuredSchemaSpec.from(options);
        assertEquals("answer", spec.name());
        assertEquals("{\"type\":\"object\"}", spec.jsonSchema());
    }

    @Test
    void theOptionKeyIsTheDocumentedOne() {
        assertEquals("structuredSchema", StructuredSchemaSpec.OPTION_KEY);
    }
}
