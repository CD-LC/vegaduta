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
 * Cross-session conversation search (P1 developer-extensibility surface) - lets an agent answer
 * "what did this customer ask us before", "has anyone mentioned X on any channel", or "find the
 * conversation where we promised a refund". Reads the persisted {@code channel_message_log}
 * transcript via {@link ChannelMessageQueryPort} (see that interface's javadoc for the cross-module
 * inversion), NOT the Redis session list - session memory is TTL-bounded working memory, while this
 * log is the durable record.
 *
 * <p>Scope: tenant schema isolation means an agent only ever searches its own tenant's messages -
 * there is no cross-tenant read to guard against. Both directions are searchable (inbound customer
 * text and the outbound operator/agent replies logged since V214); each hit carries {@code
 * direction} and {@code author} so the model can tell who said it. Results are capped at 50 rows of
 * at most 2000 chars each (the column's own bound), so the worst-case tool result stays under ~100
 * KB - large but bounded, same posture as {@code telegram_read_messages}.
 */
public class SearchConversationHistoryToolCallback implements ToolCallback {

    public static final String NAME = "search_conversation_history";

    private static final Logger log =
            LoggerFactory.getLogger(SearchConversationHistoryToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final ChannelMessageQueryPort messageQueryPort;

    public SearchConversationHistoryToolCallback(ChannelMessageQueryPort messageQueryPort) {
        this.messageQueryPort = messageQueryPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Searches this tenant's persisted channel conversation history - inbound customer messages and "
                                + "outbound replies across WhatsApp, Telegram, Slack, Discord, Email, Teams, X and Meta, in ANY "
                                + "past session, not just this one. Use it for questions like 'what did this customer ask before', "
                                + "'have we ever discussed X', or 'find past messages mentioning Y'. Matches a literal substring "
                                + "of the message text (case-insensitive). Each hit includes the channel, sender, direction, "
                                + "author, message text and timestamp, newest first.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "Literal text to search for in message bodies, e.g. 'refund' or 'invoice #42'."},
                        "channel": {"type": "string", "description": "Optional channel filter: whatsapp, telegram, slack, discord, email, teams, x, messenger, instagram. Omit to search all channels."},
                        "sender": {"type": "string", "description": "Optional: restrict to one sender's external id (phone number, chat id, email address) - use it to search one customer's history."},
                        "limit": {"type": "integer", "description": "Max messages to return, newest first (default 20, max 50)"}
                    },
                    "required": ["query"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args =
                    toolInput == null || toolInput.isBlank()
                            ? Map.of()
                            : MAPPER.readValue(toolInput, Map.class);
            String query =
                    args.get("query") == null ? "" : String.valueOf(args.get("query")).trim();
            if (query.isEmpty()) {
                return "{\"error\": \"query is required - the literal text to search for in past messages.\"}";
            }
            String channel =
                    args.get("channel") == null ? null : String.valueOf(args.get("channel")).trim();
            String sender =
                    args.get("sender") == null ? null : String.valueOf(args.get("sender")).trim();
            int limit = DEFAULT_LIMIT;
            if (args.get("limit") != null) {
                limit =
                        Math.max(
                                1,
                                Math.min(
                                        MAX_LIMIT,
                                        Integer.parseInt(String.valueOf(args.get("limit")))));
            }
            List<ChannelMessageQueryPort.ConversationSearchHit> hits =
                    messageQueryPort.searchMessages(channel, sender, query, limit);
            if (hits.isEmpty()) {
                return "{\"messages\": [], \"note\": \"No past channel messages match that search.\"}";
            }
            return MAPPER.writeValueAsString(Map.of("messages", hits, "count", hits.size()));
        } catch (Exception e) {
            log.warn("search_conversation_history tool call failed", e);
            return errorJson("Could not search conversation history: " + e.getMessage());
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
