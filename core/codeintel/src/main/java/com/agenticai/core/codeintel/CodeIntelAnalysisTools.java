package com.agenticai.core.codeintel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/**
 * The read-only code-intel analysis tool set, built by {@code AgentKnowledgeToolResolver}'s {@code
 * resolveCodeIntelTools} (agents-tools module) alongside the original five. Each entry is a {@link
 * CodeIntelAnalysisToolCallback.Spec}: name, model-facing description (written as usage guidance,
 * not just "what it does" - the description is the agent's only pointer on WHEN to reach for the
 * tool), JSON input schema, and the {@link CodeIntelClient} dispatch.
 */
public final class CodeIntelAnalysisTools {

    private CodeIntelAnalysisTools() {}

    private static String repoProp(String desc) {
        return "\"repo_id\": {\"type\": \"string\", \"description\": \"" + desc + "\"}";
    }

    private static String s(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null ? "" : v.toString();
    }

    private static int i(Map<String, Object> a, String k, int def) {
        Object v = a.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** All read-only analysis tools for this agent. */
    public static List<ToolCallback> all(CodeIntelClient client, Set<String> allowedRepoIds) {
        return all(client.analysis(), allowedRepoIds);
    }

    /** As {@link #all(CodeIntelClient, Set)} over an already-resolved analysis surface. */
    public static List<ToolCallback> all(CodeIntelAnalysis analysis, Set<String> allowedRepoIds) {
        return List.of(
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_map",
                                "Returns the ranked skeleton of a repository: the most-central symbols with "
                                        + "their signatures (never bodies), grouped by file, under a character "
                                        + "budget. CALL THIS FIRST when learning a codebase - it answers 'what is "
                                        + "the shape of this repo' before you fetch any file contents.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + repoProp("Registered repo id.")
                                        + ",\"budget\":{\"type\":\"integer\",\"description\":\"Char budget (1000-64000, default 16000)\"}"
                                        + ",\"top_n\":{\"type\":\"integer\",\"description\":\"Max symbols (default 200)\"}}}",
                                (c, a) ->
                                        c.repoMap(
                                                s(a, "repo_id"),
                                                i(a, "budget", 16000),
                                                i(a, "top_n", 200)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "find_symbols",
                                "Finds symbols by name path ('Auth.login' matches 'auth.Auth.login') with kind "
                                        + "and path filters - returns signature, doc snippet and line span. Use to "
                                        + "locate a definition before fetching its body.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"query\":{\"type\":\"string\",\"description\":\"Name or qualified-name substring\"},"
                                        + repoProp("Registered repo id.")
                                        + ",\"kind\":{\"type\":\"string\",\"description\":\"function|method|class|interface|struct|enum\"}"
                                        + ",\"path_prefix\":{\"type\":\"string\"}"
                                        + ",\"limit\":{\"type\":\"integer\"}"
                                        + "},\"required\":[\"query\"]}",
                                (c, a) ->
                                        c.findSymbols(
                                                s(a, "repo_id"),
                                                s(a, "query"),
                                                s(a, "kind"),
                                                s(a, "path_prefix"),
                                                i(a, "limit", 50)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "symbol_detail",
                                "Returns one symbol's metadata AND its exact source body - the precise "
                                        + "alternative to reading an entire file. Use after find_symbols or "
                                        + "code_map to read just the definition you need.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"qualified_name\":{\"type\":\"string\",\"description\":\"e.g. AuthService.login\"},"
                                        + repoProp("Registered repo id.")
                                        + ",\"include_body\":{\"type\":\"boolean\"}"
                                        + "},\"required\":[\"qualified_name\"]}",
                                (c, a) ->
                                        c.symbolDetail(
                                                s(a, "repo_id"),
                                                s(a, "qualified_name"),
                                                !(a.get("include_body") instanceof Boolean b)
                                                        || b))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_outline",
                                "One file's symbol tree (classes, methods, signatures) - the structural "
                                        + "overview to read before the file itself.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"path\":{\"type\":\"string\",\"description\":\"Repo-relative file path\"},"
                                        + repoProp("Registered repo id.")
                                        + "},\"required\":[\"path\"]}",
                                (c, a) -> c.fileOutline(s(a, "repo_id"), s(a, "path")))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_references",
                                "Every inbound mention of a symbol: calls, inherits AND value/type references "
                                        + "(usages that are not calls) - 'find all references'. Use to see the full "
                                        + "footprint of a type, constant or field, not just its call sites.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"symbol\":{\"type\":\"string\"},"
                                        + repoProp("Registered repo id.")
                                        + "},\"required\":[\"symbol\"]}",
                                (c, a) -> c.references(s(a, "repo_id"), s(a, "symbol")))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_implementations",
                                "Recursive subclass/implementer tree of a class or interface - 'who "
                                        + "implements X'. Use before changing an interface to find every "
                                        + "implementation that must follow.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"symbol\":{\"type\":\"string\"},"
                                        + repoProp("Registered repo id.")
                                        + ",\"depth\":{\"type\":\"integer\",\"description\":\"Max levels (default 4)\"}"
                                        + "},\"required\":[\"symbol\"]}",
                                (c, a) ->
                                        c.implementations(
                                                s(a, "repo_id"),
                                                s(a, "symbol"),
                                                i(a, "depth", 4)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_path",
                                "The shortest call chain between two symbols - 'how does X end up calling Y'. "
                                        + "Use to trace data/control flow across the codebase.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"src\":{\"type\":\"string\"}"
                                        + ",\"dst\":{\"type\":\"string\"},"
                                        + repoProp("Registered repo id.")
                                        + ",\"max_depth\":{\"type\":\"integer\",\"description\":\"Max hops (default 8)\"}"
                                        + "},\"required\":[\"src\",\"dst\"]}",
                                (c, a) ->
                                        c.callPath(
                                                s(a, "repo_id"),
                                                s(a, "src"),
                                                s(a, "dst"),
                                                i(a, "max_depth", 8)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_dead_code",
                                "Top-level symbols with zero inbound call/inherit/reference edges - "
                                        + "unreachable-code REVIEW CANDIDATES (not proof). Use for cleanup sweeps "
                                        + "and 'is this used anywhere?' checks.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + repoProp("Registered repo id.")
                                        + ",\"limit\":{\"type\":\"integer\",\"description\":\"Max candidates (default 100)\"}}}",
                                (c, a) -> c.deadCode(s(a, "repo_id"), i(a, "limit", 100)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_tests",
                                "Which test-file symbols reach this symbol - the coverage linkage between "
                                        + "production code and its tests. Use to find what tests cover a change, "
                                        + "or to spot untested code.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"symbol\":{\"type\":\"string\"},"
                                        + repoProp("Registered repo id.")
                                        + "},\"required\":[\"symbol\"]}",
                                (c, a) -> c.testsFor(s(a, "repo_id"), s(a, "symbol")))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_api_surface",
                                "The repo's extracted HTTP routes (FastAPI/Flask/Express/Spring/Go/Rust) "
                                        + "grouped by method - 'what does this service expose'.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + repoProp("Registered repo id.")
                                        + "}}",
                                (c, a) -> c.apiSurface(s(a, "repo_id")))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_hotspots",
                                "git churn x size ranking - files changed most AND carrying the most symbols: "
                                        + "where bugs concentrate. Use to target review attention and test coverage.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + repoProp("Registered repo id.")
                                        + ",\"days\":{\"type\":\"integer\",\"description\":\"Window in days (default 90)\"}"
                                        + ",\"limit\":{\"type\":\"integer\"}}}",
                                (c, a) ->
                                        c.hotspots(
                                                s(a, "repo_id"),
                                                i(a, "days", 90),
                                                i(a, "limit", 30)))),
                new CodeIntelAnalysisToolCallback(
                        analysis,
                        allowedRepoIds,
                        new CodeIntelAnalysisToolCallback.Spec(
                                "code_import_cycles",
                                "File-level import-graph cycles (strongly connected components) - "
                                        + "architectural tangles where files mutually depend. Use for architecture "
                                        + "refactors and dependency audits.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + repoProp("Registered repo id.")
                                        + "}}",
                                (c, a) -> c.importCycles(s(a, "repo_id")))));
    }
}
