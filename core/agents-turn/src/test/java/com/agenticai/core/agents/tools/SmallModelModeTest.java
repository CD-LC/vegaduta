package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.skills.SkillPromptBlock;
import com.agenticai.core.agents.skills.SkillService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** V501 small-model mode: the tool cap by relevance and the compact prompt blocks. */
class SmallModelModeTest {

    private static ToolCallback tool(String name, String description) {
        ToolDefinition definition =
                ToolDefinition.builder().name(name).description(description).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private static SkillService.SkillMetadata skill(String name, String description) {
        return new SkillService.SkillMetadata(
                java.util.UUID.randomUUID(), name, description, null, true, 0, "test", false);
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void aListWithinTheCapIsReturnedAsIs() {
        List<ToolCallback> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(tool("t" + i, "tool " + i));
        }
        assertSame(six, AgentToolSelection.capForSmallModel(six, "anything", 6));
        assertNull(AgentToolSelection.capForSmallModel(null, "x", 6));
    }

    @Test
    void aRequestWithTwoIntentsKeepsAToolForEach() {
        // The Assistant's seven file/shell tools come first in the resolved order and all score the
        // same intent boost for "files ... folder"; before the per-intent round-robin they filled
        // every one of the six slots and web_search, boosted just as much by "search the web", was
        // dropped - the model then told the person it could not search.
        List<ToolCallback> tools =
                List.of(
                        tool("remember_fact", "Remember a fact about the user."),
                        tool("local_shell", "Run a command in the projects folder."),
                        tool("local_shell_read", "Inspect files with read-only commands."),
                        tool("workspace_list", "List files in the projects folder."),
                        tool("workspace_read", "Read a file."),
                        tool("workspace_search", "Search inside files."),
                        tool("workspace_write", "Write a file."),
                        tool("workspace_edit", "Edit a file."),
                        tool("vegaduta_setup_guide", "How to set the platform up."),
                        tool("web_search", "Search the web."),
                        tool("fetch_web_page", "Read a web page."),
                        tool("generate_document", "Generate a PDF or DOCX document."));

        List<String> kept =
                names(
                        AgentToolSelection.capForSmallModel(
                                tools,
                                "Search the web for the latest Ollama release, then list the files in my workspace folder.",
                                6));

        assertEquals(6, kept.size());
        assertTrue(kept.contains("web_search"), kept.toString());
        assertTrue(kept.contains("workspace_list"), kept.toString());
        assertFalse(kept.contains("generate_document"), kept.toString());
    }

    @Test
    void theCapKeepsTheToolsMostRelevantToTheMessage() {
        List<ToolCallback> tools =
                List.of(
                        tool("remember_fact", "Remember a fact about the user."),
                        tool("search_knowledge_base", "Search the workspace knowledge base."),
                        tool("browser_navigate", "Open a web page in the browser."),
                        tool("browser_click", "Click an element on the page."),
                        tool("generate_image", "Generate an image from a prompt."),
                        tool("generate_document", "Generate a PDF or DOCX document from a prompt."),
                        tool("send_email", "Send an email message."),
                        tool("load_skill", "Load a skill's instructions."),
                        tool("run_code", "Run Python code in a sandbox."));

        List<ToolCallback> kept =
                AgentToolSelection.capForSmallModel(
                        tools, "make a one-page PDF document about mandi prices", 6);

        assertEquals(6, kept.size());
        assertEquals("generate_document", names(kept).get(0), "name and description both match");
        assertTrue(names(kept).contains("remember_fact"), "ties keep the resolved order, always-relevant tools come first there");
        assertFalse(names(kept).contains("run_code"), "the last resolved zero-scorers are what gets dropped");
        // Mutable, like the resolver's list: the assembler appends ad-hoc delegation tools later.
        kept.add(tool("extra", "extra"));
    }

    @Test
    void aToolNamedInTheMessageAlwaysWins() {
        List<ToolCallback> tools = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tools.add(tool("tool_" + i, "Does thing number " + i + "."));
        }
        List<ToolCallback> kept = AgentToolSelection.capForSmallModel(tools, "please use tool_8 for this", 6);
        assertEquals("tool_8", names(kept).get(0));
        assertEquals(6, kept.size());
    }

    @Test
    void theCompactSkillsBlockListsNamesOnly() {
        List<SkillService.SkillMetadata> skills =
                List.of(
                        skill("pdf", "Create PDF reports with a long description here."),
                        skill("brainstorm", "Structured brainstorming techniques. Use when..."));
        String compact = SkillPromptBlock.renderCompact(skills);
        String full = SkillPromptBlock.render(skills);
        assertTrue(compact.contains("pdf, brainstorm"));
        assertTrue(compact.contains("load_skill"));
        assertFalse(compact.contains("long description"));
        assertTrue(full.contains("long description"));
        assertTrue(compact.length() < full.length() / 2);
        assertEquals("", SkillPromptBlock.renderCompact(List.of()));
    }

    @Test
    void theChainLinkFlagDefaultsToOff() {
        HostedChainLink legacy = new HostedChainLink(null, "k", "label", "m", 0, 0, 0);
        assertFalse(legacy.smallModel());
        HostedChainLink small = new HostedChainLink(null, "k", "label", "m", 0, 0, 0, null, null, true);
        assertTrue(small.smallModel());
    }

    /** What people actually type shares no word with a tool's name; the intent table finds the tool anyway. */
    @Test
    void anIntentPicksTheToolThatServesItEvenWithoutSharedWords() {
        List<ToolCallback> tools = new ArrayList<>();
        for (String n : List.of("remember_fact", "request_human", "generate_document", "recall_related_facts", "propose_skill", "local_shell_read",
                "workspace_write", "web_search", "fetch_web_page", "google_calendar", "channel_send_message")) {
            tools.add(tool(n, "does something unrelated"));
        }
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "what is the weather in Goa today", 6)).contains("web_search"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "summarise https://example.com/post for me", 6)).contains("fetch_web_page"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "what is on my calendar tomorrow", 6)).contains("google_calendar"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "create a script in my project folder", 6)).contains("workspace_write"));
        tools.add(tool("search_knowledge_base", "does something unrelated"));
        tools.add(tool("schedule_task", "does something unrelated"));
        tools.add(tool("local_shell", "does something unrelated"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "what does my uploaded PDF say about pricing", 6)).contains("search_knowledge_base"));
        // Driving a page is the browser's job, not the page-text fetcher's.
        tools.add(tool("browser_navigate", "does something unrelated"));
        tools.add(tool("browser_click_element", "does something unrelated"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "log in to the site and click the submit button", 6)).contains("browser_navigate"));
        // Driving a page needs a set of browser tools, not one: read and click come along with navigate.
        tools.add(tool("browser_read_page_text", "does something unrelated"));
        tools.add(tool("browser_snapshot", "does something unrelated"));
        List<String> browsing = names(AgentToolSelection.capForSmallModel(tools, "in your browser read the page then click the link and tell me the url", 6));
        assertTrue(browsing.stream().filter(n -> n.startsWith("browser_")).count() >= 3, "browser tools kept: " + browsing);
        // Routines and machine questions: neither shares a word with the tool that does it.
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "remind me every weekday at 9 to stand up", 6)).contains("schedule_task"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "how much disk space is left", 6)).contains("local_shell"));
        // The first fragment of an intent is the tool that does the job, so it ranks above a helper.
        List<String> web = names(AgentToolSelection.capForSmallModel(tools, "latest news", 6));
        assertTrue(web.indexOf("web_search") < web.indexOf("fetch_web_page") || !web.contains("fetch_web_page"));
        // A tool named in the message still beats every intent.
        assertEquals("request_human", names(AgentToolSelection.capForSmallModel(tools, "search the web, or use request_human", 6)).get(0));
    }

    /** "Play a game on my computer" shares no word with local_desktop_click - the desktop intent must
     * surface the see-decide-act family as a set, or the model answers "I have no desktop tools". */
    @Test
    void aDesktopRequestKeepsTheDesktopFamilyAsASet() {
        List<ToolCallback> tools = new ArrayList<>();
        for (String n : List.of("remember_fact", "request_human", "generate_document", "local_shell", "workspace_write",
                "web_search", "search_knowledge_base", "schedule_task")) {
            tools.add(tool(n, "does something unrelated"));
        }
        tools.add(tool("local_desktop_screen", "does something unrelated"));
        tools.add(tool("local_desktop_elements", "does something unrelated"));
        tools.add(tool("local_desktop_press", "does something unrelated"));
        tools.add(tool("local_desktop_click", "does something unrelated"));
        tools.add(tool("local_desktop_type", "does something unrelated"));
        tools.add(tool("run_desktop_recipe", "does something unrelated"));

        List<String> game = names(AgentToolSelection.capForSmallModel(tools, "play minesweeper on my computer", 6));
        long desktopKept = game.stream().filter(n -> n.startsWith("local_desktop_")).count();
        assertTrue(desktopKept >= 3, "desktop family kept as a set: " + game);

        List<String> recipe = names(AgentToolSelection.capForSmallModel(tools, "run_desktop_recipe open-notes", 6));
        assertEquals("run_desktop_recipe", recipe.get(0), "named tool wins: " + recipe);
    }

    @Test
    void setupAndApiQuestionsKeepTheGuideToolsEvenWhenAFileWordIsPresent() {
        List<ToolCallback> tools = new ArrayList<>();
        for (String n : List.of("remember_fact", "request_human", "generate_document", "recall_related_facts", "local_shell_read", "local_shell",
                "workspace_write", "workspace_read", "workspace_list", "web_search", "vegaduta_setup_guide", "vegaduta_api_guide")) {
            tools.add(tool(n, "does something unrelated"));
        }
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "I want my agent to read Jira issues", 6)).contains("vegaduta_setup_guide"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "how do I connect Slack", 6)).contains("vegaduta_setup_guide"));
        assertTrue(names(AgentToolSelection.capForSmallModel(tools, "which endpoint lists agents", 6)).contains("vegaduta_api_guide"));
    }
}
