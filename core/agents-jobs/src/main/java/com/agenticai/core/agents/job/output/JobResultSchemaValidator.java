package com.agenticai.core.agents.job.output;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.json.JsonNodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Compiles and applies the JSON Schema a job's result must satisfy.
 *
 * <p>Two entry points, deliberately at two DIFFERENT points in time:
 * {@link #requireUsableSchema} runs at CONFIGURATION time (setting an agent's
 * result schema, or submitting a job that carries its own) and rejects a bad
 * schema with a 400 the moment someone writes one; {@link #violations} runs at
 * EXECUTION time against the model's actual answer. Splitting them is what keeps
 * "your schema is broken" from being reported ten minutes later as "your agent
 * failed", which is the same class of mistake as validating a cron expression
 * only when it first fires.
 *
 * <h2>The 2.x/3.x API is not the one every example on the internet shows</h2>
 * {@code com.networknt:json-schema-validator} 2.0.0 renamed its whole surface:
 * {@link SchemaRegistry} / {@link Schema} / {@link Error} / {@link
 * SpecificationVersion}, NOT the 1.x {@code JsonSchemaFactory} / {@code
 * JsonSchema} / {@code ValidationMessage} / {@code SpecVersion.VersionFlag}
 * names. Verified against the jar itself rather than from memory.
 *
 * <p>3.0.0 then swapped the engine's JSON tree to Jackson 3 ({@code
 * tools.jackson.databind.JsonNode}) while this codebase is still on Jackson 2
 * ({@code com.fasterxml}). Rather than bridging the two tree types, this class
 * only calls the {@link String}-based entry points - {@code
 * getSchema(String, InputFormat)} and {@code validate(String, InputFormat)} -
 * so no {@code tools.jackson} type ever crosses the boundary. If a future bump
 * makes this file stop compiling, those two facts are why.
 *
 * <h2>What is rejected, and why each one</h2>
 * <ul>
 *   <li><b>A remote {@code $ref}.</b> Server-side request forgery: the
 *       validator would fetch {@code http://169.254.169.254/...} from inside the
 *       cluster on a tenant's say-so, at validation time, with the platform's
 *       network position. There is no legitimate use for one here - a job's
 *       result schema is a self-contained shape description, not a document
 *       that composes other people's documents over the network.</li>
 *   <li><b>A non-object top level.</b> JSON Schema permits {@code true}/{@code
 *       false} as whole schemas; both are degenerate here ({@code true} accepts
 *       everything, which is indistinguishable from asking for no schema, and
 *       {@code false} rejects everything, which makes every job fail). Saying so
 *       is kinder than accepting it and letting the tenant discover it from a
 *       run of jobs that all fail identically.</li>
 *   <li><b>Oversize.</b> Bounded by {@code
 *       app.agent-jobs.output-schema.max-schema-bytes} (32KB). The schema is
 *       snapshotted onto every job row AND rendered into the prompt of every
 *       turn - an unbounded one is both a storage and a token-cost problem.</li>
 * </ul>
 */
@Component
public class JobResultSchemaValidator {

    /**
     * Bound on the compiled-schema cache. Compilation is not cheap and the same
     * handful of schemas are reused across every job of an agent, so caching is
     * worth it - but this is a per-replica in-memory map keyed on tenant-authored
     * content, so it must not be allowed to grow without limit.
     *
     * <p>A plain bounded {@link ConcurrentHashMap} rather than a real LRU cache:
     * this repo has ZERO Caffeine usage anywhere (checked across every module's
     * pom and source), and pulling in a cache library for a 500-entry map keyed
     * on a hash is not a trade worth making. The eviction policy is correspondingly
     * blunt - see {@link #cache}.
     */
    private static final int MAX_CACHED_SCHEMAS = 500;

    /**
     * Draft 2020-12, pinned rather than sniffed from the document's own {@code
     * $schema}. A tenant-supplied {@code $schema} would let the document choose
     * its own validation semantics, which is one more thing that can differ
     * between what a tenant tested and what runs. A document that DOES declare
     * {@code $schema} still works - this is the DEFAULT dialect, used when the
     * document does not say.
     */
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /**
     * SHA-256 of the schema text -&gt; its compiled form. Hash-keyed rather than
     * keyed on the raw string so a 32KB document is not held twice (once as key,
     * once inside the compiled schema) per entry.
     *
     * <p>Eviction is "clear the whole map when it exceeds {@link
     * #MAX_CACHED_SCHEMAS}". Crude on purpose: the cost of a miss is one
     * recompilation, the access pattern is a small stable working set per tenant,
     * and this is a cache whose only job is to avoid recompiling the SAME
     * document on every job. Anything smarter would be complexity bought with no
     * measurable return.
     */
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final int maxSchemaBytes;

    public JobResultSchemaValidator(ObjectMapper objectMapper, AgentJobProperties properties) {
        this.objectMapper = objectMapper;
        this.maxSchemaBytes = properties.getOutputSchema().getMaxSchemaBytes();
    }

    /**
     * Compiles the schema and throws if it is unusable. Call this at every point
     * a schema ENTERS the system - the agent-level PUT, and job submission with a
     * per-job schema - so that by the time a worker reads {@code
     * agent_job.result_schema_json}, the document there is already known to
     * compile.
     *
     * @throws InvalidResultSchemaException with a message naming the specific
     *     defect. "Invalid schema" alone sends a tenant admin hunting through a
     *     200-line document with nothing to look for.
     */
    public void requireUsableSchema(String schemaJson) {
        compile(schemaJson);
    }

    /**
     * Validates an instance and returns human-readable violations - empty means
     * it conformed.
     *
     * <p>Returns a list rather than throwing because both callers want the list:
     * {@link StructuredResultCoercer} feeds it back to the model as the repair
     * prompt, and the failure path puts it in {@code agent_job.error_message}.
     * A boolean would force both to re-derive what was actually wrong.
     */
    public List<String> violations(String schemaJson, JsonNode instance) {
        Schema schema = compile(schemaJson);
        // Instance arrives as a Jackson-2 JsonNode; the 3.x validator takes
        // Jackson-3 (tools.jackson) nodes or raw text - passing the serialized
        // form keeps this class free of the Jackson-3 type entirely.
        List<Error> errors = schema.validate(instance.toString(), InputFormat.JSON);
        List<String> readable = new ArrayList<>(errors.size());
        for (Error error : errors) {
            readable.add(format(error));
        }
        return readable;
    }

    // --------------------------------------------------------------- internals

    private Schema compile(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new InvalidResultSchemaException("A result schema was required but none was supplied.");
        }
        int bytes = schemaJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxSchemaBytes) {
            throw new InvalidResultSchemaException(
                "The result schema is " + bytes + " bytes, over the " + maxSchemaBytes + "-byte limit. "
                    + "Describe only the shape of the result, not every possible field."
            );
        }

        String key = sha256(schemaJson);
        Schema cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        JsonNode schemaNode;
        try {
            schemaNode = objectMapper.readTree(schemaJson);
        } catch (JsonProcessingException malformed) {
            throw new InvalidResultSchemaException(
                "The result schema is not valid JSON: " + malformed.getOriginalMessage(), malformed);
        }
        if (!schemaNode.isObject()) {
            throw new InvalidResultSchemaException(
                "The result schema must be a JSON object describing the required shape "
                    + "(for example {\"type\":\"object\",\"properties\":{...},\"required\":[...]})."
            );
        }
        rejectRemoteRefs(schemaNode);

        Schema compiled;
        try {
            compiled = registry.getSchema(schemaJson, InputFormat.JSON);
        } catch (RuntimeException uncompilable) {
            throw new InvalidResultSchemaException(
                "The result schema is not a valid JSON Schema: " + messageOf(uncompilable), uncompilable);
        }

        // Blunt bound, see the field's javadoc. Checked before the put so the map
        // can exceed the cap by at most the number of threads racing here.
        if (cache.size() >= MAX_CACHED_SCHEMAS) {
            cache.clear();
        }
        cache.put(key, compiled);
        return compiled;
    }

    /**
     * Walks the whole document rejecting any {@code $ref} that points off-host.
     *
     * <p>Done on the PARSED tree rather than by string-matching the raw text: a
     * substring search for {@code "http"} both over-rejects (a {@code
     * description} mentioning a URL is harmless) and under-rejects (JSON escapes
     * mean {@code "http://..."} is the same reference and does not contain
     * the literal). Protocol-relative {@code //host/...} is rejected too - it
     * resolves to a remote fetch just as surely as an explicit scheme does.
     *
     * <p>Local refs ({@code #/$defs/Item}) are untouched and fully supported;
     * they are the only kind a self-contained result schema needs.
     */
    private static void rejectRemoteRefs(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = JsonNodes.fields(node);
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("$ref".equals(field.getKey()) && field.getValue().isTextual()) {
                    String ref = field.getValue().asText().trim().toLowerCase(Locale.ROOT);
                    if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("//")) {
                        throw new InvalidResultSchemaException(
                            "The result schema references a remote document (" + field.getValue().asText()
                                + "). Result schemas must be self-contained; use a local $ref such as "
                                + "\"#/$defs/Item\" instead."
                        );
                    }
                }
                rejectRemoteRefs(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rejectRemoteRefs(child);
            }
        }
    }

    /**
     * "at /items/0/qty: integer found, string expected".
     *
     * <p>The library's own {@code getMessage()} already leads with the instance
     * location in most cases, so prefixing unconditionally would produce "at
     * /qty: /qty: ..." - the duplicate is stripped rather than accepted, because
     * this string goes into a repair prompt and a doubled path reads to the model
     * as two different locations.
     */
    private static String format(Error error) {
        String location = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
        String message = error.getMessage() == null ? "does not match the schema" : error.getMessage().trim();
        if (!location.isEmpty() && message.startsWith(location)) {
            message = message.substring(location.length()).replaceFirst("^\\s*:\\s*", "").trim();
        }
        // Root-level failures ("required property 'qty' not found") have a path
        // that renders as the bare root marker; "at the top level" reads better
        // to a human and to the model than "at $".
        String where = location.isEmpty() || "$".equals(location) || "#".equals(location)
            ? "at the top level"
            : "at " + location;
        return where + ": " + message;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandated by the JDK spec on every conforming platform.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
    }
}
