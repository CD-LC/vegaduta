package com.agenticai.core.agents.tools.messaging;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code channels_overview} - the agent's own answer to "what channels do we have connected, and
 * how busy are they". Returns the same aggregate the console's Channels screen renders ({@link
 * ChannelControlPort#overview()}), so an agent can reason about the tenant's real channel surface -
 * which channels are live, how many paired conversations each carries, when the last message
 * arrived, and which agent inbound routes to - instead of guessing or claiming a channel exists
 * when it was never connected.
 */
public class ChannelsOverviewToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChannelsOverviewToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ChannelControlPort controlPort;

    public ChannelsOverviewToolCallback(ChannelControlPort controlPort) {
        this.controlPort = controlPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channels_overview")
                .description(
                        "Lists every messaging channel on this tenant and its live state: connected or not, "
                                + "which provider/account is behind it, how many paired conversations it has, when the "
                                + "last message arrived, and which agent inbound messages route to. Use this before "
                                + "channel_send_message to check the channel is actually connected, or to answer "
                                + "questions like 'which channels are we on' or 'where do Telegram messages go'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {}
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            List<ChannelControlPort.ChannelOverviewEntry> channels = controlPort.overview();
            return MAPPER.writeValueAsString(
                    Map.of(
                            "channels",
                            channels,
                            "connected",
                            channels.stream().filter(c -> "CONNECTED".equals(c.state())).count()));
        } catch (Exception e) {
            log.warn("channels_overview tool call failed", e);
            return errorJson("Could not read channel overview: " + e.getMessage());
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
