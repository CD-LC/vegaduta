package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Schema;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link GeminiSchemaSelfHealer} against real deserialization behavior. These tests pin
 * the actual live incident (2026-08-10): a {@code max_tokens}-shaped parameter with {@code
 * "default": null} - confirmed by direct probing (see this class' history) that a non-null {@code
 * "default"} value deserializes just fine (it maps cleanly to {@code Schema}'s {@code default_}
 * field), and that unknown/unsupported JSON Schema keywords ({@code additionalProperties},
 * {@code $schema}, {@code const}, {@code exclusiveMinimum}, ...) are silently dropped by Jackson's
 * normal ignore-unknown-properties behavior rather than throwing - so this class cannot detect or
 * strip those (nothing client-side needs to, since the same silent-drop already happens for free
 * inside {@code GoogleGenAiChatModel#jsonToSchema} before the request is ever built). What DOES
 * throw is a {@code null} value for an {@code Optional}-wrapped field whose AutoValue-generated
 * builder setter calls {@code Optional.of(value)} without a null-check ({@code default},
 * {@code description}, {@code items} all confirmed) - Jackson wraps the resulting
 * {@code NullPointerException} as a message-less {@code JsonMappingException} (hence
 * {@code getMessage()} returning the literal string {@code "N/A"}, which matches the original
 * incident's logged exception exactly). This class' real scope is therefore narrower than "any
 * Gemini-incompatible schema" - it catches type-array mismatches (see
 * {@code SchemaTypeArrayNormalizer}) and null-valued known fields, not unsupported-keyword shapes.
 */
class GeminiSchemaSelfHealerTest {

    @Test
    void validSchemaIsReturnedUnmodified() {
        String schema = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}";
        assertEquals(schema, GeminiSchemaSelfHealer.healAgainstGeminiSchema(schema));
        // Sanity: confirm this really does deserialize with the real SDK class, not just pass
        // this class' own opinion of "valid".
        Schema.fromJson(schema);
    }

    @Test
    void stripsANullDefaultFieldAndTheResultRealDeserializes() {
        String broken = "{\"type\":\"object\",\"properties\":{\"max_tokens\":{\"type\":\"integer\",\"default\":null}}}";
        String healed = GeminiSchemaSelfHealer.healAgainstGeminiSchema(broken);

        assertFalse(healed.equals(broken), "expected the schema to be modified");
        assertFalse(healed.contains("default"), healed);
        // The real point of this class: the healed output must actually satisfy the real SDK,
        // not just look plausible - this throws if it doesn't.
        Schema.fromJson(healed);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals(null, GeminiSchemaSelfHealer.healAgainstGeminiSchema(null));
        assertEquals("", GeminiSchemaSelfHealer.healAgainstGeminiSchema(""));
    }

    @Test
    void malformedJsonIsLeftAloneRatherThanThrowing() {
        String malformed = "not valid json";
        assertEquals(malformed, GeminiSchemaSelfHealer.healAgainstGeminiSchema(malformed));
    }

    @Test
    void aSchemaWithNestedPropertiesStillHealsCorrectly() {
        String broken = "{\"type\":\"object\",\"properties\":{"
            + "\"outer\":{\"type\":\"object\",\"properties\":{"
            + "\"inner\":{\"type\":\"integer\",\"default\":null}}}}}";
        String healed = GeminiSchemaSelfHealer.healAgainstGeminiSchema(broken);

        assertFalse(healed.contains("default"), healed);
        assertTrue(healed.contains("\"inner\""), "unrelated sibling structure should survive: " + healed);
        Schema.fromJson(healed);
    }
}
