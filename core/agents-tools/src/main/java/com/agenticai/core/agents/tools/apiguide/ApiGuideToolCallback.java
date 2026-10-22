package com.agenticai.core.agents.tools.apiguide;

import com.agenticai.core.agentspi.ApiSpecProvider;
import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code vegaduta_api_guide}: the Assistant's way into the platform's REST contract. Read-only - it
 * looks things up in the generated OpenAPI document and never calls the API itself. To show a live
 * result the model embeds an {@code apiCall} node in a {@code json_ui} block; the console runs it
 * with the signed-in user's own session (GET straight away, anything else only after the user
 * presses Send), so the call carries the user's permissions and no credential passes through the
 * model.
 */
public class ApiGuideToolCallback implements ToolCallback {

    public static final String NAME = "vegaduta_api_guide";

    private static final Logger log = LoggerFactory.getLogger(ApiGuideToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ApiSpecProvider spec;
    private volatile ApiGuideIndex index;

    public ApiGuideToolCallback(ApiSpecProvider spec) {
        this.spec = spec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Looks up this VegaDuta install's REST API in its real OpenAPI spec. Use it whenever the user asks "
                                + "how to do something with the API, which endpoint to call, or wants data the API can return. "
                                + "action=search finds endpoints by words; action=describe returns the parameters, request "
                                + "body and response of one endpoint and a ready json_ui apiCall block that shows the live "
                                + "result to the user (GET runs by itself, POST/PUT/PATCH/DELETE wait for the user to press "
                                + "Send). Prefer showing the live result over describing it. The full spec is at /api/openapi.json.")
                .inputSchema(
                        """
                        {
                            "type": "object",
                            "properties": {
                                "action": {"type": "string", "enum": ["search", "describe"], "description": "search (default) or describe"},
                                "query": {"type": "string", "description": "For search: words about the task, e.g. 'list agents' or 'create knowledge base'"},
                                "method": {"type": "string", "description": "For describe: GET, POST, PUT, PATCH or DELETE. For search: optional filter"},
                                "path": {"type": "string", "description": "For describe: the exact path from a search result, e.g. /api/agents/{id}"}
                            },
                            "required": ["action"]
                        }
                        """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            ApiGuideIndex idx = index();
            String action = args.path("action").asString("search");
            if ("describe".equalsIgnoreCase(action)) {
                String method = args.path("method").asString("");
                String path = args.path("path").asString("");
                Optional<ObjectNode> found = idx.describe(method, path);
                return found.isPresent()
                        ? MAPPER.writeValueAsString(found.get())
                        : error(
                                "No "
                                        + method
                                        + " "
                                        + path
                                        + " in the spec. Use action=search to find the exact path.");
            }
            return MAPPER.writeValueAsString(
                    idx.search(args.path("query").asString(""), args.path("method").asString("")));
        } catch (IOException | RuntimeException e) {
            log.warn("{} failed", NAME, e);
            return error("The API spec could not be read: " + e.getClass().getSimpleName());
        }
    }

    private ApiGuideIndex index() throws IOException {
        ApiGuideIndex cached = index;
        if (cached == null) {
            synchronized (this) {
                cached = index;
                if (cached == null) {
                    cached = new ApiGuideIndex(MAPPER, spec.specJson());
                    index = cached;
                }
            }
        }
        return cached;
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
        } catch (IOException e) {
            return "{\"error\":\"error\"}";
        }
    }
}
