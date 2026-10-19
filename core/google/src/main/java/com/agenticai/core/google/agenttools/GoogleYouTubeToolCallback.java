package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.YouTubeAnalyticsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link YouTubeAnalyticsClient} as a native tool - Master-Roadmap- 2026.md item #8
 * ("Content feedback loop"), Phase 1. Same one-tool-with-an- action shape as {@link
 * GoogleCalendarToolCallback} to keep the per-agent tool count down. This is the tool that lets an
 * agent actually close the "feedback" loop: a creator can ask "how did my last video do?" or "what
 * should I make next based on what's been working?" and the agent has real numbers to reason over
 * instead of guessing.
 */
public class GoogleYouTubeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GoogleYouTubeToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final YouTubeAnalyticsClient client;

    public GoogleYouTubeToolCallback(YouTubeAnalyticsClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("youtube_content_performance")
                .description(
                        "Reads public performance stats for the connected YouTube channel. Actions: "
                                + "'channel_summary' (lifetime subscriber/view/video counts), 'recent_videos' "
                                + "(optional max_results, default 10, max 25 - title, publish date, view/like/comment "
                                + "counts for the most recent uploads, in upload-recency order). Does not include "
                                + "watch-time, traffic sources, or audience retention - view/like/comment counts only.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["channel_summary", "recent_videos"]},
                        "max_results": {"type": "integer", "description": "Optional for 'recent_videos', default 10, max 25"}
                    },
                    "required": ["action"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String action = String.valueOf(args.get("action"));
            return switch (action) {
                case "channel_summary" -> MAPPER.writeValueAsString(client.channelSummary());
                case "recent_videos" -> {
                    int maxResults =
                            args.get("max_results") instanceof Number n ? n.intValue() : 10;
                    yield MAPPER.writeValueAsString(client.recentVideoPerformance(maxResults));
                }
                default -> "{\"error\": \"Unknown action - use channel_summary or recent_videos\"}";
            };
        } catch (Exception e) {
            log.warn("youtube_content_performance tool call failed", e);
            return errorJson("YouTube content performance call failed: " + e.getMessage());
        }
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
}
