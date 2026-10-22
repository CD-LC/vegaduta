package com.agenticai.core.agents.tools.platformguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agentspi.SetupStatusProvider.State;
import com.agenticai.core.agentspi.SetupStatusProvider.Status;
import com.agenticai.core.json.JsonMappers;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SetupGuideToolCallbackTest {

    private static PlatformGuide guide() throws Exception {
        return PlatformGuide.load(JsonMappers.plain());
    }

    private static JsonNode call(SetupGuideToolCallback tool, String json) throws Exception {
        return JsonMappers.plain().readTree(tool.call(json));
    }

    private static SetupGuideToolCallback tool(Map<String, Status> live) throws Exception {
        return new SetupGuideToolCallback(
                guide(),
                () -> live,
                q -> {
                    ObjectNode n = JsonMappers.plain().createObjectNode();
                    n.put("name", "github").put("state", "NOT_REGISTERED");
                    return List.of(n);
                });
    }

    @Test
    void mcpRequestPointsAtToolsPageWithLiveStatusAndCatalogueMatch() throws Exception {
        JsonNode r =
                call(
                        tool(
                                Map.of(
                                        "mcp-servers",
                                        new Status(
                                                State.NOT_SET_UP,
                                                "No MCP server is registered yet."))),
                        "{\"query\":\"I want my agent to read GitHub issues through an MCP server\"}");
        assertEquals("mcp-servers", r.at("/topics/0/id").asString());
        assertEquals("/tools", r.at("/topics/0/link").asString());
        assertEquals("NOT_SET_UP", r.at("/topics/0/status/state").asString());
        assertEquals("github", r.at("/mcp_servers_that_fit/0/name").asString());
        // The enabling step is the part people miss: it must be reachable from the first topic.
        assertEquals("agent-tools", r.at("/topics/0/also_check/0/id").asString());
        assertTrue(r.path("how_to_answer").asString().contains("send the same request again"));
    }

    @Test
    void unknownStatusIsSaidNotInvented() throws Exception {
        JsonNode r = call(tool(Map.of()), "{\"query\":\"connect slack\"}");
        assertEquals("slack", r.at("/topics/0/id").asString());
        assertEquals("UNKNOWN", r.at("/topics/0/status/state").asString());
    }

    @Test
    void phrasingWithoutTheToolNameStillFindsTheTopic() throws Exception {
        assertEquals(
                "agent-tools",
                call(
                                tool(Map.of()),
                                "{\"query\":\"my agent says it has no tool for that, how do I allow tool access\"}")
                        .at("/topics/0/id")
                        .asString());
        assertEquals(
                "llm-providers",
                call(
                                tool(Map.of()),
                                "{\"query\":\"add my OpenAI api key so agents can use a model\"}")
                        .at("/topics/0/id")
                        .asString());
        assertEquals(
                "google",
                call(tool(Map.of()), "{\"query\":\"book a meeting on my calendar\"}")
                        .at("/topics/0/id")
                        .asString());
    }

    @Test
    void docsActionReturnsPassagesFromTheBundledDocs() throws Exception {
        JsonNode r =
                call(tool(Map.of()), "{\"action\":\"docs\",\"query\":\"tool pinning enforce\"}");
        assertFalse(r.path("docs").isEmpty());
        assertTrue(r.at("/docs/0/text").asString().toLowerCase().contains("pin"));
    }

    @Test
    void nonsenseMatchesNothingAndSaysSo() throws Exception {
        JsonNode r = call(tool(Map.of()), "{\"query\":\"zzqx plorbt\"}");
        assertTrue(r.path("topics").isEmpty());
        assertTrue(r.has("note"));
    }

    @Test
    void topicActionRejectsAnUnknownId() throws Exception {
        assertTrue(call(tool(Map.of()), "{\"action\":\"topic\",\"id\":\"nope\"}").has("error"));
    }

    @Test
    void everyTopicIsWellFormed() throws Exception {
        PlatformGuide g = guide();
        for (SetupTopic t : g.topics()) {
            assertFalse(t.steps().isEmpty(), t.id() + " has no steps");
            assertTrue(t.path().startsWith("/"), t.id() + " path must be a console route");
            for (String also : t.also()) {
                assertTrue(g.topic(also).isPresent(), t.id() + " names unknown topic " + also);
            }
            for (String doc : t.docs()) {
                assertTrue(
                        PlatformGuideTestSupport.bundled(doc),
                        t.id() + " links a doc that is not bundled: " + doc);
            }
        }
    }

    @Test
    void topicRoutesExistInTheWebApp() throws Exception {
        Path web = Path.of("..", "..", "web", "app");
        if (!Files.isDirectory(web)) {
            return; // built outside the repository checkout (container build): nothing to compare
            // with
        }
        for (SetupTopic t : guide().topics()) {
            String route = t.path().split("\\?")[0].replaceAll("^/", "");
            assertTrue(
                    new File(web.toFile(), route + "/page.tsx").exists(),
                    t.id() + " points at a console page that does not exist: " + t.path());
        }
    }
}
