package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.GoogleGmailClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link GoogleGmailClient} as a native tool - see {@code GoogleDriveToolCallback}'s
 * javadoc for the action-parameter/one-tool-per-service reasoning shared by all four Google tools.
 */
public class GoogleGmailToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GoogleGmailToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final GoogleGmailClient client;

    public GoogleGmailToolCallback(GoogleGmailClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("google_gmail")
                .description(
                        "Reads and sends email through the connected Gmail account. Actions: 'list' (optional gmail-syntax "
                                + "query, e.g. 'is:unread' or 'from:someone@example.com'), 'read' (requires message_id, returns "
                                + "the plain-text body), 'send' (requires to, subject, body). Reading requires the account to "
                                + "have granted read access; sending requires send access - either may be unavailable "
                                + "depending on what the tenant approved when connecting.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["list", "read", "send"]},
                        "query": {"type": "string", "description": "Optional Gmail search query for 'list'"},
                        "message_id": {"type": "string", "description": "Required for 'read'"},
                        "to": {"type": "string", "description": "Required for 'send'"},
                        "subject": {"type": "string", "description": "Required for 'send'"},
                        "body": {"type": "string", "description": "Required for 'send'"}
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
                case "list" -> {
                    List<GoogleGmailClient.MessageSummary> results =
                            client.listRecent(asString(args.get("query")), 10);
                    yield MAPPER.writeValueAsString(results);
                }
                case "read" -> {
                    String messageId = asString(args.get("message_id"));
                    if (messageId == null)
                        yield "{\"error\": \"message_id is required for action=read\"}";
                    yield client.readMessage(messageId);
                }
                case "send" -> {
                    String to = asString(args.get("to"));
                    String subject = asString(args.get("subject"));
                    String body = asString(args.get("body"));
                    if (to == null || subject == null || body == null) {
                        yield "{\"error\": \"to, subject, and body are all required for action=send\"}";
                    }
                    yield client.sendMessage(to, subject, body);
                }
                default -> "{\"error\": \"Unknown action - use list, read, or send\"}";
            };
        } catch (Exception e) {
            log.warn("google_gmail tool call failed", e);
            return errorJson("Gmail call failed: " + e.getMessage());
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

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
