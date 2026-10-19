package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.Schema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.ModelOptionsUtils;

/**
 * Dynamic companion to {@code SchemaTypeArrayNormalizer}'s (core/agents) hand-authored fixes for
 * specific JSON Schema shapes that crash Gemini's function-calling request builder. Rather than
 * hand-recognizing a shape, this actually attempts real deserialization the EXACT way {@code
 * GoogleGenAiChatModel#jsonToSchema} does, BEFORE a tool schema ever reaches a live request - and
 * if it throws, reads the EXACT failing field out of the real exception and removes only that
 * field, then retries.
 *
 * <p><b>Real, narrower scope - read this before assuming it catches everything</b>: probing
 * {@code ModelOptionsUtils.OBJECT_MAPPER.readValue(json, Schema.class)} directly (2026-08-10)
 * showed it does NOT throw for unsupported-for-Gemini JSON Schema keywords in general -
 * {@code additionalProperties}, {@code $schema}, {@code const}, {@code exclusiveMinimum}, even an
 * entirely made-up key all deserialize silently with no error, because Jackson's default
 * ignore-unknown-properties behavior just drops them - which also means {@code jsonToSchema}
 * itself already strips every one of those for free before a request is ever built, with no help
 * from this class. A non-null {@code "default"} value likewise deserializes cleanly (it maps to
 * {@code Schema}'s real {@code default_} field). The two things that DO throw: (1) a JSON "type"
 * array (see {@code SchemaTypeArrayNormalizer}'s own fix), and (2) a {@code null} value for a
 * field whose AutoValue-generated builder setter does {@code Optional.of(value)} with no
 * null-check - confirmed for {@code default}, {@code description}, and {@code items}. That NPE is
 * wrapped by Jackson into a message-less {@code JsonMappingException} whose {@code getMessage()}
 * returns the literal string {@code "N/A"} - this is what the original 2026-08-10 production
 * incident's {@code "default": null} logged. So this class' real value is as a safety net for
 * "type"-mismatch and null-valued-field shapes specifically, present or future - not a general
 * unsupported-keyword catcher (nothing needs to be one, per the above).
 *
 * <p><b>Validation mapper, read this before "simplifying" it</b>: {@code
 * GoogleGenAiChatModel#jsonToSchema} (private, confirmed via the installed
 * spring-ai-google-genai-1.1.7 jar's bytecode - the exact call site both {@code
 * GoogleGenAiChatOptions#getResponseSchema} and every {@code ToolDefinition#inputSchema} go
 * through on their way to a {@code FunctionDeclaration}) is {@code
 * ModelOptionsUtils.OBJECT_MAPPER.readValue(json, Schema.class)} - NOT {@link Schema#fromJson}.
 * Those are two DIFFERENTLY-CONFIGURED {@code ObjectMapper} instances ({@link Schema#fromJson}
 * delegates to {@code com.google.genai.JsonSerializable}'s own private static mapper) - an
 * earlier version of this class validated against {@code Schema.fromJson} and its own tests
 * caught that it silently failed to reproduce the live crash. {@link ModelOptionsUtils#OBJECT_MAPPER}
 * is {@code public static final}, so this replicates the real call exactly rather than
 * approximating it.
 *
 * <p>Deliberately layered ON TOP OF, not instead of, the hand-authored fixes: those run first and
 * handle the known shapes for free (no deserialization round-trip needed for the common case), and
 * this only ever does real work on a schema that already made it past both of them still broken.
 *
 * <p>Applied unconditionally (not gated to "this turn is using Gemini"), same reasoning
 * {@code SchemaTypeArrayNormalizer} already documents for its own fixes: the resulting schema is
 * still valid, equivalent JSON Schema for every other provider's function-calling too, so there
 * is no need to thread "which provider" down to this layer, and a field this strips was never
 * anything this platform's own tool-calling loop enforced in the first place (see this class'
 * removal-safety reasoning below).
 */
public final class GeminiSchemaSelfHealer {

    private static final Logger log = LoggerFactory.getLogger(GeminiSchemaSelfHealer.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    // Hard ceiling on strip-and-retry iterations - a real schema breaks on at most a handful of
    // fields even in a bad case; this exists purely so a genuinely pathological or adversarial
    // schema can never loop unbounded, not because a normal schema is expected to need many
    // passes. Bounded, not tuned - if this is ever actually hit in practice, that is itself a
    // signal worth investigating, logged as a WARN below.
    private static final int MAX_STRIP_ATTEMPTS = 10;

    private GeminiSchemaSelfHealer() {
    }

    /**
     * Returns a schema guaranteed to real-deserialize into Gemini's own {@link Schema} class, by
     * removing whatever field(s) the SDK itself rejects - or the original, unmodified schema if
     * it already validates or if healing was not possible (a malformed/unparsable schema, or one
     * that still fails after {@link #MAX_STRIP_ATTEMPTS}). Never throws; a caller that gets the
     * original schema back is in exactly the same position as before this class existed.
     */
    public static String healAgainstGeminiSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return schemaJson;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            return schemaJson; // Not this class' problem to diagnose an unparsable schema.
        }

        for (int attempt = 0; attempt < MAX_STRIP_ATTEMPTS; attempt++) {
            String candidate;
            try {
                candidate = OBJECT_MAPPER.writeValueAsString(root);
            } catch (Exception e) {
                return schemaJson; // Round-tripping our own parsed tree should never fail - fail safe.
            }
            JsonMappingException failure = tryDeserialize(candidate);
            if (failure == null) {
                return attempt == 0 ? schemaJson : candidate; // Valid as-is, or healed - return whichever we actually have.
            }
            String removedField = removeFailingField(root, failure);
            if (removedField == null) {
                // Couldn't map the exception back to a real field to remove - stop guessing
                // rather than risk stripping the wrong thing or looping forever.
                log.warn(
                    "Gemini schema validation failed but the failing field could not be located for "
                        + "removal - leaving the schema as-is: {}",
                    failure.getOriginalMessage()
                );
                return schemaJson;
            }
            log.warn(
                "Tool schema field \"{}\" is incompatible with Gemini's function-calling schema "
                    + "(would have crashed the whole turn) - removed automatically. Consider adding a "
                    + "permanent, documented fix to SchemaTypeArrayNormalizer if this recurs.",
                removedField
            );
        }
        log.warn("Gemini schema still failed validation after {} strip attempts - giving up and using the "
            + "original schema unmodified", MAX_STRIP_ATTEMPTS);
        return schemaJson;
    }

    /** Null means it deserialized successfully - nothing to heal. Mirrors {@code
     * GoogleGenAiChatModel#jsonToSchema} exactly (see this class' javadoc) so this actually
     * proves the real request-building call would succeed, not just that SOME mapper accepts it. */
    private static JsonMappingException tryDeserialize(String schemaJson) {
        try {
            SchemaJson.read(schemaJson, Schema.class);
            return null;
        } catch (JsonMappingException jme) {
            return jme;
        } catch (Exception e) {
            // Some other failure shape (unparsable JSON, a non-schema-content error) - not this
            // class' problem to diagnose or guess a field to remove for.
            return null;
        }
    }

    /**
     * Reads the exact failing field off the real exception (e.g. for {@code
     * properties -> max_tokens -> default}, {@link JsonMappingException#getPath()} returns
     * reference entries named exactly "properties", "max_tokens", "default" - confirmed against a
     * live incident's own exception message) and removes it from the live tree in place. Returns
     * the removed field's name for logging, or null if the path couldn't be resolved against the
     * actual tree (schema and exception disagree - fail safe rather than remove the wrong thing).
     */
    private static String removeFailingField(JsonNode root, JsonMappingException failure) {
        List<JsonMappingException.Reference> path = failure.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        JsonNode current = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String fieldName = path.get(i).getFieldName();
            if (fieldName == null || current == null || !current.isObject()) {
                return null;
            }
            current = current.get(fieldName);
        }
        String lastField = path.get(path.size() - 1).getFieldName();
        if (lastField == null || !(current instanceof ObjectNode objectNode) || !objectNode.has(lastField)) {
            return null;
        }
        objectNode.remove(lastField);
        return lastField;
    }
}
