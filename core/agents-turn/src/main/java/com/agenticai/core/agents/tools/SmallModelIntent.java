package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The small-model intent table of {@link AgentToolSelection}: what a person says -> the tool
 * families that do it, plus the scoring/signalling helpers that read the table. Split out only for
 * the line-count cap; package-private like its owner.
 */
final class SmallModelIntent {

    private SmallModelIntent() {
        // static declaration table + its scorers
    }

    /**
     * What a person says -> the tool families that do it. Lexical overlap with a tool's name and
     * description (the base score below) misses most real requests: "what is the weather in Goa"
     * shares no word with {@code web_search}, "my meeting tomorrow" none with a calendar tool, so a
     * small model was offered six unrelated tools and answered from memory or invented a call (seen
     * 2026-10-18: it asked for fetch_web_page, which was not among the six). Each entry is (words
     * that signal the intent, name fragments of the tools that serve it); a matching tool scores
     * {@code app.chat.small-model.intent-boost}, enough to outrank any overlap score but not an
     * explicit tool-name mention.
     */
    static final List<Map.Entry<Set<String>, List<String>>> INTENTS =
            List.of(
                    Map.entry(
                            Set.of(
                                    "search", "latest", "news", "today", "weather", "price",
                                    "current", "who", "when", "find", "google", "lookup", "online",
                                    "web"),
                            List.of("web_search", "fetch_web_page")),
                    Map.entry(
                            Set.of(
                                    "http", "https", "www", "url", "link", "website", "webpage",
                                    "page", "site"),
                            List.of("fetch_web_page", "web_search")),
                    Map.entry(
                            Set.of(
                                    "email",
                                    "gmail",
                                    "mail",
                                    "inbox",
                                    "calendar",
                                    "meeting",
                                    "schedule",
                                    "event",
                                    "invite",
                                    "drive",
                                    "sheet",
                                    "sheets",
                                    "spreadsheet",
                                    "doc",
                                    "docs",
                                    "contacts"),
                            List.of(
                                    "google",
                                    "gmail",
                                    "calendar",
                                    "sheets",
                                    "drive",
                                    "email",
                                    "mail")),
                    Map.entry(
                            Set.of(
                                    "file",
                                    "files",
                                    "folder",
                                    "directory",
                                    "code",
                                    "script",
                                    "project",
                                    "run",
                                    "build",
                                    "install",
                                    "edit",
                                    "write",
                                    "read",
                                    "create",
                                    "repo",
                                    "terminal",
                                    "command",
                                    "shell",
                                    "disk",
                                    "space",
                                    "process",
                                    "processes",
                                    "largest",
                                    "git",
                                    "npm",
                                    "python",
                                    "copy",
                                    "move",
                                    "delete",
                                    "rename",
                                    "list"),
                            List.of("workspace_", "local_shell")),
                    // Driving a page: opening a site, clicking, signing in, filling a form. The
                    // agent's own browser
                    // (browser_*) is a different tool from fetching a page's text; the first
                    // fragment is the best fit.
                    Map.entry(
                            Set.of(
                                    "browser",
                                    "browse",
                                    "click",
                                    "screenshot",
                                    "login",
                                    "log",
                                    "sign",
                                    "form",
                                    "button",
                                    "fill",
                                    "submit",
                                    "navigate",
                                    "tab"),
                            List.of(
                                    "browser_navigate",
                                    "browser_snapshot",
                                    "browser_read_page_text",
                                    "browser_click_element",
                                    "browser_type_into",
                                    "browser_screenshot")),
                    // Routines and reminders: without this a 6-tool cap drops schedule_task on
                    // about half of them.
                    Map.entry(
                            Set.of(
                                    "schedule",
                                    "remind",
                                    "reminder",
                                    "reminders",
                                    "every",
                                    "daily",
                                    "weekly",
                                    "hourly",
                                    "monthly",
                                    "cron",
                                    "recurring",
                                    "repeat"),
                            List.of("schedule_task")),
                    Map.entry(
                            Set.of("remember", "recall", "memory", "forget", "earlier", "told"),
                            List.of("remember", "recall", "search_conversation")),
                    Map.entry(
                            Set.of("document", "report", "pdf", "docx", "slides", "presentation"),
                            List.of("generate_document")),
                    // Setting something up or asking how the platform works: the guide, never a
                    // guess.
                    Map.entry(
                            Set.of(
                                    "connect",
                                    "integrate",
                                    "integration",
                                    "configure",
                                    "setup",
                                    "enable",
                                    "install",
                                    "mcp",
                                    "jira",
                                    "slack",
                                    "whatsapp",
                                    "telegram",
                                    "github",
                                    "notion",
                                    "linear",
                                    "provider",
                                    "sso",
                                    "saml",
                                    "plugin",
                                    "skill",
                                    "credential"),
                            List.of("vegaduta_setup_guide")),
                    Map.entry(
                            Set.of("api", "endpoint", "openapi", "swagger", "rest", "curl"),
                            List.of("vegaduta_api_guide", "vegaduta_setup_guide")),
                    // The person's own documents: "what does my PDF say", "according to my notes".
                    Map.entry(
                            Set.of(
                                    "knowledge",
                                    "uploaded",
                                    "upload",
                                    "document",
                                    "documents",
                                    "pdf",
                                    "notes",
                                    "paper",
                                    "according",
                                    "summarise",
                                    "summarize",
                                    "summary"),
                            List.of("search_knowledge_base", "list_knowledge_collections")),
                    // The person's own screen: "play minesweeper", "open Notes", "click the
                    // button", "what is on my screen" - a desktop request shares no word with
                    // local_desktop_click, and like the browser family it only works as a set
                    // (see the screen, decide, act), so the family quota below covers it too.
                    Map.entry(
                            Set.of(
                                    "desktop",
                                    "screen",
                                    "screenshot",
                                    "computer",
                                    "monitor",
                                    "window",
                                    "app",
                                    "game",
                                    "play",
                                    "click",
                                    "type",
                                    "press",
                                    "keyboard",
                                    "mouse",
                                    "gui",
                                    "open"),
                            List.of("local_desktop_", "run_desktop_recipe")));

    static int intentScore(
            Set<String> query, String userMessage, String toolName, int intentBoost) {
        String lowerName = toolName.toLowerCase(java.util.Locale.ROOT);
        boolean hasUrl = hasUrl(userMessage);
        int best = 0;
        for (Map.Entry<Set<String>, List<String>> intent : INTENTS) {
            if (!signalled(intent, query, hasUrl)) {
                continue;
            }
            List<String> fragments = intent.getValue();
            for (int i = 0; i < fragments.size(); i++) {
                if (lowerName.contains(fragments.get(i))) {
                    // The first fragment is the tool that does the job; later ones are helpers.
                    best = Math.max(best, intentBoost - i);
                    break;
                }
            }
        }
        return best;
    }

    /** The index in {@link #INTENTS} of the first signalled intent this tool serves, or -1. */
    static int matchedIntent(Set<String> query, String userMessage, String toolName) {
        String lowerName = toolName.toLowerCase(java.util.Locale.ROOT);
        boolean hasUrl = hasUrl(userMessage);
        for (int index = 0; index < INTENTS.size(); index++) {
            Map.Entry<Set<String>, List<String>> intent = INTENTS.get(index);
            if (!signalled(intent, query, hasUrl)) {
                continue;
            }
            for (String fragment : intent.getValue()) {
                if (lowerName.contains(fragment)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean hasUrl(String userMessage) {
        return userMessage != null && userMessage.matches("(?s).*(https?://|www\\.)\\S+.*");
    }

    private static boolean signalled(
            Map.Entry<Set<String>, List<String>> intent, Set<String> query, boolean hasUrl) {
        return query.stream().anyMatch(intent.getKey()::contains)
                || (hasUrl && intent.getKey().contains("url"));
    }
}
