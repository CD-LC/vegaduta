package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.skills.tools.LoadSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ProposeSkillToolCallback;
import com.agenticai.core.agents.skills.tools.ReadSkillFileToolCallback;
import com.agenticai.core.agents.skills.tools.RunSkillScriptToolCallback;
import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * The pure, static tool-selection rules {@code AgentToolProvider} applies to every turn - the MCP
 * discovery cache, the always-relevant tool set, the per-turn hard cap, builtin-vs-MCP name
 * collisions and static MCP callback wrapping. Extracted from {@code AgentToolProvider}
 * (extract-only, no behavior change) so that class stays under the file-size ratchet; each rule is
 * static and parameterised so it is unit-testable without the bean's constructor dependencies (see
 * AgentToolProviderCapTest).
 */
public final class AgentToolSelection {

    private static final Logger log = LoggerFactory.getLogger(AgentToolSelection.class);

    private AgentToolSelection() {}

    /**
     * Package-private (not private) purely so {@link #computeCached}'s serialization/TTL contract
     * is directly unit-testable - see AgentToolProviderCapTest.
     */
    record CachedMcpTools(List<ToolCallback> tools, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Shared short-TTL cache lookup for a tenant's raw (pre-guardAndLog) MCP tool discovery result
     * - see the {@code staticMcpToolCache}/{@code dynamicMcpToolCache} field javadoc above for why
     * this exists. Caches the raw list only; every caller still wraps it in a fresh {@link
     * #guardAndLog} pass per call so per-turn agent/session/step-counter context is always correct
     * even when the underlying discovery was skipped.
     */
    /**
     * Shared compute-if-expired lookup for both MCP discovery caches. The KEY is the caller's
     * choice, and that choice is load-bearing:
     *
     * <p>The STATIC cache is keyed by the single global {@code
     * AgentToolProvider#STATIC_MCP_CACHE_KEY}, because the resource its serialization protects -
     * Spring AI's startup-created {@code mcpSyncClients} singleton list - is JVM-wide, shared by
     * every tenant. Direct incident (core.log, first real Quick Task retry after the fetch/time
     * catalog fix): "Sinks.many().unicast() sinks only allow a single Subscriber" - two concurrent
     * callers both landing in a cache-miss/expired window each ran the loader against the exact
     * same still-lazily-uninitialized McpSyncClient list (see application-mcp.yml's "initialized:
     * false"), and a STDIO client's underlying transport only tolerates ONE subscriber to its
     * incoming-message stream - the second concurrent listTools()/ initialize() call crashed
     * instead of just being slow. Per-tenant keying (how this cache was keyed until the 2026-08-19
     * MCP-blackout follow-up sweep) merely serialized callers WITHIN one tenant while still letting
     * two different tenants' cold loaders race the same JVM-wide clients - the same crash, one
     * tenant-boundary away.
     *
     * <p>The DYNAMIC cache stays keyed per tenant ({@code AgentToolProvider#tenantCacheKey()}): its
     * clients live per tenant inside {@link DynamicMcpConnectionManager}, so cross-tenant
     * parallelism there is genuinely safe and desirable.
     *
     * <p>{@code ConcurrentHashMap#compute} holds the key's bin lock for the whole remapping
     * function, so concurrent callers for the SAME key serialize here instead of both reaching the
     * clients. Package-private and static (key/TTL as parameters) so this serialization/TTL
     * contract is directly unit-testable without the full bean's ~40 constructor dependencies -
     * same rationale {@link #capToHardLimit} documents for itself.
     */
    static List<ToolCallback> computeCached(
            Map<String, CachedMcpTools> cache,
            String key,
            Duration ttl,
            Supplier<List<ToolCallback>> loader) {
        return cache.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null && !existing.isExpired()) {
                                return existing;
                            }
                            return new CachedMcpTools(loader.get(), Instant.now().plus(ttl));
                        })
                .tools();
    }

    /**
     * Tool names {@link ToolRelevanceSelector} never filters out - see its {@code select} javadoc
     * for why these are treated as standing capabilities rather than optional, keyword-matchable
     * tools. {@code remember_shared_fact} (2026-08-19 MCP-blackout follow-up sweep) is the shared
     * WRITE sibling of {@code remember_fact}/{@code recall_shared_graph_facts}, resolved right
     * beside them in {@code AgentToolProvider#resolveMemoryTools} and only ever offered when the
     * agent opted into shareMemoryWithTenant - as a builtin it has no MCP family, so no sibling
     * rescue can save it on a trimmed turn; omitting it here silently dropped it whenever a turn
     * lacked memory vocabulary and tenant-shared memory never accumulated. Package-private (not
     * private) for the same testability reason {@link #capToHardLimit}'s javadoc documents for its
     * own visibility - AgentToolProviderCapTest locks the write/read pairing.
     */
    static final Set<String> ALWAYS_RELEVANT_TOOL_NAMES =
            Set.of(
                    "remember_fact",
                    "remember_shared_fact",
                    "list_knowledge_collections",
                    "search_knowledge_base",
                    // "search the repository" shares words with search_knowledge_base, not with
                    // this tool's code-specific description - the wrong pick then reads as an
                    // empty-result loop. Keep the code index visible whenever code-intel offers it.
                    "search_code",
                    "recall_related_facts",
                    "recall_shared_graph_facts",
                    "request_human",
                    // Agent Skills: resolveSkillTools only offers the skill tools on turns where a
                    // skill is in
                    // play (message or recent history matches, a skill was loaded this session, or
                    // the agent
                    // has an explicit skill scope), so listing them here costs nothing on a plain
                    // turn. On a
                    // turn where load_skill IS offered, the selector must not trim the others:
                    // load_skill's
                    // output tells the model to read files / run scripts, and a user's message
                    // matches a
                    // SKILL's description, not these tool names, so keyword relevance could never
                    // rescue them.
                    LoadSkillToolCallback.NAME,
                    ReadSkillFileToolCallback.NAME,
                    RunSkillScriptToolCallback.NAME,
                    ProposeSkillToolCallback.NAME,
                    // Context compaction: offered only while app.context.compaction.enabled, and
                    // then a cleared placeholder points
                    // at it, so keyword relevance must never trim it away.
                    RestoreToolResultToolCallback.NAME,
                    // The Assistant's file tools and terminal: resolved only on a one-person
                    // install
                    // (projects folder / sandbox or workspace runner), for an agent that allows
                    // them. A build request ("make a Spring project") shares no words with
                    // "workspace_write" or "local_shell", so keyword relevance trimmed them on
                    // exactly the turns that need them.
                    "local_shell",
                    "local_shell_read",
                    "workspace_list",
                    "workspace_read",
                    "workspace_search",
                    "workspace_write",
                    "workspace_edit",
                    // The person's own screen: the local_desktop_* computer-use set, resolved only
                    // where DesktopToolSource offers it (one-person install + the sidecar opted
                    // in). "Play minesweeper" or "open Notes and type hi" shares no word with
                    // "local_desktop_click", and the family only works as a set (see the screen,
                    // decide, act) - a relevance-trimmed turn hid the whole capability and the
                    // model answered "I have no desktop tools".
                    "local_desktop_screen",
                    "local_desktop_elements",
                    "local_desktop_press",
                    "local_desktop_click",
                    "local_desktop_type",
                    "local_desktop_key",
                    "local_desktop_open",
                    "local_desktop_activate",
                    "local_desktop_notify",
                    "local_desktop_clipboard",
                    "run_desktop_recipe",
                    // The platform guides: "how do I connect Jira" shares no words with the tool's
                    // name,
                    // yet is exactly when the Assistant must look the answer up instead of
                    // guessing.
                    "vegaduta_setup_guide",
                    "vegaduta_api_guide",
                    // Document skills (pptx/docx/xlsx/...) load via load_skill and tell the model
                    // to call generate_document; the user's message matches the SKILL's
                    // vocabulary ("ppt", "deck", "spreadsheet"), not this tool's name, so
                    // lexical relevance trimmed it on exactly the turns the skill was in play.
                    "generate_document");

    /**
     * Public read-only view of {@link #ALWAYS_RELEVANT_TOOL_NAMES} for callers in other packages
     * (ChatTurnExecutor's stable tool ordering).
     */
    public static boolean isAlwaysRelevantTool(String toolName) {
        return toolName != null && ALWAYS_RELEVANT_TOOL_NAMES.contains(toolName);
    }

    /**
     * Defense-in-depth backstop, independent of {@link ToolRelevanceSelector} - see the live
     * incident documented on that class ({@code POST https://api.x.ai/v1/chat/completions} 400ing
     * with "Maximum tools limit reached. 5415 tools have been provided but the maximum is 350").
     * The selector is the FIRST line of defense and, with its own empty-query cap fixed, should
     * never let this trigger in practice - but this cap holds even if {@code
     * app.tools.auto-selection.enabled} is turned off, or {@code max-tools-per-turn} is
     * misconfigured above a real provider's own ceiling, or a future tool source added to {@code
     * AgentToolProvider#resolveToolsFor} above turns out to be unexpectedly unbounded. Every real
     * LLM provider's own tool-count ceiling (xAI's is 350; others vary) sits well above {@code
     * AgentToolProvider#hardCapPerTurn}'s default, so this should be invisible in normal operation
     * and only ever fire as the last-resort truncation it's meant to be - graceful degradation (log
     * + truncate) instead of a hard 400 on every single turn. Package-private and static (taking
     * the cap as a parameter rather than reading the instance field) so this pure truncation logic
     * is directly unit-testable without standing up the full bean's ~40 constructor dependencies.
     *
     * <p><b>Family-aware truncation (2026-08-19 fix, live prod incident):</b> a blind {@code
     * subList(0, hardCap)} used to slice straight through {@link ToolRelevanceSelector}'s own
     * sibling-stickiness output - that selector guarantees an MCP tool's whole family (e.g.
     * puppeteer's navigate/screenshot/click/evaluate chain) survives together specifically so the
     * model never gets one step of a chain without the others, but this second, independent
     * truncation stage had no notion of families at all and could - and on a large-enough tenant
     * catalog (thousands of resolved candidates from a big proxy-style MCP connector, this cap
     * firing constantly) reliably did - keep {@code puppeteer_screenshot} while dropping {@code
     * puppeteer_navigate} from the very same turn's list. The model still tried to call the dropped
     * tool (it had no way to know it was cut post-selection), got rejected by {@link
     * com.agenticai.core.chat.ArgumentSanitizingToolCallingManager}, and reported back to the user
     * as a full capability loss rather than "one step of this action is unavailable." Fix: group
     * candidates into order-preserving segments - one MCP family is one segment, everything else is
     * its own singleton segment - and admit only whole segments, stopping at the first segment that
     * would push the running total over {@code hardCap}. A single family larger than the entire
     * remaining budget (e.g. a proxy MCP server that itself fans out to hundreds of sub-tools) is
     * the one case a whole segment still can't be honored - rather than return zero tools this
     * turn, that one oversized family is truncated internally, but only ever as the very first
     * segment (so real per-turn relevance ordering is preserved for every case where a family fits
     * whole).
     */
    static List<ToolCallback> capToHardLimit(
            List<ToolCallback> tools, int hardCap, Set<String> mcpToolNames, UUID agentId) {
        if (tools.size() <= hardCap) {
            return tools;
        }
        log.warn(
                "Agent {} resolved {} tools for this turn - exceeds the hard cap of {} tool(s) - truncating before "
                        + "this reaches the LLM provider. This should be rare; if it recurs, check "
                        + "app.tools.auto-selection.max-tools-per-turn and this tenant's MCP/custom-tool catalog size.",
                agentId,
                tools.size(),
                hardCap);
        Map<String, List<ToolCallback>> segments = new LinkedHashMap<>();
        for (ToolCallback t : tools) {
            String name = t.getToolDefinition().name();
            String family = ToolRelevanceSelector.mcpFamily(name, mcpToolNames);
            String segmentKey = family != null ? "family:" + family : "solo:" + name;
            segments.computeIfAbsent(segmentKey, k -> new ArrayList<>()).add(t);
        }
        List<ToolCallback> result = new ArrayList<>();
        for (List<ToolCallback> segment : segments.values()) {
            if (result.size() + segment.size() > hardCap) {
                if (result.isEmpty()) {
                    result.addAll(segment.subList(0, hardCap));
                }
                break;
            }
            result.addAll(segment);
        }
        return result;
    }

    // Small-model mode (V501): the tools offered on one turn, the browsing quota and the ranking
    // weights are
    // app.chat.small-model.* - see SmallModelProperties.

    /**
     * Small-model mode (V501): at most {@code cap} tools, the ones most relevant to {@code
     * userMessage}. Independent of {@link ToolRelevanceSelector}'s configured budgets (which are
     * sized for frontier models) and applied after it, to whatever the turn resolved. Relevance is
     * lexical overlap between the message and the tool's name and description, with ties broken by
     * the resolved order; a tool named in the message always wins. A list already within the cap is
     * returned as it is. Returns a mutable list, like the resolver does.
     */
    public static List<ToolCallback> capForSmallModel(
            List<ToolCallback> tools, String userMessage, int cap) {
        return capForSmallModel(tools, userMessage, cap, Set.of());
    }

    /**
     * As above, with the cap and ranking weights taken from {@code settings} ({@code
     * app.chat.small-model.*}).
     */
    public static List<ToolCallback> capForSmallModel(
            List<ToolCallback> tools,
            String userMessage,
            Set<String> stickyTools,
            SmallModelProperties settings) {
        return capForSmallModel(tools, userMessage, settings.getToolCap(), stickyTools, settings);
    }

    /**
     * As {@link #capForSmallModel(List, String, int)}, for a turn whose message may be a short
     * follow-up: {@code stickyTools} are the tools the conversation already used (most recent
     * first), kept ahead of the message-driven choice, so a task does not lose its tools to a
     * message ("continue") that names none. They never take the slots of a tool the message names
     * itself.
     */
    public static List<ToolCallback> capForSmallModel(
            List<ToolCallback> tools, String userMessage, int cap, Set<String> stickyTools) {
        return capForSmallModel(tools, userMessage, cap, stickyTools, new SmallModelProperties());
    }

    /** As above, with the browsing quota and ranking weights taken from {@code settings}. */
    public static List<ToolCallback> capForSmallModel(
            List<ToolCallback> tools,
            String userMessage,
            int cap,
            Set<String> stickyTools,
            SmallModelProperties settings) {
        if (tools == null || tools.size() <= cap) {
            return tools;
        }
        Set<String> query = tokens(userMessage);
        Map<ToolCallback, Integer> scores = new java.util.IdentityHashMap<>();
        for (ToolCallback tool : tools) {
            scores.put(tool, smallModelScore(query, tool, userMessage, settings));
        }
        List<ToolCallback> ranked = new ArrayList<>(tools);
        // Stable sort: equal scores keep the resolved order (always-relevant tools come first
        // there).
        ranked.sort(
                java.util.Comparator.comparingInt((ToolCallback t) -> scores.get(t)).reversed());
        // A request that signals several intents ("search the web ... then list my files") used to
        // lose a whole family: every boosted tool scores the same, the resolved order puts the
        // Assistant's seven workspace/shell tools first, and web_search fell off the end. Take the
        // best tool of each signalled intent first (round-robin, in the order the tools rank), then
        // fill the rest by score, so every intent the person voiced keeps at least one tool.
        List<ToolCallback> kept = new ArrayList<>(cap);
        Set<ToolCallback> taken =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (stickyTools != null && !stickyTools.isEmpty()) {
            int named =
                    (int)
                            ranked.stream()
                                    .filter(t -> scores.get(t) >= settings.getNamedToolScore())
                                    .count();
            for (String sticky : stickyTools) {
                for (ToolCallback tool : ranked) {
                    if (kept.size() >= cap - named) {
                        break;
                    }
                    if (tool.getToolDefinition().name().equals(sticky)
                            && scores.get(tool) < settings.getNamedToolScore()) {
                        taken.add(tool);
                        kept.add(tool);
                    }
                }
            }
        }
        Map<Integer, List<ToolCallback>> byIntent = new java.util.TreeMap<>();
        for (ToolCallback tool : ranked) {
            int intent = matchedIntent(query, userMessage, tool.getToolDefinition().name());
            if (intent >= 0) {
                byIntent.computeIfAbsent(intent, k -> new ArrayList<>()).add(tool);
            }
        }
        boolean progress = true;
        while (kept.size() < cap && progress) {
            progress = false;
            for (Map.Entry<Integer, List<ToolCallback>> family : byIntent.entrySet()) {
                // One tool per intent per pass, except a family that only works as a set (driving a
                // page needs navigate, read and click together, and driving the screen needs
                // screen, elements and press/click together - one of them alone sent the model
                // round in circles).
                int quota =
                        INTENTS.get(family.getKey()).getValue().get(0).startsWith("browser_")
                                        || INTENTS.get(family.getKey())
                                                .getValue()
                                                .get(0)
                                                .startsWith("local_desktop_")
                                ? settings.getBrowserFamilyQuota()
                                : 1;
                for (ToolCallback candidate : family.getValue()) {
                    if (kept.size() >= cap || quota == 0) {
                        break;
                    }
                    if (taken.add(candidate)) {
                        kept.add(candidate);
                        progress = true;
                        quota--;
                    }
                }
            }
        }
        for (ToolCallback tool : ranked) {
            if (kept.size() >= cap) {
                break;
            }
            if (taken.add(tool)) {
                kept.add(tool);
            }
        }
        // Explicitly named tools outrank everything, whatever family they belong to.
        kept.sort(java.util.Comparator.comparingInt((ToolCallback t) -> scores.get(t)).reversed());
        log.info(
                "Small-model mode: offering {} of {} resolved tools this turn: {}",
                kept.size(),
                tools.size(),
                kept.stream().map(t -> t.getToolDefinition().name()).toList());
        return kept;
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
    private static final List<Map.Entry<Set<String>, List<String>>> INTENTS =
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

    private static int smallModelScore(
            Set<String> query,
            ToolCallback tool,
            String userMessage,
            SmallModelProperties settings) {
        String name = tool.getToolDefinition().name();
        if (userMessage != null
                && userMessage
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return settings.getNamedToolScore();
        }
        int score = intentScore(query, userMessage, name, settings.getIntentBoost());
        for (String token : tokens(name.replace('_', ' '))) {
            if (query.contains(token)) {
                score += 3;
            }
        }
        for (String token : tokens(tool.getToolDefinition().description())) {
            if (query.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private static int intentScore(
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
    private static int matchedIntent(Set<String> query, String userMessage, String toolName) {
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

    private static Set<String> tokens(String text) {
        Set<String> out = new java.util.HashSet<>();
        for (String raw : WordTokens.words(text)) {
            out.add(
                    raw.endsWith("s") && raw.length() > 4
                            ? raw.substring(0, raw.length() - 1)
                            : raw);
        }
        return out;
    }

    /**
     * Spring AI's {@code ToolCallingChatOptions} hard-rejects (throws, failing the WHOLE chat turn)
     * if two callbacks in the final list report the same tool name - confirmed live 2026-07-26: an
     * agent with both code-intel enabled AND a dynamic MCP server registered that happened to also
     * expose a tool named {@code search_code} threw {@code IllegalStateException: Multiple tools
     * with the same name (search_code) found} on every single turn, making the agent completely
     * unusable rather than just shadowing one tool.
     *
     * <p>{@code builtinTools} always wins a name collision against {@code mcpTools}: platform
     * built-ins are trusted, reviewed code, an MCP tool is tenant-supplied and arbitrary. Correct
     * by construction, not by inspecting each callback's type - {@code builtinTools} is seeded into
     * the result map FIRST from a list that never contained any MCP tool to begin with, so there is
     * no ordering trick or instanceof check needed; {@code mcpTools} entries are only added for
     * names not already claimed. A tenant registering an MCP server whose tool happens to share a
     * built-in's name is far more likely to be an accidental clash (there are only so many good
     * names for "search code") than a deliberate shadowing attempt, but even if it were the latter
     * this is exactly the right outcome - the built-in wins, the MCP tool is dropped from THIS
     * turn's list rather than allowed to masquerade under a trusted name, and the turn proceeds
     * instead of failing outright.
     *
     * <p>Bug fix (2026-08-09, misdiagnosis follow-up): a collision between two MCP tools that
     * happen to share a name (now rarer post-namespacing, see {@link NamespacedMcpToolCallback},
     * but still possible - namespacing collapses to the same prefixed name if two servers are
     * literally registered under the same server name) used to be reported and logged as "a
     * built-in platform tool already uses that name" unconditionally, because the old code only
     * ever checked {@code byName.containsKey(name)} without asking WHOSE entry that was. {@code
     * mcpNamesClaimedThisPass} tracks which names in {@code byName} were placed by an MCP tool
     * earlier in THIS SAME loop (as opposed to seeded from {@code builtinTools} before the loop
     * started) so the reported cause - and the admin Issues portal message an operator actually
     * acts on - matches what really collided, instead of sending someone to hunt for a built-in
     * that was never involved.
     *
     * <p>Package-private and static (taking the collision-issue side effect as a callback rather
     * than reading {@code this.issueService}) for the same reason {@link #capToHardLimit} is:
     * directly unit-testable without standing up the full bean's ~40 constructor dependencies.
     */
    static List<ToolCallback> mergeFavoringBuiltins(
            List<ToolCallback> builtinTools,
            List<ToolCallback> mcpTools,
            UUID agentId,
            java.util.function.BiConsumer<String, String> collisionIssueRecorder) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback tool : builtinTools) {
            ToolCallback previous = byName.put(tool.getToolDefinition().name(), tool);
            if (previous != null) {
                // Two DIFFERENT built-in resolvers produced the same name -
                // a real platform bug, not a tenant-config issue. Keep the
                // first, log loudly since this one is ours to fix.
                log.warn(
                        "Agent {} tool name collision: two platform built-in tools both named '{}' - keeping the first.",
                        agentId,
                        tool.getToolDefinition().name());
                byName.put(tool.getToolDefinition().name(), previous);
            }
        }
        Set<String> mcpNamesClaimedThisPass = new java.util.HashSet<>();
        for (ToolCallback tool : mcpTools) {
            String name = tool.getToolDefinition().name();
            if (byName.containsKey(name)) {
                boolean collidedWithBuiltin = !mcpNamesClaimedThisPass.contains(name);
                String message =
                        collidedWithBuiltin
                                ? "An MCP-registered tool named '"
                                        + name
                                        + "' was dropped for this turn because a built-in "
                                        + "platform tool already uses that name. Rename the tool on the MCP server (or disable "
                                        + "that server) if you need it available - the built-in always takes priority."
                                : "An MCP-registered tool named '"
                                        + name
                                        + "' was dropped for this turn because another "
                                        + "MCP-registered tool already claimed that exact name earlier in the same resolution "
                                        + "pass (no built-in tool is involved in this collision). Rename one of the servers, or "
                                        + "the tool on one of them, if you need both available.";
                log.warn(
                        "Agent {} tool name collision: dropping MCP-provided '{}' ({}).",
                        agentId,
                        name,
                        collidedWithBuiltin
                                ? "collided with a built-in"
                                : "collided with an earlier MCP tool");
                collisionIssueRecorder.accept(name, message);
                continue;
            }
            byName.put(name, tool);
            mcpNamesClaimedThisPass.add(name);
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Per-TOOL wrap isolation for {@link #resolveStaticMcpTools} (2026-08-19 MCP-blackout follow-up
     * sweep - archetype smell #1 surviving in a narrower seam): both wrapper constructors below
     * eagerly call {@code delegate.getToolDefinition()}, and this loop used to run OUTSIDE the
     * per-client try, so a definition-materialization throw from ONE tool on ONE server escaped to
     * the outer catch - discarding every healthy server's already-discovered tools and caching the
     * empty list for the TTL. A wrap-time failure now costs at most that one tool, never the
     * catalog. Package-private and static for the same direct-unit-testability reason {@link
     * #capToHardLimit} documents.
     *
     * <p>MCP-DOCTOR follow-up (pentest M5): static (YAML-wired) MCP servers were the one
     * tool-invocation path that skipped {@link SanitizedMcpToolCallback} - guardAndLog still
     * applies ToolCallOutputValidator + GuardrailService.sanitizeToolOutput to every tool
     * regardless of origin, but only the dynamic (#424) path got the ANSI/control-char strip,
     * invisible-Unicode strip (bidi overrides, zero-width, Unicode Tag block), and discovery-time
     * name/description poisoning clamp. A static server is operator-configured, not tenant-typed,
     * but it's still a third-party process whose output/description this platform doesn't control -
     * same wrap {@code DynamicMcpConnectionManager#connect} already applies to every dynamic
     * callback it advertises.
     *
     * <p>Pentest L4 follow-up (2026-08-09): two static servers that both happen to expose a
     * same-named tool (e.g. both ship "search") used to silently collide in mergeFavoringBuiltins
     * with no structural way to tell them apart - {@link NamespacedMcpToolCallback} was previously
     * reserved for dynamic servers only (see its javadoc's now-updated framing) on the theory that
     * static servers are operator-trusted so namespacing was purely a spoofing defense; that
     * reasoning missed the operational collision case, which is exactly as real for trusted servers
     * as untrusted ones.
     */
    static List<ToolCallback> wrapStaticCallbacks(ToolCallback[] fetched, String serverName) {
        List<ToolCallback> wrapped = new ArrayList<>();
        for (ToolCallback cb : fetched) {
            try {
                wrapped.add(
                        new NamespacedMcpToolCallback(
                                new SanitizedMcpToolCallback(cb), serverName));
            } catch (Exception ex) {
                // The tool's own name may be exactly what failed to materialize - best-effort
                // identification via the callback itself, never a second getToolDefinition() call.
                log.warn(
                        "Skipping one tool from static MCP server '{}' - its definition could not be materialized/"
                                + "wrapped ({}). The server's other tools are unaffected this turn.",
                        serverName,
                        cb,
                        ex);
            }
        }
        return wrapped;
    }
}
