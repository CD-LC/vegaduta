package com.agenticai.core.codeintel;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Shared base for the read-only code-intel analysis tools (repo map, references, implementations,
 * call paths, outlines, dead code, test linkage, api surface, hotspots, import cycles). Every
 * instance is one {@link ToolCallback} driven by a {@link Spec}: the name/description/JSON schema
 * plus the {@link CodeIntelClient} call it maps to. This exists so the eight analysis tools share
 * the repo-scope enforcement and error-shape conventions {@link SearchCodeToolCallback} established
 * instead of copying them into eight files.
 *
 * <p>Scope rules are identical to {@link SearchCodeToolCallback}: an empty {@code allowedRepoIds}
 * means unrestricted (no grant configured); a non-empty set requires {@code repo_id} to be in it.
 * Unlike {@code search_code}'s cross-repo fan-out, these tools all require a repo - the analysis
 * endpoints are per-repo by design.
 */
public class CodeIntelAnalysisToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CodeIntelAnalysisToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Everything a single analysis tool needs: identity, model-facing text, schema, dispatch. */
    public record Spec(
            String name,
            String description,
            String inputSchema,
            BiFunction<CodeIntelAnalysis, Map<String, Object>, String> invoke) {}

    private final CodeIntelAnalysis analysis;
    private final Set<String> allowedRepoIds;
    private final Spec spec;

    public CodeIntelAnalysisToolCallback(
            CodeIntelAnalysis analysis, Set<String> allowedRepoIds, Spec spec) {
        this.analysis = analysis;
        this.allowedRepoIds = allowedRepoIds == null ? Set.of() : allowedRepoIds;
        this.spec = spec;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object repoId = args.get("repo_id");
            if (repoId == null || repoId.toString().isBlank()) {
                if (!allowedRepoIds.isEmpty() && allowedRepoIds.size() == 1) {
                    // Single-scope agents may omit repo_id - there is exactly one
                    // repo they can mean.
                    args = new java.util.LinkedHashMap<>(args);
                    args.put("repo_id", allowedRepoIds.iterator().next());
                } else if (!allowedRepoIds.isEmpty()) {
                    return "{\"error\": \"repo_id is required - pass one of: "
                            + String.join(", ", allowedRepoIds)
                            + "\"}";
                } else {
                    return "{\"error\": \"repo_id is required (list this tenant's repos "
                            + "with list_repos / the code-intel page first)\"}";
                }
            } else if (!allowedRepoIds.isEmpty() && !allowedRepoIds.contains(repoId.toString())) {
                return "{\"error\": \"This agent is not permitted to target that repository\"}";
            }
            return spec.invoke().apply(analysis, args);
        } catch (Exception e) {
            log.warn("{} tool call failed", spec.name(), e);
            return "{\"error\": \""
                    + spec.name()
                    + " failed: "
                    + SearchCodeToolCallback.safe(e.getMessage())
                    + "\"}";
        }
    }
}
