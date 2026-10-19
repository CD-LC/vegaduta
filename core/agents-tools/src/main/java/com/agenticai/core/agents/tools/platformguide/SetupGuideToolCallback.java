package com.agenticai.core.agents.tools.platformguide;

import com.agenticai.core.agentspi.SetupStatusProvider.Status;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code vegaduta_setup_guide}: how the Assistant turns "I want X" or "why can't you do X" into the
 * exact console page that sets X up. Read-only. It matches the request to a setup topic, reports the
 * install's live state for that topic where a module can tell (connected, failing, not set up), adds
 * the matching passages of the bundled documentation and, for MCP, the catalogue servers that fit the
 * request. The model answers with a link, the steps and "try again once it is on" - it never configures
 * anything itself, so nothing here can change the install.
 */
public class SetupGuideToolCallback implements ToolCallback {

    public static final String NAME = "vegaduta_setup_guide";

    private static final Logger log = LoggerFactory.getLogger(SetupGuideToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_TOPICS = 3;
    private static final int MAX_DOCS = 4;

    /** Catalogue servers (or similar) that fit the words of a request, as ready-to-return JSON. */
    @FunctionalInterface
    public interface McpAdvisor {
        List<ObjectNode> candidates(String query);
    }

    private final PlatformGuide guide;
    private final Supplier<Map<String, Status>> statuses;
    private final McpAdvisor mcp;

    public SetupGuideToolCallback(PlatformGuide guide, Supplier<Map<String, Status>> statuses, McpAdvisor mcp) {
        this.guide = guide;
        this.statuses = statuses;
        this.mcp = mcp;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Finds how to set something up on this VegaDuta install and where. Call it BEFORE answering when the "
                                + "user asks how to connect, configure, enable, install or integrate anything (MCP servers, "
                                + "channels, Google, model providers, knowledge, API keys, SSO, tools, workflows...), asks a "
                                + "how-do-I question about the platform, or when you lack a tool the request needs. It returns "
                                + "the console page link, the steps, this install's live status for that topic, what else must be "
                                + "on, and passages from the platform docs. For REST endpoints use vegaduta_api_guide instead. "
                                + "action=help (default) matches setup topics and docs to the query; action=docs searches only "
                                + "the docs; action=topic returns one topic by id.")
                .inputSchema(
                        """
                        {
                            "type": "object",
                            "properties": {
                                "action": {"type": "string", "enum": ["help", "docs", "topic"], "description": "help (default), docs or topic"},
                                "query": {"type": "string", "description": "The user's request in a few words, e.g. 'connect github mcp' or 'why is slack not answering'"},
                                "id": {"type": "string", "description": "For action=topic: a topic id from an earlier result"}
                            }
                        }
                        """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String action = args.path("action").asText("help");
            String query = args.path("query").asText("");
            ObjectNode out = MAPPER.createObjectNode();
            switch (action.toLowerCase()) {
                case "docs" -> docsInto(out, query);
                case "topic" -> {
                    String id = args.path("id").asText("");
                    var topic = guide.topic(id);
                    if (topic.isEmpty()) {
                        return error("No topic '" + id + "'. Use action=help with a query to find the id.");
                    }
                    ArrayNode arr = out.putArray("topics");
                    arr.add(topicNode(topic.get(), statuses.get(), query, true));
                    out.put("how_to_answer", HOW_TO_ANSWER);
                }
                default -> helpInto(out, query);
            }
            return MAPPER.writeValueAsString(out);
        } catch (IOException | RuntimeException e) {
            log.warn("{} failed", NAME, e);
            return error("The platform guide could not be read: " + e.getClass().getSimpleName());
        }
    }

    private static final String HOW_TO_ANSWER =
            "Reply in plain words: say what is needed, give each console page as a markdown link using its 'link' "
                    + "(for example [Tools](/tools)), list the steps, and say what is already set up from 'status' "
                    + "(unknown means you cannot tell: ask the user to check the page). If a thing is not set up or is "
                    + "failing, ask the user to do it and then send the same request again; do not pretend it worked and do "
                    + "not guess a result. Mention 'needs' when a permission may be missing. Only describe what these results say.";

    private void helpInto(ObjectNode out, String query) {
        Map<String, Status> live = statuses.get();
        ArrayNode topics = out.putArray("topics");
        List<PlatformGuide.TopicHit> hits = guide.rankTopics(query, MAX_TOPICS);
        for (PlatformGuide.TopicHit hit : hits) {
            topics.add(topicNode(hit.topic(), live, query, hits.indexOf(hit) == 0));
        }
        boolean mcpMatched = hits.stream().anyMatch(h -> h.topic().id().startsWith("mcp"));
        if (mcpMatched || query.toLowerCase().contains("mcp")) {
            List<ObjectNode> candidates = mcp.candidates(query);
            if (!candidates.isEmpty()) {
                ArrayNode arr = out.putArray("mcp_servers_that_fit");
                candidates.forEach(arr::add);
            }
        }
        docsInto(out, query);
        if (hits.isEmpty()) {
            out.put("note", "No setup topic matched. Answer from the docs below if they fit; otherwise say you could not find it and "
                    + "point the user to the Help page (/help/docs). For an API question use vegaduta_api_guide.");
        }
        out.put("how_to_answer", HOW_TO_ANSWER);
    }

    private void docsInto(ObjectNode out, String query) {
        ArrayNode docs = out.putArray("docs");
        for (PlatformGuide.DocHit d : guide.searchDocs(query, MAX_DOCS)) {
            docs.addObject().put("doc", d.doc()).put("section", d.heading()).put("text", d.excerpt());
        }
    }

    private ObjectNode topicNode(SetupTopic t, Map<String, Status> live, String query, boolean full) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", t.id()).put("title", t.title()).put("summary", t.summary()).put("link", t.path());
        Status s = live.get(t.id());
        ObjectNode st = n.putObject("status");
        if (s == null) {
            st.put("state", "UNKNOWN").put("detail", "This install cannot tell from here; ask the user to open the page.");
        } else {
            st.put("state", s.state().name()).put("detail", s.detail());
        }
        ArrayNode steps = n.putArray("steps");
        t.steps().forEach(steps::add);
        if (t.needs() != null) {
            n.put("needs", t.needs());
        }
        if (full) {
            ArrayNode also = n.putArray("also_check");
            for (String id : t.also()) {
                guide.topic(id).ifPresent(o -> {
                    ObjectNode a = also.addObject();
                    a.put("id", o.id()).put("title", o.title()).put("link", o.path());
                    Status os = live.get(o.id());
                    a.put("state", os == null ? "UNKNOWN" : os.state().name());
                });
            }
            if (!t.apiHints().isEmpty()) {
                ArrayNode api = n.putArray("rest_api_lookup");
                t.apiHints().forEach(api::add);
            }
        }
        return n;
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
        } catch (IOException e) {
            return "{\"error\":\"error\"}";
        }
    }
}
