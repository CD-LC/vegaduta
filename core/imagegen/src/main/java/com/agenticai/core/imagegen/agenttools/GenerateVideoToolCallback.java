package com.agenticai.core.imagegen.agenttools;

import com.agenticai.core.imagegen.VideoGenerationJob;
import com.agenticai.core.imagegen.VideoGenerationJobService;
import com.agenticai.core.imagegen.VideoGenerationJobStatus;
import com.agenticai.core.imagegen.VideoGenerationQuotaExceededException;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link VideoGenerationJobService} as a native Spring AI tool - the video counterpart of
 * {@code com.agenticai.core.imagegen.agenttools.GenerateImageToolCallback}. Unlike image
 * generation, Veo-class video generation takes tens of seconds to minutes (see {@code
 * VideoGenerationJob}'s javadoc), far past what a single chat turn can block on - so this tool only
 * submits the job and hands back its id/status, the same enqueue-then-poll shape {@code
 * VideoGenerationController} exposes over REST. The already-wired {@code VideoGenerationJobRunner}
 * advances the job to completion in the background; the model is told to relay the poll/result URLs
 * to the user rather than wait on them.
 *
 * <p>{@link VideoGenerationJobService#enqueue} does not throw for a "not configured" outcome the
 * way {@code OpenAiImageGenerationClient#generate} does - it catches that internally and returns a
 * job already marked {@link VideoGenerationJobStatus#FAILED} (see that method's javadoc), so this
 * class checks the returned job's status rather than relying only on a catch block.
 */
public class GenerateVideoToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GenerateVideoToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final int DEFAULT_DURATION_SECONDS = 5;

    private final VideoGenerationJobService jobService;

    public GenerateVideoToolCallback(VideoGenerationJobService jobService) {
        this.jobService = jobService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("generate_video")
                .description(
                        "Starts generating a short video from a text description. Video generation takes tens of "
                                + "seconds to a few minutes and runs in the background - this tool does not wait for it to "
                                + "finish. It returns a job id and URLs; tell the user their video is being generated and "
                                + "share the videoUrl, which starts serving the finished video once ready (returns 404 "
                                + "until then - the user's client is expected to retry it). Use it when the user asks to "
                                + "create, make, generate, or produce a video or short clip from a description.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "description": "A detailed description of the video to generate"},
                        "durationSeconds": {"type": "integer", "description": "Video length in seconds, 1-60. Defaults to 5."}
                    },
                    "required": ["prompt"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String prompt = asString(args.get("prompt"));
            if (prompt == null || prompt.isBlank()) {
                return errorJson("prompt is required");
            }
            int durationSeconds = asDurationSeconds(args.get("durationSeconds"));

            VideoGenerationJob job = jobService.enqueue(prompt, durationSeconds);
            if (job.getStatus() == VideoGenerationJobStatus.FAILED) {
                return errorJson(notConfiguredOrFailedReason(job.getErrorMessage()));
            }
            return MAPPER.writeValueAsString(
                    Map.of(
                            "jobId",
                            job.getId().toString(),
                            "status",
                            job.getStatus().name(),
                            "jobUrl",
                            "/api/videogen/jobs/" + job.getId(),
                            "videoUrl",
                            "/api/videogen/videos/" + job.getId()));
        } catch (Exception e) {
            log.warn("generate_video tool call failed", e);
            return errorJson(consumerReason(e));
        }
    }

    /**
     * Written at the model, not the user - same reasoning as {@code
     * GenerateImageToolCallback#consumerReason}: the return value is a tool result, only ever seen
     * through the model's own rendering of it in whatever language the person is speaking.
     */
    private static String consumerReason(Exception e) {
        if (e instanceof VideoGenerationQuotaExceededException quota) {
            // Already written for a person by VideoGenerationQuotaService - pass it through.
            return quota.getMessage() + " Tell the user this and do not retry the tool.";
        }
        return "Video generation could not be started just now. Tell the user the video could not be started "
                + "and that they can try again in a moment; answer the rest of their message normally.";
    }

    /**
     * Distinguishes the "no Vertex credential/pool configured" job-failure path from a genuine
     * mid-call provider failure.
     */
    private static String notConfiguredOrFailedReason(String errorMessage) {
        if (errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("credential")) {
            return "Video generation is not switched on for this workspace. It needs a Google Vertex AI "
                    + "credential to be added and activated on the Providers page of the console. Tell the user "
                    + "exactly that, and do not retry the tool.";
        }
        String detail =
                (errorMessage == null || errorMessage.isBlank()) ? "" : " (" + errorMessage + ")";
        return "Video generation could not be started just now"
                + detail
                + ". Tell the user it could not be "
                + "started and that they can try again in a moment; answer the rest of their message normally.";
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
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

    private static int asDurationSeconds(Object value) {
        if (value == null) {
            return DEFAULT_DURATION_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(value.toString());
            return Math.min(60, Math.max(1, seconds));
        } catch (NumberFormatException e) {
            return DEFAULT_DURATION_SECONDS;
        }
    }
}
