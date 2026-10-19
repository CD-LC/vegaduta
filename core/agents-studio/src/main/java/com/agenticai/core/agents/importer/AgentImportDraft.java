package com.agenticai.core.agents.importer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a source mapper extracted, before it is matched to the platform's model catalog and
 * turned into a real agent. A plain mutable holder - the mappers are pure functions that fill one
 * in; {@link AgentImportFinalizer} then normalises it (name, length caps, clamps).
 *
 * <p>{@code providerHint}/{@code modelHint} are the SOURCE's own words (e.g. {@code "openai"},
 * {@code "gpt-4o-mini"}); {@code AgentImportService} resolves them against the real catalog and
 * never fabricates a model name.
 */
public final class AgentImportDraft {

    public final AgentImportReport report = new AgentImportReport();

    public String name;
    public String description;
    public String instructions;
    public String providerHint;
    public String modelHint;
    public Double temperature;
    public Double topP;
    public final Set<String> suggestedCapabilities = new LinkedHashSet<>();
    public final Set<String> suggestedTools = new LinkedHashSet<>();

    /** Platform capability key (see {@code E2bCodeExecutionCapability}) for sandboxed code execution. */
    public static final String CAP_CODE_EXECUTION = "e2b-code-execution";
    /** Platform tool names (see {@code BuiltInToolClassifications}). */
    public static final String TOOL_RUN_CODE = "run_code";
    public static final String TOOL_KNOWLEDGE_SEARCH = "search_knowledge_base";
    public static final String TOOL_WEB_SEARCH = "web_search";
    public static final String TOOL_FETCH_PAGE = "fetch_web_page";
    public static final String TOOL_HTTP_REQUEST = "http_request";
    public static final String TOOL_GENERATE_IMAGE = "generate_image";
    public static final String TOOL_DELEGATE = "delegate_to_specialist";

    /** Hint text reused by every mapper so the report reads the same whatever the source. */
    public static final String HINT_CUSTOM_TOOL =
        "Create an HTTP/OpenAPI custom tool or connect an MCP server that provides it, then attach it to the agent.";
    public static final String HINT_KNOWLEDGE =
        "Upload the source documents to a Knowledge base and turn the knowledge base on for this agent.";
    public static final String HINT_CODE_EXEC =
        "Turn on the Code execution capability for this agent; it needs an E2B key on the Providers page.";
    public static final String HINT_DELEGATION =
        "Create it as its own agent and let this agent hand work to it through agent delegation.";
}
