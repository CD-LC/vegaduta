package com.agenticai.core.workflow.portability;

import static com.agenticai.core.workflow.portability.N8nImportModel.firstNonBlank;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.N8nImportModel.NodeCtx;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps an n8n HTTP Request node's parameters onto the HTTP node's config. Part of {@link
 * N8nImportMapper}.
 */
final class N8nHttpMapping {

    private final ObjectMapper mapper;

    N8nHttpMapping(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The HTTP node config, or {@code null} when the source node has no URL to carry over. */
    Map<String, Object> config(NodeCtx c) {
        String url = c.tx(c.text("url"));
        if (url == null || url.isBlank()) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        String method = firstNonBlank(c.text("method"), c.text("requestMethod"));
        config.put("method", method == null ? "GET" : method.strip().toUpperCase(Locale.ROOT));
        config.put("url", url);

        List<Map<String, Object>> headers =
                pairs(
                        c,
                        firstNonNull(
                                c.params.path("headerParameters").get("parameters"),
                                c.params.path("headerParametersUi").get("parameter")));
        if (!headers.isEmpty()) {
            config.put("headers", headers);
        }
        List<Map<String, Object>> query =
                pairs(
                        c,
                        firstNonNull(
                                c.params.path("queryParameters").get("parameters"),
                                c.params.path("queryParametersUi").get("parameter")));
        if (!query.isEmpty()) {
            config.put("query", query);
        }
        if (c.params.hasNonNull("jsonHeaders") || c.params.hasNonNull("jsonQuery")) {
            c.lose(
                    "Headers/query written as a JSON blob were not carried over; add them as name/value pairs.");
        }

        httpBody(c, config);

        JsonNode options = c.params.path("options");
        Double timeoutMs = AgentImportJson.number(options, "timeout");
        if (timeoutMs != null) {
            config.put(
                    "timeoutSeconds",
                    (int) Math.max(1, Math.min(30, Math.ceil(timeoutMs / 1000.0))));
        }
        if (options.path("response").path("response").path("neverError").asBoolean(false)) {
            config.put("failOnHttpError", Boolean.FALSE);
        }
        List<String> dropped = new ArrayList<>();
        Iterator<String> names = options.fieldNames();
        while (names.hasNext()) {
            String option = names.next();
            if (!"timeout".equals(option) && !"response".equals(option)) {
                dropped.add(option);
            }
        }
        if (!dropped.isEmpty()) {
            c.lose("HTTP options not carried over: " + String.join(", ", dropped) + ".");
        }
        String auth = c.text("authentication");
        if (auth != null && !"none".equalsIgnoreCase(auth)) {
            c.lose(
                    "Authentication ("
                            + AgentImportText.inline(auth, 40)
                            + ") is not imported: the request is sent "
                            + "without it until you add headers or a {{secret.NAME}} reference.");
        }
        return config;
    }

    private void httpBody(NodeCtx c, Map<String, Object> config) {
        boolean sendBody =
                c.params.path("sendBody").asBoolean(false)
                        || c.params.path("jsonParameters").asBoolean(false)
                        || c.params.has("bodyParametersJson");
        if (!sendBody) {
            config.put("bodyType", "none");
            return;
        }
        String contentType = c.text("contentType");
        String specify = c.text("specifyBody");
        String json = firstNonBlank(c.text("jsonBody"), c.text("bodyParametersJson"));
        List<Map<String, Object>> keypairs =
                pairs(
                        c,
                        firstNonNull(
                                c.params.path("bodyParameters").get("parameters"),
                                c.params.path("bodyParametersUi").get("parameter")));
        String ct = contentType == null ? "json" : contentType;
        switch (ct) {
            case "json" -> {
                if (json != null && ("json".equals(specify) || keypairs.isEmpty())) {
                    config.put("bodyType", "json");
                    config.put("body", c.tx(json));
                } else if (!keypairs.isEmpty()) {
                    config.put("bodyType", "json");
                    config.put("body", pairsAsJson(keypairs));
                } else {
                    config.put("bodyType", "none");
                }
            }
            case "form-urlencoded" -> {
                config.put("bodyType", "form");
                config.put("body", pairsAsJson(keypairs));
            }
            case "raw" -> {
                config.put("bodyType", "text");
                config.put("body", c.tx(firstNonBlank(c.text("body"), "")));
            }
            default -> {
                config.put("bodyType", "none");
                c.lose(
                        "The request body type '"
                                + AgentImportText.inline(ct, 40)
                                + "' (multipart / binary) is not "
                                + "supported by the HTTP node; the body was not carried over.");
            }
        }
    }

    private List<Map<String, Object>> pairs(NodeCtx c, JsonNode list) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode item : list) {
            String name = AgentImportJson.text(item, "name");
            JsonNode value = item.get("value");
            if (name == null) {
                continue;
            }
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("name", name);
            pair.put("value", value == null || value.isNull() ? "" : c.tx(value.asText()));
            out.add(pair);
        }
        return out;
    }

    private String pairsAsJson(List<Map<String, Object>> pairs) {
        ObjectNode object = mapper.createObjectNode();
        for (Map<String, Object> pair : pairs) {
            object.put(String.valueOf(pair.get("name")), String.valueOf(pair.get("value")));
        }
        return object.toString();
    }

    private static JsonNode firstNonNull(JsonNode a, JsonNode b) {
        return a != null ? a : b;
    }
}
