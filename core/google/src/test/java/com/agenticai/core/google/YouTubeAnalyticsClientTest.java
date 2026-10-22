package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link YouTubeAnalyticsClient} tests. Its per-request shape (Bearer GET, status check, "YouTube
 * Data API call failed (N): body" translation) is identical to the other clients' and is
 * deliberately NOT re-tested exhaustively here - Gmail/Drive/Sheets/Calendar already pin that
 * pattern. What IS unique, and what this file covers, is the three-call orchestration in {@code
 * recentVideoPerformance} (channel -> uploads playlist -> batched video stats), its empty-channel
 * short-circuits, and the "no channel" error message.
 */
class YouTubeAnalyticsClientTest {

    private YouTubeAnalyticsClient client;
    private HttpStubs.RecordingHttpClient http;

    private static final String CHANNEL_JSON =
            "{\"items\":[{"
                    + "\"id\":\"UCabc\",\"snippet\":{\"title\":\"My Channel\"},"
                    + "\"statistics\":{\"subscriberCount\":\"1200\",\"viewCount\":\"99000\",\"videoCount\":\"31\"},"
                    + "\"contentDetails\":{\"relatedPlaylists\":{\"uploads\":\"UUabc\"}}}]}";

    @BeforeEach
    void setUp() {
        GoogleOAuthService oauthService = mock(GoogleOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn("at-123");
        client = new YouTubeAnalyticsClient(new ObjectMapper(), oauthService);
        http = HttpStubs.install(client);
    }

    @Test
    void channelSummaryRequestsOwnChannelWithStatsAndParsesCounts() {
        http.enqueue(200, CHANNEL_JSON);

        YouTubeAnalyticsClient.ChannelSummary summary = client.channelSummary();

        assertEquals(
                "https://www.googleapis.com/youtube/v3/channels?part=snippet%2Cstatistics%2CcontentDetails&mine=true",
                http.onlyRequest().uri().toString());
        assertEquals(
                "Bearer at-123",
                http.onlyRequest().headers().firstValue("Authorization").orElse(null));
        assertEquals(
                new YouTubeAnalyticsClient.ChannelSummary(
                        "My Channel", "UCabc", 1200, 99000, 31, "UUabc"),
                summary);
    }

    @Test
    void accountWithoutAChannelIsACleanErrorNotAnIndexOutOfBounds() {
        // A Google account that never created a YouTube channel returns an
        // empty items array - the agent must see a sentence, not a crash.
        http.enqueue(200, "{\"items\":[]}");

        GoogleApiException ex =
                assertThrows(GoogleApiException.class, () -> client.channelSummary());

        assertTrue(ex.getMessage().contains("No YouTube channel found"));
    }

    @Test
    void recentVideoPerformanceChainsChannelPlaylistAndBatchedVideoStats() {
        http.enqueue(200, CHANNEL_JSON);
        http.enqueue(
                200,
                "{\"items\":["
                        + "{\"contentDetails\":{\"videoId\":\"v1\"}},"
                        + "{\"contentDetails\":{\"videoId\":\"v2\"}}]}");
        http.enqueue(
                200,
                "{\"items\":["
                        + "{\"id\":\"v1\",\"snippet\":{\"title\":\"Video one\",\"publishedAt\":\"2026-08-01T00:00:00Z\"},"
                        + "\"statistics\":{\"viewCount\":\"400\",\"likeCount\":\"20\",\"commentCount\":\"3\"}},"
                        + "{\"id\":\"v2\",\"snippet\":{\"title\":\"Video two\",\"publishedAt\":\"2026-07-20T00:00:00Z\"},"
                        + "\"statistics\":{\"viewCount\":\"100\",\"likeCount\":\"5\",\"commentCount\":\"1\"}}]}");

        List<YouTubeAnalyticsClient.VideoPerformance> videos = client.recentVideoPerformance(2);

        assertEquals(
                3, http.requests.size(), "channel -> playlistItems -> videos, exactly three calls");
        assertEquals(
                "https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=2&playlistId=UUabc",
                http.requests.get(1).uri().toString());
        // One batched videos.list for all ids - not one call per video.
        assertEquals(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet%2Cstatistics&id=v1%2Cv2",
                http.requests.get(2).uri().toString());
        assertEquals(2, videos.size());
        assertEquals(
                new YouTubeAnalyticsClient.VideoPerformance(
                        "v1", "Video one", "2026-08-01T00:00:00Z", 400, 20, 3),
                videos.get(0));
    }

    @Test
    void emptyUploadsPlaylistShortCircuitsWithoutTheVideosCall() {
        http.enqueue(200, CHANNEL_JSON);
        http.enqueue(200, "{\"items\":[]}"); // channel exists but has no uploads

        assertEquals(List.of(), client.recentVideoPerformance(5));
        assertEquals(2, http.requests.size(), "no ids means the videos.list call must be skipped");
    }
}
