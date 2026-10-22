package com.agenticai.core.agents.tools;

import com.agenticai.core.chat.GeminiSchemaSelfHealer;
import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fixes one specific JSON Schema shape a resolved tool's {@code inputSchema()} can carry that
 * crashes Google's Gemini function-calling request builder: a nullable/optional parameter expressed
 * as a "type" ARRAY (e.g. {@code "type": ["integer", "null"]} - the JSON Schema 2020-12 idiom
 * several tool sources here emit for an optional field, including this platform's own
 * {@code @Tool}-annotated methods with a boxed/optional parameter and MCP servers generated from
 * Python/Pydantic models with {@code Optional[...]} fields). {@code
 * org.springframework.ai.google.genai.GoogleGenAiChatModel#jsonToSchema} only accepts a single
 * string "type" and throws {@code MismatchedInputException} while building the request - which
 * fails the WHOLE turn, not just a call to that one tool, because Gemini needs every offered tool's
 * schema converted up front for function-calling, independent of whether the model ends up calling
 * it.
 *
 * <p>Live incident, 2026-08-05: staging ({@code agentic-core}) logs showed this firing repeatedly
 * (`limit`, `fiscal_year` parameters both observed, {@code "Aggregation Error"} / {@code
 * MessageAggregator}), surfacing to affected tenants as the generic "service temporarily
 * unavailable" toast or an empty assistant reply. Reproducible for ANY tool with an optional
 * integer/number/boolean parameter once a Gemini-family model is in use - hosted or the
 * hosted-fallback edge chat takes for a tool-needing turn (Edge Agents' on-device tiers never call
 * tools directly, so this only ever surfaced through the ordinary hosted path, not edge-specific
 * code).
 *
 * <p>Recursively walks the schema (properties/items/anywhere a "type" keyword can appear) and
 * collapses a "type" array to a single value: {@code "null"} is dropped (nullability is already
 * expressed correctly via the schema's own "required" list, which this never touches), and if more
 * than one non-null type remains the first is kept - a tool parameter union genuinely needing more
 * than "nullable" is rare enough that a slightly narrowed type beats crashing the turn outright.
 * Applied uniformly to every resolved tool, not gated to Gemini calls: the normalized shape is
 * valid, equivalent JSON Schema for every other provider's function-calling too, so there is no
 * need to thread "which provider is this turn using" down to this layer.
 *
 * <p><b>Extended 2026-08-10</b> for a second, same-class crash: a tool parameter with a {@code
 * "default"} keyword (e.g. {@code {"type": "integer", "default": 100}} for a {@code
 * max_tokens}-shaped parameter) crashes the same Gemini schema-conversion step with a {@code
 * DatabindException} on {@code AutoValue_Schema$Builder}'s "default" field - Gemini's function-
 * declaration schema (a restricted subset of OpenAPI schema) has no {@code Schema.Builder} setter
 * for it at all. Same fix shape as the type-array case: strip the field rather than fail the whole
 * turn. A dropped default costs nothing functionally - it was never a value this platform's own
 * tool-calling loop enforced, only a hint for whichever client rendered the raw schema (nothing
 * here does), so the model still sees an accurate required/optional shape via the schema's own
 * "required" list, just without a suggested value for the optional case.
 *
 * <p><b>Extended 2026-08-12</b> for a THIRD, structurally different crash: unlike the two fixes
 * above, this one is never a local Jackson exception at all - the schema deserializes into {@code
 * Schema} on this side just fine, then Vertex's own server-side validator rejects the live HTTP
 * request with a 400 ("schema type should be ARRAY") because a node carries an {@code "items"}
 * keyword without its own {@code "type"} also literally being {@code "array"} - observed live from
 * a third-party MCP server (Firecrawl's {@code get_contents}, an {@code ids} parameter with {@code
 * items} but no {@code type} at all). Neither {@link #collapseTypeArrays} nor {@link
 * GeminiSchemaSelfHealer} can catch this class of defect: the first only rewrites an
 * ALREADY-array-valued "type" field, the second only reacts to a LOCAL deserialization exception -
 * and a missing/wrong "type" next to "items" throws neither. Fixed proactively rather than
 * reactively this time - {@link #fixArrayTypeMismatch} enforces the general structural rule ("any
 * node with items must have type array") for every tool schema, not just the one observed parameter
 * name, so the next third-party MCP server that emits this same shape needs no new code here.
 * {@code com.google.genai.types.Type}'s own constructor matches case-insensitively (decompiled from
 * the installed google-genai-1.37.0 jar to confirm, rather than guessing), so the literal case
 * written here is cosmetic only - lowercase to match every other type value this class and the rest
 * of this codebase's tool schemas already use.
 */
final class SchemaTypeArrayNormalizer {

    private static final ObjectMapper OBJECT_MAPPER = JsonMappers.plain();

    private SchemaTypeArrayNormalizer() {}

    /**
     * Wraps only the tools that actually need it - the common case (no array-typed "type" field)
     * returns the original callback untouched.
     */
    static List<ToolCallback> normalizeAll(List<ToolCallback> tools) {
        List<ToolCallback> result = new ArrayList<>(tools.size());
        for (ToolCallback tool : tools) {
            result.add(normalize(tool));
        }
        return result;
    }

    private static ToolCallback normalize(ToolCallback tool) {
        String fixed = tryNormalize(tool.getToolDefinition().inputSchema());
        return fixed == null ? tool : new Wrapped(tool, fixed);
    }

    /**
     * Null when the schema is unparsable or already free of every known-incompatible shape - no
     * wrapping needed either way.
     */
    private static String tryNormalize(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            return null;
        }
        boolean[] changed = {false};
        collapseTypeArrays(root, changed);
        stripDefaultFields(root, changed);
        fixArrayTypeMismatch(root, changed);
        String afterKnownFixes;
        try {
            afterKnownFixes = OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Round-tripping a JsonNode this class itself just parsed should never fail - fail
            // safe (keep the original schema) rather than risk propagating a malformed one.
            return null;
        }
        // Dynamic self-healing pass (see GeminiSchemaSelfHealer's own javadoc): runs AFTER the
        // two hand-authored fixes above, on whatever they produced, so it only ever does real
        // work (an actual deserialization round-trip) on a schema still broken after the free
        // shape-specific fixes - the common case pays nothing extra. Catches a future "type"
        // array or null-valued-field shape neither fix above knows about yet, automatically (see
        // that class' javadoc for what it can and can't catch).
        String healed = GeminiSchemaSelfHealer.healAgainstGeminiSchema(afterKnownFixes);
        if (!healed.equals(afterKnownFixes)) {
            return healed;
        }
        // Healer made no further change - compare against afterKnownFixes (not the original
        // schemaJson text) so re-serialization formatting alone (whitespace, key order) never
        // masquerades as a real change.
        return changed[0] ? afterKnownFixes : null;
    }

    private static void collapseTypeArrays(JsonNode node, boolean[] changed) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode typeNode = obj.get("type");
            if (typeNode != null && typeNode.isArray()) {
                String single = firstNonNullType((ArrayNode) typeNode);
                if (single != null) {
                    obj.put("type", single);
                } else {
                    obj.remove("type");
                }
                changed[0] = true;
            }
            Iterator<JsonNode> children = obj.values().iterator();
            while (children.hasNext()) {
                collapseTypeArrays(children.next(), changed);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collapseTypeArrays(child, changed);
            }
        }
    }

    /**
     * See this class' javadoc's "Extended 2026-08-10" section. Mirrors {@link
     * #collapseTypeArrays}'s recursive shape (properties/items/anywhere a "default" keyword can
     * appear), just removing the field outright instead of rewriting it.
     */
    private static void stripDefaultFields(JsonNode node, boolean[] changed) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("default")) {
                obj.remove("default");
                changed[0] = true;
            }
            Iterator<JsonNode> children = obj.values().iterator();
            while (children.hasNext()) {
                stripDefaultFields(children.next(), changed);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripDefaultFields(child, changed);
            }
        }
    }

    /**
     * See this class' javadoc's "Extended 2026-08-12" section. Mirrors {@link
     * #collapseTypeArrays}'s recursive shape (properties/items/anywhere "items" can appear), but
     * runs AFTER it so a "type" that was itself an array (e.g. {@code ["array", "null"]}) has
     * already been collapsed to a single string by the time this checks it.
     */
    private static void fixArrayTypeMismatch(JsonNode node, boolean[] changed) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("items")) {
                JsonNode typeNode = obj.get("type");
                boolean alreadyArray =
                        typeNode != null
                                && typeNode.isString()
                                && "array".equalsIgnoreCase(typeNode.textValue());
                if (!alreadyArray) {
                    obj.put("type", "array");
                    changed[0] = true;
                }
            }
            Iterator<JsonNode> children = obj.values().iterator();
            while (children.hasNext()) {
                fixArrayTypeMismatch(children.next(), changed);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                fixArrayTypeMismatch(child, changed);
            }
        }
    }

    private static String firstNonNullType(ArrayNode typeArray) {
        for (JsonNode t : typeArray) {
            if (t.isString() && !"null".equalsIgnoreCase(t.textValue())) {
                return t.textValue();
            }
        }
        return null;
    }

    private static final class Wrapped implements ToolCallback {
        private final ToolCallback delegate;
        private final ToolDefinition definition;

        Wrapped(ToolCallback delegate, String normalizedSchema) {
            this.delegate = delegate;
            ToolDefinition original = delegate.getToolDefinition();
            this.definition =
                    new DefaultToolDefinition(
                            original.name(), original.description(), normalizedSchema);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
