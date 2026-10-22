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
import org.springframework.stereotype.Component;

/**
 * Master-Roadmap-2026.md item #8 ("Content feedback loop") - Phase 1 of three planned data sources
 * (Google Search Console, YouTube Analytics, TikTok/Instagram Graph API - see {@link
 * GoogleScopes#YOUTUBE_READONLY}'s javadoc for why this one shipped first: it reuses this
 * platform's existing Google OAuth connection rather than standing up a brand-new vendor
 * integration, unlike TikTok/Instagram which have zero existing OAuth plumbing here today). YouTube
 * Data API v3 REST calls, same "plain HttpClient, no SDK" style as {@link GoogleDriveClient}.
 *
 * <p>Three chained calls make up {@link #recentVideoPerformance}: {@code channels.list(mine=true)}
 * resolves the connected account's "uploads" playlist ID (every channel has exactly one,
 * auto-created by YouTube), {@code playlistItems.list} walks that playlist for the most recent
 * video IDs, then {@code videos.list} fetches each video's public stats in one batched call. This
 * is the same three-call shape YouTube's own docs recommend for "list my channel's videos with
 * stats" - there's no single endpoint that returns both a channel's video list and their view/like/
 * comment counts together.
 *
 * <p><b>Explicitly NOT included</b> (see {@link GoogleScopes#YOUTUBE_READONLY}): watch-time,
 * average view duration, traffic sources, audience retention, or subscriber-gained/lost deltas -
 * all of those live behind the YouTube Analytics API's {@code yt-analytics.readonly} scope, which
 * requires a Google security assessment for verification. What's here (view/like/ comment counts,
 * upload recency) is already public information visible on the channel's own YouTube page, which is
 * why {@code youtube.readonly} doesn't carry that same requirement. A future pass can add the
 * Analytics API once that verification is worth the compliance cost for this platform's user base.
 */
@Component
public class YouTubeAnalyticsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE = "https://www.googleapis.com/youtube/v3";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public YouTubeAnalyticsClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    /**
     * Channel-level totals (lifetime subscriber/view/video counts) for the connected account's own
     * channel.
     */
    public ChannelSummary channelSummary() {
        JsonNode body =
                get(
                        BASE
                                + "/channels?part="
                                + urlEncode("snippet,statistics,contentDetails")
                                + "&mine=true");
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new GoogleApiException(
                    "No YouTube channel found for the connected Google account.");
        }
        JsonNode item = items.get(0);
        JsonNode stats = item.path("statistics");
        return new ChannelSummary(
                item.path("snippet").path("title").asText(null),
                item.path("id").asText(null),
                stats.path("subscriberCount").asLong(0),
                stats.path("viewCount").asLong(0),
                stats.path("videoCount").asLong(0),
                item.path("contentDetails").path("relatedPlaylists").path("uploads").asText(null));
    }

    /**
     * The core "content feedback loop" call - recent uploads plus their public performance stats,
     * in upload-recency order. Feeds an agent's next reply with real numbers ("your last video got
     * 4x the views of the one before it") rather than the agent having no visibility into how past
     * content actually performed at all.
     */
    public List<VideoPerformance> recentVideoPerformance(int maxResults) {
        int capped = Math.max(1, Math.min(maxResults, 25));
        String uploadsPlaylistId = channelSummary().uploadsPlaylistId();
        if (uploadsPlaylistId == null) {
            return List.of();
        }
        List<String> videoIds = new ArrayList<>();
        JsonNode playlistBody =
                get(
                        BASE
                                + "/playlistItems?part=contentDetails&maxResults="
                                + capped
                                + "&playlistId="
                                + urlEncode(uploadsPlaylistId));
        for (JsonNode item : playlistBody.path("items")) {
            String videoId = item.path("contentDetails").path("videoId").asText(null);
            if (videoId != null) {
                videoIds.add(videoId);
            }
        }
        if (videoIds.isEmpty()) {
            return List.of();
        }
        JsonNode videosBody =
                get(
                        BASE
                                + "/videos?part="
                                + urlEncode("snippet,statistics")
                                + "&id="
                                + urlEncode(String.join(",", videoIds)));
        List<VideoPerformance> results = new ArrayList<>();
        for (JsonNode video : videosBody.path("items")) {
            JsonNode snippet = video.path("snippet");
            JsonNode stats = video.path("statistics");
            results.add(
                    new VideoPerformance(
                            video.path("id").asText(null),
                            snippet.path("title").asText(null),
                            snippet.path("publishedAt").asText(null),
                            stats.path("viewCount").asLong(0),
                            stats.path("likeCount").asLong(0),
                            stats.path("commentCount").asLong(0)));
        }
        return results;
    }

    private JsonNode get(String uri) {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + oauthService.currentAccessToken())
                        .GET()
                        .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach the YouTube Data API: " + e.getMessage(), e);
        }
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "YouTube Data API call failed ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not parse YouTube Data API response: " + e.getMessage(), e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ChannelSummary(
            String title,
            String channelId,
            long subscriberCount,
            long viewCount,
            long videoCount,
            String uploadsPlaylistId) {}

    public record VideoPerformance(
            String videoId,
            String title,
            String publishedAt,
            long viewCount,
            long likeCount,
            long commentCount) {}
}
