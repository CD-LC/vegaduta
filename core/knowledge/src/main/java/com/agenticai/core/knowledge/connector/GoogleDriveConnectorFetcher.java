package com.agenticai.core.knowledge.connector;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Google Drive connector - lists files under a folder (or shared drive root) and exports/downloads
 * each into bytes for the shared ingest path.
 *
 * <p><b>Config keys:</b> {@code accessToken} (OAuth access or service- account-impersonation token
 * - decrypted by the service), {@code folderId} (optional - absent means "My Drive root").
 *
 * <p>Export mapping: Google-native docs/sheets/slides export to {@code text/plain}/{@code text/csv}
 * (the {@code files.export} endpoint); binary files (pdf/docx/html/md) download via {@code
 * ?alt=media} and keep their real extension so {@code ingestFile}'s sidecar extraction handles them
 * exactly like an upload of the same file. Unexportable types (images, video, zip) are skipped - a
 * knowledge connector can't cite what it can't read.
 */
@Component
public class GoogleDriveConnectorFetcher implements ConnectorFetcher {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final int PAGE_LIMIT = 200;

    private static final Map<String, String> GOOGLE_EXPORT_MIME =
            Map.of(
                    "application/vnd.google-apps.document", "text/plain",
                    "application/vnd.google-apps.spreadsheet", "text/csv",
                    "application/vnd.google-apps.presentation", "text/plain");

    private final RestClient restClient;

    public GoogleDriveConnectorFetcher(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public ConnectorType getType() {
        return ConnectorType.GOOGLE_DRIVE;
    }

    @Override
    public List<ConnectorDocument> fetch(Map<String, String> config, String cursor) {
        String token = require(config, "accessToken");
        String folderId = config.get("folderId");

        String query =
                folderId == null || folderId.isBlank()
                        ? "trashed=false and mimeType != 'application/vnd.google-apps.folder'"
                        : "'" + folderId + "' in parents and trashed=false";
        String listUrl =
                API
                        + "/files?q="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&pageSize="
                        + PAGE_LIMIT
                        + "&fields=files(id,name,mimeType,webViewLink)&supportsAllDrives=true&includeItemsFromAllDrives=true";

        List<ConnectorDocument> docs = new ArrayList<>();
        try {
            JsonNode listing =
                    MAPPER.readTree(
                            restClient
                                    .get()
                                    .uri(listUrl)
                                    .header("Authorization", "Bearer " + token)
                                    .retrieve()
                                    .body(String.class));
            for (JsonNode file : listing.path("files")) {
                if (docs.size() >= ConnectorHttp.MAX_DOCUMENTS_PER_SYNC) {
                    break;
                }
                String id = file.path("id").asText();
                String name = file.path("name").asText("untitled");
                String mime = file.path("mimeType").asText("");
                String link =
                        file.path("webViewLink")
                                .asText("https://drive.google.com/file/d/" + id + "/view");

                byte[] content;
                String filename;
                String exportMime = GOOGLE_EXPORT_MIME.get(mime);
                if (exportMime != null) {
                    content =
                            getBytes(
                                    API
                                            + "/files/"
                                            + id
                                            + "/export?mimeType="
                                            + URLEncoder.encode(exportMime, StandardCharsets.UTF_8),
                                    token);
                    filename =
                            NotionConnectorFetcher.sanitizeFilename(name)
                                    + ("text/csv".equals(exportMime) ? ".csv" : ".md");
                } else if (mime.startsWith("text/")
                        || mime.contains("pdf")
                        || mime.contains("wordprocessingml")
                        || mime.contains("html")
                        || mime.contains("markdown")) {
                    content = getBytes(API + "/files/" + id + "?alt=media", token);
                    filename = name.contains(".") ? name : name + extensionFor(mime);
                } else {
                    continue; // images/video/zips - nothing to ingest
                }
                if (content != null && content.length > 0) {
                    docs.add(new ConnectorDocument(id, name, link, filename, content));
                }
            }
        } catch (ConnectorFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorFetchException("Google Drive fetch failed: " + e.getMessage(), e);
        }
        return docs;
    }

    private byte[] getBytes(String url, String token) {
        try {
            return ConnectorHttp.readBounded(
                    restClient
                            .get()
                            .uri(url)
                            .header("Authorization", "Bearer " + token)
                            .retrieve()
                            .body(java.io.InputStream.class));
        } catch (Exception e) {
            throw new ConnectorFetchException("Drive download failed: " + e.getMessage(), e);
        }
    }

    private static String extensionFor(String mime) {
        if (mime.contains("pdf")) {
            return ".pdf";
        }
        if (mime.contains("wordprocessingml")) {
            return ".docx";
        }
        if (mime.contains("html")) {
            return ".html";
        }
        return ".md";
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new ConnectorFetchException("Google Drive connector missing config: " + key);
        }
        return v;
    }
}
