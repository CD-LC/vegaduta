package com.agenticai.core.agents.tools.openapi;

import static com.agenticai.core.agents.tools.openapi.OpenApiSchemaResolver.resolveRef;
import static com.agenticai.core.agents.tools.openapi.OpenApiSchemaResolver.resolveSchema;
import static com.agenticai.core.agents.tools.openapi.OpenApiSchemaResolver.withDescription;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an OpenAPI 3.x (or Swagger 2.0) document into one tool definition per operation - the pure,
 * dependency-free heart of the import feature, shared by the console's preview and by the persisted
 * import/refresh so "what the preview showed" and "what the agent gets" are one code path.
 *
 * <p>Per operation:
 *
 * <ul>
 *   <li><b>name</b> - {@code <prefix>_<operationId>}, falling back to {@code
 *       <prefix>_<method>_<path>} when the spec omits operationId; always a schema-safe {@code
 *       ^[a-z][a-z0-9_]{2,63}$} identifier like every other tool name in this codebase,
 *       de-duplicated within the document.
 *   <li><b>description</b> - the operation's {@code summary}, then {@code description}, then {@code
 *       METHOD /path}.
 *   <li><b>input schema</b> - a JSON-Schema object whose properties are the path/query/header
 *       parameters (by name) plus the request body: an object body's properties are FLATTENED to
 *       the top level unless a property name collides with a parameter, otherwise (and for
 *       non-object bodies) the body is WRAPPED under a single {@code body} field - see {@link
 *       OpenApiBodyMode}. {@code $ref}s inside the document are inlined with a cycle guard and a
 *       depth cap; external references degrade to a plain {@code object}.
 * </ul>
 *
 * <p>YAML is read through SnakeYAML's {@link SafeConstructor} (already on the classpath via
 * spring-boot-starter) so a hostile document can never instantiate arbitrary classes - the same
 * reason the JSON side is plain Jackson tree parsing with no type binding.
 *
 * <p>Deliberately NOT a full OpenAPI validator (swagger-parser is not on the classpath and would be
 * a new dependency for a feature that only needs paths, parameters and schemas): unknown keywords
 * are passed through, unsupported constructs degrade to permissive schemas rather than failing the
 * whole import, and only outright non-documents are rejected.
 */
@Component
public class OpenApiSpecParser {

    static final int MAX_SPEC_CHARS = 1_000_000;
    static final int MAX_OPERATIONS = 500;
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_CHARS = 2_000;

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    // Header parameters the executor sets itself (auth, framing); exposing
    // them to the LLM would only let it fight the platform.
    private static final Set<String> RESERVED_HEADERS =
            Set.of(
                    "authorization",
                    "content-type",
                    "content-length",
                    "accept",
                    "host",
                    "connection",
                    "cookie",
                    "user-agent");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");

    /**
     * One path/query/header parameter of an operation. {@code schema} is already {@code $ref}-free.
     */
    public record ParsedParameter(String name, String in, boolean required, JsonNode schema) {}

    /**
     * One operation, ready to persist as an {@link OpenApiTool}. {@code operationKey} ({@code
     * "METHOD /path"}) is the refresh-stable identity; {@code suggestedToolName} is unique within
     * the document but is still checked against the tenant's other sources by {@link
     * OpenApiToolSourceService} before it is committed.
     */
    public record ParsedOperation(
            String operationKey,
            String operationId,
            String method,
            String path,
            String summary,
            String description,
            boolean deprecated,
            List<ParsedParameter> parameters,
            OpenApiBodyMode bodyMode,
            String bodyContentType,
            boolean bodyRequired,
            String parametersJson,
            String inputSchemaJson,
            String suggestedToolName) {}

    /**
     * The whole document. {@code serverUrl} may be relative or null - the service
     * resolves/validates it.
     */
    public record ParsedSpec(
            String title,
            String version,
            String description,
            String serverUrl,
            List<ParsedOperation> operations) {}

    /**
     * @param text the document, JSON or YAML
     * @param toolPrefix the (already validated) slug prefixed onto every tool name
     */
    public ParsedSpec parse(String text, String toolPrefix) {
        if (text == null || text.isBlank()) {
            throw new OpenApiToolException("The OpenAPI document is empty.");
        }
        if (text.length() > MAX_SPEC_CHARS) {
            throw new OpenApiToolException("The OpenAPI document is larger than 1 MB.");
        }
        JsonNode root = toTree(text);
        if (root == null || !root.isObject()) {
            throw new OpenApiToolException(
                    "The document is not an OpenAPI object (expected a JSON or YAML mapping at the top level).");
        }
        boolean swagger2 = root.hasNonNull("swagger");
        if (!swagger2 && !root.hasNonNull("openapi")) {
            throw new OpenApiToolException(
                    "Not an OpenAPI document: no \"openapi\" (3.x) or \"swagger\" (2.0) version field was found.");
        }
        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            throw new OpenApiToolException("The document declares no paths.");
        }
        JsonNode info = root.path("info");
        String serverUrl = swagger2 ? swagger2ServerUrl(root) : oas3ServerUrl(root);

        List<ParsedOperation> operations = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> pathIt = JsonNodes.fields(paths);
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> entry = pathIt.next();
            String path = entry.getKey();
            if (path.startsWith("x-")) {
                continue;
            }
            JsonNode pathItem = resolveRef(root, entry.getValue());
            if (!pathItem.isObject()) {
                continue;
            }
            List<JsonNode> pathLevelParams = listOf(pathItem.path("parameters"));
            for (String method : METHODS) {
                JsonNode op = pathItem.get(method);
                if (op == null || !op.isObject()) {
                    continue;
                }
                operations.add(
                        parseOperation(
                                root,
                                swagger2,
                                method.toUpperCase(Locale.ROOT),
                                path,
                                pathLevelParams,
                                op,
                                toolPrefix,
                                usedNames));
                if (operations.size() > MAX_OPERATIONS) {
                    throw new OpenApiToolException(
                            "The document declares more than "
                                    + MAX_OPERATIONS
                                    + " operations - split it before importing.");
                }
            }
        }
        if (operations.isEmpty()) {
            throw new OpenApiToolException(
                    "The document declares paths but no operations (get/post/...) under them.");
        }
        return new ParsedSpec(
                clip(text(info, "title"), 256),
                clip(text(info, "version"), 64),
                clip(text(info, "description"), MAX_DESCRIPTION_CHARS),
                serverUrl,
                operations);
    }

    // ------------------------------------------------------------------
    // Document loading
    // ------------------------------------------------------------------

    private static JsonNode toTree(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("\uFEFF")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.startsWith("{")) {
            try {
                return MAPPER.readTree(trimmed);
            } catch (JacksonException e) {
                throw new OpenApiToolException(
                        "The document is not valid JSON: " + e.getOriginalMessage());
            }
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(true);
            Object loaded = new Yaml(new SafeConstructor(options)).load(trimmed);
            if (loaded == null) {
                return null;
            }
            return MAPPER.valueToTree(loaded);
        } catch (OpenApiToolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OpenApiToolException(
                    "The document is not valid YAML or JSON: " + firstLine(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Servers
    // ------------------------------------------------------------------

    private static String oas3ServerUrl(JsonNode root) {
        JsonNode servers = root.path("servers");
        if (!servers.isArray() || servers.isEmpty()) {
            return null;
        }
        JsonNode first = servers.get(0);
        String url = text(first, "url");
        if (url == null) {
            return null;
        }
        // Server templates ("https://{region}.api.example.com/{version}") -
        // substitute each variable's default so the result is a usable origin.
        JsonNode variables = first.path("variables");
        if (variables.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = JsonNodes.fields(variables);
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> v = it.next();
                String def = text(v.getValue(), "default");
                if (def != null) {
                    url = url.replace("{" + v.getKey() + "}", def);
                }
            }
        }
        return url.strip();
    }

    private static String swagger2ServerUrl(JsonNode root) {
        String host = text(root, "host");
        String basePath = text(root, "basePath");
        if (host == null) {
            return basePath;
        }
        JsonNode schemes = root.path("schemes");
        String scheme = "https";
        if (schemes.isArray() && !schemes.isEmpty()) {
            scheme = schemes.get(0).asString("https");
        }
        return scheme + "://" + host + (basePath == null ? "" : basePath);
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    private ParsedOperation parseOperation(
            JsonNode root,
            boolean swagger2,
            String method,
            String path,
            List<JsonNode> pathLevelParams,
            JsonNode op,
            String toolPrefix,
            Set<String> usedNames) {
        // Operation-level parameters override path-level ones with the same (in, name).
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        for (JsonNode raw : pathLevelParams) {
            JsonNode p = resolveRef(root, raw);
            merged.put(text(p, "in") + ":" + text(p, "name"), p);
        }
        for (JsonNode raw : listOf(op.path("parameters"))) {
            JsonNode p = resolveRef(root, raw);
            merged.put(text(p, "in") + ":" + text(p, "name"), p);
        }

        List<ParsedParameter> parameters = new ArrayList<>();
        JsonNode bodySchema = null;
        String bodyContentType = null;
        boolean bodyRequired = false;
        ObjectNode formProperties = null;
        ArrayNode formRequired = null;

        for (JsonNode p : merged.values()) {
            String in = text(p, "in");
            String name = text(p, "name");
            if (in == null || name == null || name.isBlank()) {
                continue;
            }
            in = in.toLowerCase(Locale.ROOT);
            if (swagger2 && in.equals("body")) {
                bodySchema = resolveSchema(root, p.path("schema"), 0, new LinkedHashSet<>());
                bodyContentType = firstConsumes(root, op);
                bodyRequired = p.path("required").asBoolean(false);
                continue;
            }
            if (swagger2 && in.equals("formdata")) {
                if (formProperties == null) {
                    formProperties = MAPPER.createObjectNode();
                    formRequired = MAPPER.createArrayNode();
                }
                formProperties.set(
                        name, withDescription(swagger2ParameterSchema(p), text(p, "description")));
                if (p.path("required").asBoolean(false)) {
                    formRequired.add(name);
                }
                continue;
            }
            if (in.equals("cookie")) {
                continue;
            }
            if (in.equals("header") && RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!in.equals("path") && !in.equals("query") && !in.equals("header")) {
                continue;
            }
            JsonNode schema =
                    swagger2
                            ? swagger2ParameterSchema(p)
                            : resolveSchema(root, p.path("schema"), 0, new LinkedHashSet<>());
            if (schema == null) {
                schema = MAPPER.createObjectNode().put("type", "string");
            }
            boolean required = in.equals("path") || p.path("required").asBoolean(false);
            parameters.add(
                    new ParsedParameter(
                            name, in, required, withDescription(schema, text(p, "description"))));
        }

        if (formProperties != null) {
            ObjectNode form = MAPPER.createObjectNode();
            form.put("type", "object");
            form.set("properties", formProperties);
            if (formRequired != null && !formRequired.isEmpty()) {
                form.set("required", formRequired);
            }
            bodySchema = form;
            bodyContentType = "application/x-www-form-urlencoded";
            bodyRequired = formRequired != null && !formRequired.isEmpty();
        }

        if (!swagger2) {
            JsonNode requestBody = resolveRef(root, op.path("requestBody"));
            if (requestBody.isObject()) {
                bodyRequired = requestBody.path("required").asBoolean(false);
                JsonNode content = requestBody.path("content");
                String mediaType = pickMediaType(content);
                if (mediaType != null) {
                    bodyContentType = mediaType;
                    bodySchema =
                            resolveSchema(
                                    root,
                                    content.path(mediaType).path("schema"),
                                    0,
                                    new LinkedHashSet<>());
                    if (bodySchema == null) {
                        bodySchema = MAPPER.createObjectNode().put("type", "object");
                    }
                }
            }
        }

        // ---- input schema ----
        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();
        Set<String> parameterNames = new HashSet<>();
        for (ParsedParameter parameter : parameters) {
            parameterNames.add(parameter.name());
            properties.set(parameter.name(), parameter.schema());
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        OpenApiBodyMode bodyMode = OpenApiBodyMode.NONE;
        if (bodySchema != null) {
            if (isFlattenable(bodySchema, parameterNames)) {
                bodyMode = OpenApiBodyMode.FLATTENED;
                Iterator<Map.Entry<String, JsonNode>> it =
                        JsonNodes.fields(bodySchema.path("properties"));
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> prop = it.next();
                    properties.set(prop.getKey(), prop.getValue());
                }
                for (JsonNode r : bodySchema.path("required")) {
                    if (r.isString() && bodySchema.path("properties").has(r.asString())) {
                        required.add(r.asString());
                    }
                }
            } else {
                bodyMode = OpenApiBodyMode.WRAPPED;
                properties.set("body", withDescription(bodySchema, "The request body."));
                if (bodyRequired) {
                    required.add("body");
                }
            }
        }
        ObjectNode inputSchema = MAPPER.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.set("required", required);
        }

        // ---- parameters json (what the executor replays) ----
        ArrayNode parametersJson = MAPPER.createArrayNode();
        for (ParsedParameter parameter : parameters) {
            ObjectNode node = parametersJson.addObject();
            node.put("name", parameter.name());
            node.put("in", parameter.in());
            node.put("required", parameter.required());
            node.set("schema", parameter.schema());
        }

        String operationId = text(op, "operationId");
        String summary = clip(text(op, "summary"), 512);
        String description = clip(text(op, "description"), MAX_DESCRIPTION_CHARS);
        String toolName = uniqueToolName(toolPrefix, operationId, method, path, usedNames);

        return new ParsedOperation(
                method + " " + path,
                clip(operationId, 256),
                method,
                path,
                summary,
                description,
                op.path("deprecated").asBoolean(false),
                List.copyOf(parameters),
                bodyMode,
                bodyContentType,
                bodyRequired,
                parametersJson.toString(),
                inputSchema.toString(),
                toolName);
    }

    /**
     * An object body with named properties can be spread over the top level - unless a name is
     * already a parameter's.
     */
    private static boolean isFlattenable(JsonNode bodySchema, Set<String> parameterNames) {
        if (!bodySchema.isObject()) {
            return false;
        }
        String type = text(bodySchema, "type");
        if (type != null && !type.equals("object")) {
            return false;
        }
        JsonNode props = bodySchema.path("properties");
        if (!props.isObject() || props.isEmpty()) {
            return false;
        }
        Iterator<String> names = props.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (parameterNames.contains(name) || name.equals("body")) {
                return false;
            }
        }
        return true;
    }

    private static String pickMediaType(JsonNode content) {
        if (!content.isObject() || content.isEmpty()) {
            return null;
        }
        if (content.has("application/json")) {
            return "application/json";
        }
        Iterator<String> names = content.propertyNames().iterator();
        String first = null;
        while (names.hasNext()) {
            String name = names.next();
            if (first == null) {
                first = name;
            }
            if (name.toLowerCase(Locale.ROOT).contains("json")) {
                return name;
            }
        }
        if (content.has("application/x-www-form-urlencoded")) {
            return "application/x-www-form-urlencoded";
        }
        return first;
    }

    private static String firstConsumes(JsonNode root, JsonNode op) {
        for (JsonNode source : new JsonNode[] {op.path("consumes"), root.path("consumes")}) {
            if (source.isArray() && !source.isEmpty()) {
                for (JsonNode c : source) {
                    if (c.asString("").toLowerCase(Locale.ROOT).contains("json")) {
                        return c.asString();
                    }
                }
                return source.get(0).asString();
            }
        }
        return "application/json";
    }

    /** Swagger 2 parameters carry their schema keywords inline rather than under {@code schema}. */
    private static ObjectNode swagger2ParameterSchema(JsonNode p) {
        ObjectNode schema = MAPPER.createObjectNode();
        for (String key :
                List.of(
                        "type",
                        "format",
                        "enum",
                        "items",
                        "default",
                        "minimum",
                        "maximum",
                        "minLength",
                        "maxLength",
                        "pattern",
                        "minItems",
                        "maxItems",
                        "uniqueItems")) {
            JsonNode v = p.get(key);
            if (v != null && !v.isNull()) {
                schema.set(key, v.deepCopy());
            }
        }
        if (!schema.has("type")) {
            schema.put("type", "string");
        }
        if (schema.path("type").asString("").equals("file")) {
            schema.put("type", "string");
        }
        return schema;
    }

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    /**
     * {@code <prefix>_<slug(operationId | method_path)>}, clipped to 64 chars and made unique
     * within the document with a numeric suffix. Package-private test seam.
     */
    static String uniqueToolName(
            String toolPrefix,
            String operationId,
            String method,
            String path,
            Set<String> usedNames) {
        String core =
                operationId != null && !operationId.isBlank()
                        ? slugify(operationId)
                        : slugify(method.toLowerCase(Locale.ROOT) + "_" + path);
        if (core.isBlank()) {
            core = "op";
        }
        String base = clipName(toolPrefix + "_" + core, MAX_TOOL_NAME_LENGTH);
        String candidate = base;
        for (int n = 2; usedNames.contains(candidate); n++) {
            String suffix = "_" + n;
            candidate = clipName(base, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
        }
        usedNames.add(candidate);
        return candidate;
    }

    static String slugify(String raw) {
        if (raw == null) {
            return "";
        }
        // camelCase -> camel_case before lowercasing, so "listUsers" reads as list_users.
        String spaced = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        String slug = NON_SLUG.matcher(spaced.toLowerCase(Locale.ROOT)).replaceAll("_");
        return EDGE_UNDERSCORES.matcher(slug).replaceAll("");
    }

    private static String clipName(String name, int max) {
        if (name.length() <= max) {
            return name;
        }
        String clipped = name.substring(0, max);
        return EDGE_UNDERSCORES.matcher(clipped).replaceAll("");
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static List<JsonNode> listOf(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.isString() ? v.asString() : v.toString();
        return s.isBlank() ? null : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable document";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
