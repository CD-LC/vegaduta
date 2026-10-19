package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.GoogleDriveClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link GoogleDriveClient} as a native Spring AI tool - same "plain in-process
 * ToolCallback, not an MCP server" choice {@code KnowledgeSearchToolCallback} makes, for the
 * identical reason (an in-process Java service, not an external subprocess/HTTP tool server). One
 * tool with an {@code action} parameter rather than three separate tools (list/read/create), to
 * keep the total per-agent tool count down - {@code AgentToolProvider} only offers this at all when
 * {@code GoogleOAuthService#status} shows an active connection for the tenant, so there's no
 * separate per-agent enable flag to configure either.
 */
public class GoogleDriveToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final GoogleDriveClient client;

    public GoogleDriveToolCallback(GoogleDriveClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("google_drive")
                .description(
                        "Reads and creates files in the connected Google Drive account. Only sees files this app created "
                                + "or that the user explicitly opened with it (drive.file scope), not the user's whole Drive. "
                                + "Actions: 'list' (optional name_contains filter), 'read' (requires file_id, works for plain "
                                + "text/binary files, not native Google Docs), 'create' (requires name and content, creates a "
                                + "plain text file).")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["list", "read", "create"]},
                        "name_contains": {"type": "string", "description": "Optional filter for 'list'"},
                        "file_id": {"type": "string", "description": "Required for 'read'"},
                        "name": {"type": "string", "description": "Required for 'create'"},
                        "content": {"type": "string", "description": "Required for 'create'"}
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
                case "list" ->
                        MAPPER.writeValueAsString(
                                client.listFiles(asString(args.get("name_contains")), 10));
                case "read" -> {
                    String fileId = asString(args.get("file_id"));
                    if (fileId == null)
                        yield "{\"error\": \"file_id is required for action=read\"}";
                    yield client.readFile(fileId);
                }
                case "create" -> {
                    String name = asString(args.get("name"));
                    String content = asString(args.get("content"));
                    if (name == null || content == null)
                        yield "{\"error\": \"name and content are required for action=create\"}";
                    yield MAPPER.writeValueAsString(client.createTextFile(name, content));
                }
                default -> "{\"error\": \"Unknown action - use list, read, or create\"}";
            };
        } catch (Exception e) {
            log.warn("google_drive tool call failed", e);
            return errorJson("Google Drive call failed: " + e.getMessage());
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
