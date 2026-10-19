package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Google Drive v3 REST calls, scoped to {@code drive.file} (see {@code
 * GoogleScopes}'s javadoc) - so {@link #listFiles} only ever sees files this
 * platform itself created via {@link #createTextFile}, or files the
 * connected user has explicitly opened with this app, never their whole
 * Drive. {@link #readFile} uses {@code alt=media}, which works for plain
 * text/binary files but not native Google Docs/Sheets/Slides (those need
 * the separate {@code export} endpoint with a target mime type) - an
 * honest MVP scope, not full Drive format coverage.
 */
@Component
public class GoogleDriveClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3/files";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public GoogleDriveClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    public List<DriveFile> listFiles(String nameContains, int maxResults) {
        String q = (nameContains == null || nameContains.isBlank())
            ? ""
            : "&q=" + urlEncode("name contains '" + nameContains.replace("'", "\\'") + "'");
        String uri = BASE + "?pageSize=" + Math.max(1, Math.min(maxResults, 25))
            + "&fields=" + urlEncode("files(id,name,mimeType,modifiedTime)") + q;
        JsonNode body = get(uri);
        List<DriveFile> files = new ArrayList<>();
        for (JsonNode f : body.path("files")) {
            files.add(new DriveFile(
                f.path("id").asText(null), f.path("name").asText(null),
                f.path("mimeType").asText(null), f.path("modifiedTime").asText(null)
            ));
        }
        return files;
    }

    public String readFile(String fileId) {
        HttpRequest request = authedRequest(BASE + "/" + urlEncode(fileId) + "?alt=media").GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new GoogleApiException("Could not read Drive file " + fileId + " (" + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    /** Two-step create: metadata-only POST, then a media-upload PATCH for the content - simpler than a multipart request. */
    public DriveFile createTextFile(String name, String content) {
        return createTextFile(name, content, null);
    }

    /**
     * Same as {@link #createTextFile(String, String)}, plus an optional
     * {@code folderId} - added for the workflow Output node's Google Drive
     * destination (#156), which lets a tenant point uploads at a specific
     * folder rather than always landing in Drive's root. {@code null}/blank
     * behaves exactly like the original method (root-level create); the
     * {@code drive.file} scope this client is limited to (see class javadoc)
     * still applies, so the folder itself must already be one this app's
     * connection can see (created or opened by it), same MVP scope as
     * everything else here.
     */
    public DriveFile createTextFile(String name, String content, String folderId) {
        JsonNode created;
        try {
            Map<String, Object> metadata = (folderId == null || folderId.isBlank())
                ? Map.of("name", name)
                : Map.of("name", name, "parents", List.of(folderId));
            String metadataJson = objectMapper.writeValueAsString(metadata);
            HttpRequest createRequest = authedRequest(BASE + "?fields=" + urlEncode("id,name,mimeType,modifiedTime"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(metadataJson))
                .build();
            HttpResponse<String> createResponse = send(createRequest);
            if (createResponse.statusCode() >= 300) {
                throw new GoogleApiException("Could not create Drive file (" + createResponse.statusCode() + "): " + createResponse.body());
            }
            created = objectMapper.readTree(createResponse.body());
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not create Drive file: " + e.getMessage(), e);
        }
        String fileId = created.path("id").asText();
        HttpRequest uploadRequest = authedRequest(UPLOAD_BASE + "/" + urlEncode(fileId) + "?uploadType=media")
            .header("Content-Type", "text/plain; charset=UTF-8")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> uploadResponse = send(uploadRequest);
        if (uploadResponse.statusCode() >= 300) {
            throw new GoogleApiException("Created the Drive file but could not write its content (" + uploadResponse.statusCode() + "): " + uploadResponse.body());
        }
        return new DriveFile(fileId, created.path("name").asText(name), created.path("mimeType").asText(null), created.path("modifiedTime").asText(null));
    }

    private JsonNode get(String uri) {
        HttpRequest request = authedRequest(uri).GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new GoogleApiException("Google Drive API call failed (" + response.statusCode() + "): " + response.body());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException("Could not parse Google Drive API response: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder authedRequest(String uri) {
        return HttpRequest.newBuilder().uri(URI.create(uri)).timeout(TIMEOUT).header("Authorization", "Bearer " + oauthService.currentAccessToken());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException("Could not reach the Google Drive API: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record DriveFile(String id, String name, String mimeType, String modifiedTime) {
    }
}
