package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * YouTube Data API v3 resumable video upload - the write counterpart of {@link
 * YouTubeAnalyticsClient} (read-only). Same "plain HttpClient, no SDK" style as every other Google
 * client in this module.
 *
 * <p>Requires {@link GoogleScopes#YOUTUBE_UPLOAD} on the tenant's active Google credential -
 * callers must check {@code GoogleCredential#hasScope(GoogleScopes.YOUTUBE_UPLOAD)} before calling
 * {@link #upload}; this class does not check it itself (it has no access to the credential row,
 * only a minted access token via {@link GoogleOAuthService#currentAccessToken()}), and a call made
 * without the scope simply fails with a 403 from Google, surfaced as a {@link GoogleApiException}.
 *
 * <p><b>Two-step resumable upload</b>, per YouTube's own recommended shape for anything past a
 * trivial size (small multipart uploads are flakier and can't resume on a dropped connection, which
 * matters for video files):
 *
 * <ol>
 *   <li>{@code POST .../upload/youtube/v3/videos?uploadType=resumable} with the video's metadata as
 *       a JSON body and the real byte count in {@code X-Upload-Content-Length} - Google responds
 *       with a session URL in the {@code Location} header, no bytes sent yet.
 *   <li>{@code PUT} the actual video bytes to that session URL. YouTube Data API's resumable
 *       protocol supports chunked resumption on failure; this implementation does the simple
 *       single-PUT case (the whole stream in one request) rather than the chunked-retry variant,
 *       matching the "reserved for now, exercised for real once this is live" scope of the
 *       2026-08-30 rollout - see {@link GoogleScopes#YOUTUBE_UPLOAD}'s javadoc.
 * </ol>
 */
@Component
public class YouTubeUploadClient {

    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(15);
    // Video files can be large and the connection is doing real upstream
    // work the whole time - a much longer timeout than every other Google
    // client in this module, which only ever move a few KB of JSON.
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final String UPLOAD_ENDPOINT =
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status";

    private final HttpClient httpClient =
            GuardedJdkHttpClient.defaults(METADATA_TIMEOUT, METADATA_TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public YouTubeUploadClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    /**
     * @param title required, max 100 chars per YouTube's own limit (not enforced here - a too-long
     *     title is a normal 400 from Google, surfaced like any other {@link GoogleApiException})
     * @param description optional, empty string if null
     * @param tags optional, empty list if null
     * @param privacyStatus one of {@code "private"}, {@code "unlisted"}, {@code "public"} - callers
     *     should default to {@code "private"}; see {@code UploadVideoToYouTubeToolCallback} for
     *     where that default actually lives
     * @param categoryId a YouTube video category id, defaults to {@code "22"} (People & Blogs) if
     *     null/blank - YouTube requires SOME category
     * @param video the video bytes; this method consumes and closes it
     * @param contentType e.g. {@code "video/mp4"}
     * @param contentLength exact byte count - required upfront by the resumable-upload protocol's
     *     {@code X-Upload-Content-Length} header
     * @return the created video's id and canonical watch URL
     */
    public UploadedVideo upload(
            String title,
            String description,
            List<String> tags,
            String privacyStatus,
            String categoryId,
            InputStream video,
            String contentType,
            long contentLength) {
        String sessionUrl =
                startSession(
                        title,
                        description,
                        tags,
                        privacyStatus,
                        categoryId,
                        contentType,
                        contentLength);
        return putBytes(sessionUrl, video, contentType, contentLength);
    }

    private String startSession(
            String title,
            String description,
            List<String> tags,
            String privacyStatus,
            String categoryId,
            String contentType,
            long contentLength) {
        Map<String, Object> snippet = new java.util.HashMap<>();
        snippet.put("title", title);
        snippet.put("description", description == null ? "" : description);
        snippet.put("tags", tags == null ? List.of() : tags);
        snippet.put("categoryId", (categoryId == null || categoryId.isBlank()) ? "22" : categoryId);
        Map<String, Object> status = Map.of("privacyStatus", privacyStatus);
        Map<String, Object> body = Map.of("snippet", snippet, "status", status);

        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not build the YouTube upload request: " + e.getMessage(), e);
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(UPLOAD_ENDPOINT))
                        .timeout(METADATA_TIMEOUT)
                        .header("Authorization", "Bearer " + oauthService.currentAccessToken())
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("X-Upload-Content-Type", contentType)
                        .header("X-Upload-Content-Length", String.valueOf(contentLength))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach the YouTube Data API to start the upload: " + e.getMessage(),
                    e);
        }
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "YouTube rejected the upload request ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        return response.headers()
                .firstValue("Location")
                .orElseThrow(
                        () ->
                                new GoogleApiException(
                                        "YouTube did not return an upload session URL."));
    }

    private UploadedVideo putBytes(
            String sessionUrl, InputStream video, String contentType, long contentLength) {
        HttpRequest request;
        try {
            request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(sessionUrl))
                            .timeout(UPLOAD_TIMEOUT)
                            .header("Authorization", "Bearer " + oauthService.currentAccessToken())
                            .header("Content-Type", contentType)
                            .header("Content-Length", String.valueOf(contentLength))
                            .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> video))
                            .build();
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not prepare the video upload: " + e.getMessage(), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "The video upload to YouTube failed: " + e.getMessage(), e);
        } finally {
            try {
                video.close();
            } catch (Exception ignored) {
                // Best-effort close - the upload's own success/failure is what matters, not this.
            }
        }
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "YouTube rejected the video upload ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not parse YouTube's upload response: " + e.getMessage(), e);
        }
        String videoId = body.path("id").asText(null);
        if (videoId == null) {
            throw new GoogleApiException(
                    "YouTube's upload response had no video id: " + response.body());
        }
        return new UploadedVideo(videoId, "https://www.youtube.com/watch?v=" + videoId);
    }

    public record UploadedVideo(String videoId, String watchUrl) {}
}
