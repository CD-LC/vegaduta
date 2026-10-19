package com.agenticai.core.knowledge.connector;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * SharePoint/OneDrive-for-Business connector via Microsoft Graph - lists a
 * drive's root children and downloads each file's content for the shared
 * ingest path.
 *
 * <p><b>Config keys:</b> {@code accessToken} (Graph token with
 * {@code Files.Read.All}/{@code Sites.Read.All} - decrypted by the
 * service), {@code driveId} (the document library id from Graph).
 *
 * <p>Only readable file types are downloaded (text/pdf/docx/html/md) -
 * same policy as {@link GoogleDriveConnectorFetcher}: a connector can't
 * cite what it can't extract.
 */
@Component
public class SharePointConnectorFetcher implements ConnectorFetcher {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";

    private final RestClient restClient;

    public SharePointConnectorFetcher(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public ConnectorType getType() {
        return ConnectorType.SHAREPOINT;
    }

    @Override
    public List<ConnectorDocument> fetch(Map<String, String> config, String cursor) {
        String token = require(config, "accessToken");
        String driveId = require(config, "driveId");

        String listUrl = GRAPH + "/drives/" + driveId
            + "/root/children?$select=id,name,file,webUrl&$top=" + ConnectorHttp.MAX_DOCUMENTS_PER_SYNC;
        List<ConnectorDocument> docs = new ArrayList<>();
        try {
            JsonNode listing = MAPPER.readTree(restClient.get()
                .uri(listUrl)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class));
            for (JsonNode item : listing.path("value")) {
                if (item.path("file").isMissingNode() || item.path("file").isNull()) {
                    continue; // folders have no file facet - skip (lite: no recursion)
                }
                String id = item.path("id").asText();
                String name = item.path("name").asText("untitled");
                if (!isReadable(name)) {
                    continue;
                }
                byte[] content = ConnectorHttp.readBounded(restClient.get()
                    .uri(GRAPH + "/drives/" + driveId + "/items/" + id + "/content")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(java.io.InputStream.class));
                if (content != null && content.length > 0) {
                    docs.add(new ConnectorDocument(
                        id, name, item.path("webUrl").asText(""), name, content));
                }
            }
        } catch (ConnectorFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorFetchException("SharePoint fetch failed: " + e.getMessage(), e);
        }
        return docs;
    }

    private static boolean isReadable(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".pdf")
            || lower.endsWith(".docx") || lower.endsWith(".html") || lower.endsWith(".htm")
            || lower.endsWith(".csv");
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new ConnectorFetchException("SharePoint connector missing config: " + key);
        }
        return v;
    }
}
