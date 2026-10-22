package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.DifyImportModel.Ctx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Maps a Dify HTTP Request node onto the HTTP node's config. Part of {@link DifyImportMapper}. */
final class DifyHttpMapping {

    private final ObjectMapper mapper;

    DifyHttpMapping(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The HTTP node config, or {@code null} when the source node has no URL to carry over. */
    Map<String, Object> config(Ctx c) {
        c.inHttp = true;
        String url = c.text(AgentImportJson.text(c.data, "url"));
        if (url == null || url.isBlank()) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        String method = AgentImportJson.text(c.data, "method");
        config.put("method", method == null ? "GET" : method.toUpperCase(Locale.ROOT));
        config.put("url", url);
        List<Map<String, Object>> headers =
                keyValueLines(c, AgentImportJson.text(c.data, "headers"));
        List<Map<String, Object>> query = keyValueLines(c, AgentImportJson.text(c.data, "params"));

        JsonNode authorization = AgentImportJson.obj(c.data, "authorization");
        String authType =
                authorization == null ? null : AgentImportJson.text(authorization, "type");
        if (authType != null && !"no-auth".equals(authType)) {
            JsonNode authConfig = AgentImportJson.obj(authorization, "config");
            String scheme = authConfig == null ? null : AgentImportJson.text(authConfig, "type");
            String secret = authConfig == null ? null : AgentImportJson.text(authConfig, "api_key");
            if (secret != null && secret.startsWith("[[CREDENTIAL_") && "bearer".equals(scheme)) {
                headers.add(pair("Authorization", "Bearer " + secret));
            } else if (secret != null
                    && secret.startsWith("[[CREDENTIAL_")
                    && "custom".equals(scheme)
                    && AgentImportJson.text(authConfig, "header") != null) {
                headers.add(pair(AgentImportJson.text(authConfig, "header"), secret));
            }
            c.lose(
                    "The API key was not imported (only a [[CREDENTIAL_..]] marker is left in the header, if any). "
                            + "Replace it with a {{secret.NAME}} vault reference.");
        }
        if (!headers.isEmpty()) {
            config.put("headers", headers);
        }
        if (!query.isEmpty()) {
            config.put("query", query);
        }

        JsonNode body = AgentImportJson.obj(c.data, "body");
        String bodyType =
                body == null ? "none" : String.valueOf(AgentImportJson.text(body, "type"));
        switch (bodyType) {
            case "json" -> {
                config.put("bodyType", "json");
                config.put("body", c.text(bodyData(body)));
            }
            case "raw-text" -> {
                config.put("bodyType", "text");
                config.put("body", c.text(bodyData(body)));
            }
            case "x-www-form-urlencoded" -> {
                config.put("bodyType", "form");
                config.put("body", linesAsJson(keyValueLines(c, bodyData(body))));
            }
            case "none", "null" -> config.put("bodyType", "none");
            default -> {
                config.put("bodyType", "none");
                c.lose(
                        "The request body type '"
                                + AgentImportText.inline(bodyType, 40)
                                + "' (form-data / binary) is not supported by the HTTP node; the body was not carried over.");
            }
        }
        JsonNode timeout = AgentImportJson.obj(c.data, "timeout");
        Double read = timeout == null ? null : AgentImportJson.number(timeout, "max_read_timeout");
        if (read != null && read > 0) {
            config.put("timeoutSeconds", (int) Math.max(1, Math.min(30, Math.ceil(read))));
        }
        if (c.data.has("retry_config")
                && c.data.path("retry_config").path("retry_enabled").asBoolean(false)) {
            c.lose("Retry settings were not carried over (use the workflow's retry limit).");
        }
        return config;
    }

    /**
     * The body's {@code data}: a string, or a list of {key, value} entries rendered as "key:value"
     * lines.
     */
    private String bodyData(JsonNode body) {
        JsonNode data = body == null ? null : body.get("data");
        if (data == null || data.isNull()) {
            return "";
        }
        if (data.isString()) {
            return data.asString();
        }
        if (data.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : data) {
                String key = AgentImportJson.text(item, "key");
                if (key != null) {
                    JsonNode v = item.get("value");
                    sb.append(key)
                            .append(':')
                            .append(v == null || v.isNull() ? "" : v.asString())
                            .append('\n');
                }
            }
            return sb.toString();
        }
        return data.toString();
    }

    private List<Map<String, Object>> keyValueLines(Ctx c, String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            if (name.isEmpty()) {
                continue;
            }
            out.add(pair(name, c.text(line.substring(colon + 1).strip())));
        }
        return out;
    }

    private static Map<String, Object> pair(String name, String value) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("name", name);
        pair.put("value", value);
        return pair;
    }

    private String linesAsJson(List<Map<String, Object>> lines) {
        ObjectNode object = mapper.createObjectNode();
        for (Map<String, Object> line : lines) {
            object.put(String.valueOf(line.get("name")), String.valueOf(line.get("value")));
        }
        return object.toString();
    }
}
