package com.agenticai.core.knowledge.connector;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Notion connector - syncs pages under a database or parent page into the
 * collection as markdown-ish text (headings/paragraphs/lists flattened to
 * plain lines, the exact granularity the chunker expects).
 *
 * <p><b>Config keys:</b> {@code token} (internal-integration secret,
 * decrypted by the service), {@code databaseId} XOR {@code pageId} (the
 * subtree root). Both ids accept the dashed or bare form - the API takes
 * either.
 *
 * <p>Two calls per page: {@code POST /v1/search} (or a direct children list
 * when a pageId root is given) to enumerate, then {@code GET
 * /v1/blocks/{id}/children} for content - the search endpoint returns page
 * stubs, not text. Pagination on both is followed via {@code next_cursor}
 * up to {@link ConnectorHttp#MAX_DOCUMENTS_PER_SYNC} pages per sync.
 */
@Component
public class NotionConnectorFetcher implements ConnectorFetcher {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String API = "https://api.notion.com/v1";
    private static final int BLOCK_DEPTH_LIMIT = 2;

    private final RestClient restClient;

    public NotionConnectorFetcher(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public ConnectorType getType() {
        return ConnectorType.NOTION;
    }

    @Override
    public List<ConnectorDocument> fetch(Map<String, String> config, String cursor) {
        String token = require(config, "token");
        String databaseId = config.get("databaseId");
        String pageId = config.get("pageId");
        if ((databaseId == null || databaseId.isBlank()) && (pageId == null || pageId.isBlank())) {
            throw new ConnectorFetchException("Notion connector needs databaseId or pageId");
        }

        List<String> pageIds = new ArrayList<>();
        Map<String, String> titles = new java.util.HashMap<>();
        if (databaseId != null && !databaseId.isBlank()) {
            queryDatabase(token, databaseId, pageIds, titles);
        } else {
            pageIds.add(pageId);
        }

        List<ConnectorDocument> docs = new ArrayList<>();
        for (String pid : pageIds) {
            if (docs.size() >= ConnectorHttp.MAX_DOCUMENTS_PER_SYNC) {
                break;
            }
            String title = titles.getOrDefault(pid, "notion-page-" + pid);
            StringBuilder text = new StringBuilder("# ").append(title).append("\n\n");
            collectBlocks(token, pid, text, 0);
            String url = "https://www.notion.so/" + pid.replace("-", "");
            docs.add(new ConnectorDocument(
                pid, title, url, sanitizeFilename(title) + ".md",
                text.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return docs;
    }

    private void queryDatabase(String token, String databaseId, List<String> pageIds, Map<String, String> titles) {
        String nextCursor = null;
        do {
            String body = nextCursor == null
                ? "{\"page_size\":100}"
                : "{\"page_size\":100,\"start_cursor\":\"" + nextCursor + "\"}";
            JsonNode resp = post("/databases/" + databaseId + "/query", body, token);
            for (JsonNode row : resp.path("results")) {
                String id = row.path("id").asText();
                if (id.isBlank()) {
                    continue;
                }
                pageIds.add(id);
                String title = extractPageTitle(row);
                if (title != null) {
                    titles.put(id, title);
                }
            }
            nextCursor = resp.path("has_more").asBoolean(false)
                ? resp.path("next_cursor").asText(null) : null;
        } while (nextCursor != null && pageIds.size() < ConnectorHttp.MAX_DOCUMENTS_PER_SYNC);
    }

    private void collectBlocks(String token, String blockId, StringBuilder out, int depth) {
        String nextCursor = null;
        do {
            JsonNode resp = get("/blocks/" + blockId + "/children?page_size=100"
                + (nextCursor == null ? "" : "&start_cursor=" + nextCursor), token);
            for (JsonNode block : resp.path("results")) {
                String text = extractPlainText(block);
                if (!text.isBlank()) {
                    out.append(text).append("\n\n");
                }
                if (depth < BLOCK_DEPTH_LIMIT && block.path("has_children").asBoolean(false)) {
                    collectBlocks(token, block.path("id").asText(), out, depth + 1);
                }
            }
            nextCursor = resp.path("has_more").asBoolean(false)
                ? resp.path("next_cursor").asText(null) : null;
        } while (nextCursor != null);
    }

    /** Headings/paragraphs/lists all carry their text in {type}.rich_text[].plain_text. */
    private static String extractPlainText(JsonNode block) {
        String type = block.path("type").asText("");
        JsonNode rich = block.path(type).path("rich_text");
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : rich) {
            sb.append(t.path("plain_text").asText(""));
        }
        if (sb.length() == 0) {
            return "";
        }
        return switch (type) {
            case "heading_1" -> "# " + sb;
            case "heading_2" -> "## " + sb;
            case "heading_3" -> "### " + sb;
            case "bulleted_list_item", "numbered_list_item" -> "- " + sb;
            default -> sb.toString();
        };
    }

    private static String extractPageTitle(JsonNode page) {
        JsonNode props = page.path("properties");
        for (var fields = JsonNodes.fields(props); fields.hasNext();) {
            JsonNode prop = fields.next().getValue();
            if ("title".equals(prop.path("type").asText(""))) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode t : prop.path("title")) {
                    sb.append(t.path("plain_text").asText(""));
                }
                return sb.length() == 0 ? null : sb.toString();
            }
        }
        return null;
    }

    private JsonNode get(String path, String token) {
        try {
            return MAPPER.readTree(restClient.get()
                .uri(API + path)
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .retrieve()
                .body(String.class));
        } catch (Exception e) {
            throw new ConnectorFetchException("Notion fetch failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String path, String json, String token) {
        try {
            return MAPPER.readTree(restClient.post()
                .uri(API + path)
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(String.class));
        } catch (Exception e) {
            throw new ConnectorFetchException("Notion fetch failed: " + e.getMessage(), e);
        }
    }

    static String sanitizeFilename(String title) {
        String clean = title.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("^-+|-+$", "")
            .toLowerCase(java.util.Locale.ROOT);
        clean = clean.substring(0, Math.min(80, clean.length()));
        return clean.isBlank() ? "untitled" : clean;
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new ConnectorFetchException("Notion connector missing config: " + key);
        }
        return v;
    }
}
