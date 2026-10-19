package com.agenticai.core.google.agenttools;

import com.agenticai.core.agentspi.GeneratedVideoReader;
import com.agenticai.core.google.GoogleApiException;
import com.agenticai.core.google.YouTubeUploadClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Publishes a completed {@code generate_video} job (see {@code GenerateVideoToolCallback}) to the
 * tenant's connected YouTube channel. Requires {@link
 * com.agenticai.core.google.GoogleScopes#YOUTUBE_UPLOAD} on the tenant's Google connection - {@code
 * GoogleWorkspaceToolSource} only offers this tool at all once that scope is granted (unlike the
 * other five Google tools, which are offered unconditionally the moment ANY Google connection
 * exists - see that method's javadoc for why upload is the one exception).
 *
 * <p><b>Defaults to a private upload, always</b> - not a suggestion, a one-way ratchet enforced in
 * {@link #call}: this tool can only ever request {@code "private"} or {@code "unlisted"}; it will
 * never accept {@code "public"} from the model, regardless of what the input schema's enum
 * (rendered by other providers, or a future refactor, might otherwise suggest is available).
 * Publishing a video to the world is a decision for a human to make in YouTube Studio after
 * reviewing what got uploaded, not something this tool call - itself already gated by the
 * platform's normal human-approval mechanism (the same {@code guardAndLog}/{@code
 * GuardedToolCallback} chokepoint every consequential tool goes through) - should also be trusted
 * to decide unattended. Two independent gates, not one: the approval gate says "should this run at
 * all," this ratchet says "and if it does, it still can't go instantly public."
 */
public class UploadVideoToYouTubeToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(UploadVideoToYouTubeToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final List<String> ALLOWED_PRIVACY_STATUSES = List.of("private", "unlisted");
    private static final String DEFAULT_PRIVACY_STATUS = "private";

    private final GeneratedVideoReader videoReader;
    private final YouTubeUploadClient uploadClient;

    public UploadVideoToYouTubeToolCallback(
            GeneratedVideoReader videoReader, YouTubeUploadClient uploadClient) {
        this.videoReader = videoReader;
        this.uploadClient = uploadClient;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("upload_video_to_youtube")
                .description(
                        "Uploads a video previously created with generate_video to the tenant's connected YouTube "
                                + "channel, as a PRIVATE (or unlisted) upload - never public. Tell the user it uploaded "
                                + "privately and that they need to review it and change visibility themselves in YouTube "
                                + "Studio before it's public. Requires the video's job id from a completed generate_video "
                                + "call. Use only when the user explicitly asks to upload/publish/post a generated video "
                                + "to YouTube.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "video_job_id": {"type": "string", "description": "The jobId returned by a prior generate_video call"},
                        "title": {"type": "string", "description": "Video title, required, YouTube max 100 characters"},
                        "description": {"type": "string", "description": "Optional video description"},
                        "tags": {"type": "array", "items": {"type": "string"}, "description": "Optional search tags"},
                        "unlisted": {"type": "boolean", "description": "true for unlisted instead of private. Defaults to false (private). Never public."}
                    },
                    "required": ["video_job_id", "title"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String jobIdRaw = asString(args.get("video_job_id"));
            String title = asString(args.get("title"));
            if (jobIdRaw == null || jobIdRaw.isBlank()) {
                return errorJson("video_job_id is required");
            }
            if (title == null || title.isBlank()) {
                return errorJson("title is required");
            }
            UUID jobId;
            try {
                jobId = UUID.fromString(jobIdRaw.trim());
            } catch (IllegalArgumentException e) {
                return errorJson("video_job_id is not a valid job id");
            }
            String description = asString(args.get("description"));
            List<String> tags = asStringList(args.get("tags"));
            // The ratchet described in this class's javadoc: only ever "private"
            // or "unlisted" leave this method, regardless of what's in args.
            boolean unlisted = Boolean.TRUE.equals(args.get("unlisted"));
            String privacyStatus = unlisted ? "unlisted" : DEFAULT_PRIVACY_STATUS;
            if (!ALLOWED_PRIVACY_STATUSES.contains(privacyStatus)) {
                privacyStatus = DEFAULT_PRIVACY_STATUS;
            }

            GeneratedVideoReader.VideoFile result = videoReader.open(jobId);
            YouTubeUploadClient.UploadedVideo uploaded;
            try {
                uploaded =
                        uploadClient.upload(
                                title,
                                description,
                                tags,
                                privacyStatus,
                                null,
                                result.stream(),
                                result.contentType() == null ? "video/mp4" : result.contentType(),
                                result.contentLength());
            } finally {
                closeQuietly(result.stream());
            }

            return MAPPER.writeValueAsString(
                    Map.of(
                            "videoId", uploaded.videoId(),
                            "watchUrl", uploaded.watchUrl(),
                            "privacyStatus", privacyStatus));
        } catch (Exception e) {
            log.warn("upload_video_to_youtube tool call failed", e);
            return errorJson(consumerReason(e));
        }
    }

    private static String consumerReason(Exception e) {
        if (e instanceof GeneratedVideoReader.NotReadyException) {
            return "That video isn't ready to upload yet "
                    + safeMessage(e)
                    + ". Tell the user to wait for generation to finish and try again.";
        }
        if (e instanceof GoogleApiException) {
            String message = safeMessage(e);
            if (message.toLowerCase(Locale.ROOT).contains("403")
                    || message.toLowerCase(Locale.ROOT).contains("insufficient")) {
                return "YouTube publishing isn't switched on for this workspace yet (needs the YouTube upload "
                        + "permission granted on the Integrations page, separate from the regular Google connection). "
                        + "Tell the user exactly that, and do not retry the tool.";
            }
            return "The upload to YouTube failed"
                    + message
                    + ". Tell the user it could not be uploaded and "
                    + "that they can try again in a moment; answer the rest of their message normally.";
        }
        return "The video could not be uploaded to YouTube just now. Tell the user that and that they can try "
                + "again in a moment; answer the rest of their message normally.";
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? "" : " (" + e.getMessage() + ")";
    }

    private static void closeQuietly(java.io.InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Upload already succeeded or failed by the time this runs - a close failure changes
            // nothing.
        }
    }

    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
