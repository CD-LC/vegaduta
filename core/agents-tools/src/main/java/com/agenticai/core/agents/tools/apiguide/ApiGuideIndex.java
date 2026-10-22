package com.agenticai.core.agents.tools.apiguide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A searchable, read-only view of the platform's OpenAPI document. Everything it returns is copied
 * out of the spec (never invented); schemas are summarised to a few levels so a small model can
 * read them.
 */
final class ApiGuideIndex {

    private static final int MAX_RESULTS = 8;
    private static final int MAX_SCHEMA_DEPTH = 3;
    private static final List<String> VERBS = List.of("get", "post", "put", "patch", "delete");

    private record Op(
            String method,
            String path,
            String tag,
            String summary,
            String description,
            String operationId,
            JsonNode node) {
        String haystack() {
            return (method
                            + " "
                            + path
                            + " "
                            + tag
                            + " "
                            + summary
                            + " "
                            + description
                            + " "
                            + operationId)
                    .toLowerCase(Locale.ROOT);
        }
    }

    private final ObjectMapper mapper;
    private final JsonNode root;
    private final List<Op> ops = new ArrayList<>();

    ApiGuideIndex(ObjectMapper mapper, String specJson) throws IOException {
        this.mapper = mapper;
        this.root = mapper.readTree(specJson);
        Iterator<Map.Entry<String, JsonNode>> paths = root.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            for (String verb : VERBS) {
                JsonNode op = p.getValue().path(verb);
                if (op.isObject()) {
                    String tag =
                            op.path("tags").isArray() && !op.path("tags").isEmpty()
                                    ? op.path("tags").get(0).asString("")
                                    : "";
                    ops.add(
                            new Op(
                                    verb.toUpperCase(Locale.ROOT),
                                    p.getKey(),
                                    tag,
                                    op.path("summary").asString(""),
                                    shorten(op.path("description").asString(""), 200),
                                    op.path("operationId").asString(""),
                                    op));
                }
            }
        }
    }

    int size() {
        return ops.size();
    }

    ObjectNode search(String query, String method) {
        String[] terms =
                query == null
                        ? new String[0]
                        : query.toLowerCase(Locale.ROOT).trim().split("[^a-z0-9_/{}-]+");
        record Scored(Op op, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Op op : ops) {
            if (method != null && !method.isBlank() && !op.method().equalsIgnoreCase(method)) {
                continue;
            }
            String hay = op.haystack();
            int score = 0;
            for (String t : terms) {
                if (t.length() < 2) {
                    continue;
                }
                if (op.path().toLowerCase(Locale.ROOT).contains(t)) {
                    score += 3;
                }
                if (op.summary().toLowerCase(Locale.ROOT).contains(t)
                        || op.tag().toLowerCase(Locale.ROOT).contains(t)) {
                    score += 2;
                }
                if (hay.contains(t)) {
                    score += 1;
                }
            }
            if (score > 0 || terms.length == 0) {
                scored.add(new Scored(op, score));
            }
        }
        scored.sort(
                Comparator.comparingInt(Scored::score)
                        .reversed()
                        .thenComparing(s -> s.op().path()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode results = out.putArray("results");
        for (Scored s : scored.stream().limit(MAX_RESULTS).toList()) {
            ObjectNode r = results.addObject();
            r.put("method", s.op().method());
            r.put("path", s.op().path());
            r.put("summary", s.op().summary());
            r.put("tag", s.op().tag());
            if (!s.op().description().isBlank()) {
                r.put("note", s.op().description());
            }
        }
        out.put("matches", scored.size());
        out.put(
                "next",
                scored.isEmpty()
                        ? "Nothing matched. Try other words (a noun from the task, e.g. 'agents', 'jobs', 'knowledge')."
                        : "Call again with action=describe, method and path for the one you need.");
        return out;
    }

    Optional<ObjectNode> describe(String method, String path) {
        for (Op op : ops) {
            if (op.method().equalsIgnoreCase(method) && op.path().equals(path)) {
                return Optional.of(describe(op));
            }
        }
        return Optional.empty();
    }

    private ObjectNode describe(Op op) {
        ObjectNode out = mapper.createObjectNode();
        out.put("method", op.method());
        out.put("path", op.path());
        out.put("summary", op.summary());
        out.put("description", shorten(op.node().path("description").asString(""), 800));
        ArrayNode params = out.putArray("parameters");
        for (JsonNode raw : op.node().path("parameters")) {
            JsonNode p = resolve(raw);
            if ("header".equals(p.path("in").asString())) {
                continue;
            }
            ObjectNode o = params.addObject();
            o.put("name", p.path("name").asString());
            o.put("in", p.path("in").asString());
            o.put("required", p.path("required").asBoolean(false));
            o.put("type", resolve(p.path("schema")).path("type").asString("string"));
        }
        JsonNode body = op.node().path("requestBody").path("content");
        if (body.isObject() && body.fields().hasNext()) {
            Map.Entry<String, JsonNode> first = body.fields().next();
            ObjectNode b = out.putObject("body");
            b.put("contentType", first.getKey());
            b.set("schema", summarise(first.getValue().path("schema"), 0));
        }
        JsonNode ok = null;
        for (String code : List.of("200", "201", "202")) {
            if (op.node().path("responses").has(code)) {
                ok = op.node().path("responses").path(code);
                break;
            }
        }
        if (ok != null) {
            JsonNode content = ok.path("content");
            if (content.isObject() && content.fields().hasNext()) {
                out.set(
                        "response",
                        summarise(content.fields().next().getValue().path("schema"), 0));
            }
        }
        out.put(
                "showItLive",
                "Embed this in your reply as a ```json_ui block so the user sees the live result: "
                        + "{\"version\":\"v1\",\"root\":{\"type\":\"apiCall\",\"props\":{\"method\":\""
                        + op.method()
                        + "\",\"path\":\""
                        + op.path()
                        + "\",\"title\":\""
                        + op.summary().replace("\"", "'")
                        + "\"}}}. GET runs on its own; anything else waits for the user to press Send. Put {id}-style values "
                        + "into path, ?query into query (object) and the JSON body into body (object).");
        return out;
    }

    private JsonNode summarise(JsonNode schema, int depth) {
        JsonNode s = resolve(schema);
        ObjectNode out = mapper.createObjectNode();
        String type = s.path("type").asString(s.has("properties") ? "object" : "");
        if (!type.isBlank()) {
            out.put("type", type);
        }
        if (s.has("enum")) {
            out.set("enum", s.get("enum"));
        }
        if ("array".equals(type)) {
            out.set(
                    "items",
                    depth >= MAX_SCHEMA_DEPTH
                            ? mapper.createObjectNode()
                            : summarise(s.path("items"), depth + 1));
        }
        JsonNode props = s.path("properties");
        if (props.isObject() && depth < MAX_SCHEMA_DEPTH) {
            ObjectNode po = out.putObject("properties");
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            int n = 0;
            while (it.hasNext() && n++ < 40) {
                Map.Entry<String, JsonNode> e = it.next();
                po.set(e.getKey(), summarise(e.getValue(), depth + 1));
            }
            if (s.path("required").isArray()) {
                out.set("required", s.get("required"));
            }
        }
        return out;
    }

    private JsonNode resolve(JsonNode node) {
        JsonNode cur = node;
        for (int i = 0; i < 5 && cur.has("$ref"); i++) {
            String ref = cur.path("$ref").asString();
            if (!ref.startsWith("#/")) {
                break;
            }
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) {
                break;
            }
            cur = target;
        }
        return cur;
    }

    private static String shorten(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }
}
