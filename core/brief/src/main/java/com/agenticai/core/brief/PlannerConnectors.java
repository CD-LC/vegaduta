package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief.EnvBinding;
import com.agenticai.core.brief.model.Brief.McpServerSpec;
import com.agenticai.core.brief.model.Brief.ToolAuth;
import com.agenticai.core.brief.model.Brief.ToolSpec;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistryEnvVarSpec;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.agenticai.core.brief.PlannerText.orElse;
import static com.agenticai.core.brief.PlannerText.sanitizeSecretName;
import static com.agenticai.core.brief.PlannerText.text;

/**
 * The planner's connector step: MCP servers (chosen only from what the real registry search returned, never from
 * the model) and tools (OpenAPI or code). Split out of {@link BriefPlannerService}; any credential either needs is
 * declared by name in the {@link PlannerSecretBook}, never valued.
 */
final class PlannerConnectors {

    private static final Logger log = LoggerFactory.getLogger(PlannerConnectors.class);

    private static final int MCP_CANDIDATES = 5;

    private final OfficialMcpRegistryClient registryClient;
    private final PlannerLlm llm;

    PlannerConnectors(OfficialMcpRegistryClient registryClient, PlannerLlm llm) {
        this.registryClient = registryClient;
        this.llm = llm;
    }

    McpServerSpec resolveMcp(JsonNode m, PlannerRefs refs, PlannerSecretBook secrets, int index, List<String> warnings) {
        String key = text(m, "key");
        String query = text(m, "query");
        if (query == null) {
            warnings.add("Skipped an MCP server with no search phrase.");
            return null;
        }
        RegistrySearchResponse response;
        try {
            response = registryClient.search(query, null);
        } catch (RuntimeException e) {
            log.warn("MCP registry search failed during planning", e);
            response = null;
        }
        if (response == null || response.unavailable() || response.results() == null) {
            warnings.add("The MCP registry could not be reached, so no server was chosen for '" + query + "'.");
            return null;
        }
        List<RegistrySearchResult> candidates = response.results().stream()
            .filter(RegistrySearchResult::runnable).limit(MCP_CANDIDATES).toList();
        if (candidates.isEmpty()) {
            warnings.add("No runnable MCP server was found for '" + query + "'.");
            return null;
        }
        RegistrySearchResult chosen = candidates.get(0);
        if (candidates.size() > 1) {
            chosen = pickCandidate(query, text(m, "why"), candidates, warnings);
            if (chosen == null) {
                return null;
            }
        }
        Map<String, EnvBinding> env = new LinkedHashMap<>();
        if (chosen.envVars() != null) {
            for (RegistryEnvVarSpec v : chosen.envVars()) {
                if (!v.required()) {
                    continue;
                }
                if (v.secret()) {
                    String ref = secrets.declare(null, v.name(), "TENANT",
                        "Required by the " + orElse(chosen.displayName(), chosen.name()) + " MCP server"
                            + (v.description() != null ? ": " + v.description() : "."),
                        null);
                    if (ref != null) {
                        env.put(v.name(), new EnvBinding(ref));
                    }
                } else {
                    warnings.add("The " + orElse(chosen.displayName(), chosen.name()) + " MCP server also needs the setting "
                        + v.name() + ", which is not a secret - you will be asked for it when connecting.");
                }
            }
        }
        return new McpServerSpec("m" + index, chosen.name(), orElse(chosen.displayName(), chosen.name()), env);
    }

    /** Asks the model to choose ONE candidate by exact registry name; anything else is refused. */
    private RegistrySearchResult pickCandidate(
        String query, String why, List<RegistrySearchResult> candidates, List<String> warnings
    ) {
        StringBuilder list = new StringBuilder();
        for (RegistrySearchResult c : candidates) {
            list.append("- name: ").append(c.name()).append(" | ").append(orElse(c.description(), "")).append('\n');
        }
        JsonNode pick = llm.callJson("mcpServers", """
            Choose the single best MCP server for a need from the candidate list. Output ONE JSON object (no
            markdown fences): {"registryId": "<exact name from the list, or null if none fits>"}. Copy the
            name exactly; never invent one.
            """, "Need: " + query + (why != null ? " (" + why + ")" : "") + "\nCandidates:\n" + list);
        String id = text(pick, "registryId");
        if (id == null || "null".equalsIgnoreCase(id)) {
            warnings.add("None of the MCP servers found for '" + query + "' fit well enough to add.");
            return null;
        }
        for (RegistrySearchResult c : candidates) {
            if (c.name().equals(id)) {
                return c;
            }
        }
        warnings.add("The planner named an MCP server ('" + id + "') that the registry search did not return for '"
            + query + "'; it was not added.");
        return null;
    }

    ToolSpec toolOf(JsonNode t, PlannerRefs refs, PlannerSecretBook secrets, List<String> warnings) {
        String key = text(t, "key");
        String ref = refs.ref(BriefRefs.TOOL, key);
        String name = orElse(text(t, "name"), ref);
        String kind = text(t, "kind") == null ? "CODE" : text(t, "kind").toUpperCase(Locale.ROOT);
        String specUrl = text(t, "specUrl");
        String codeDescription = text(t, "codeDescription");
        if ("OPENAPI".equals(kind) && (specUrl == null || !specUrl.matches("(?i)^https?://\\S+$"))) {
            warnings.add("Tool '" + name + "' had no usable OpenAPI spec URL, so it is planned as a code tool - add the spec URL to use an API connector instead.");
            kind = "CODE";
            specUrl = null;
            codeDescription = orElse(codeDescription, name);
        }
        if ("CODE".equals(kind)) {
            codeDescription = orElse(codeDescription, name);
            specUrl = null;
        } else if (!"OPENAPI".equals(kind)) {
            warnings.add("Dropped tool '" + name + "': unknown kind '" + kind + "'.");
            refs.forget(BriefRefs.TOOL, key);
            return null;
        }
        String authType = text(t, "authType") == null ? "NONE" : text(t, "authType").toUpperCase(Locale.ROOT);
        ToolAuth auth = null;
        if (!"NONE".equals(authType) && !authType.isEmpty()) {
            String secretRef = secrets.refForKey(text(t, "secretKey"));
            if (secretRef == null) {
                secretRef = secrets.declare(null, sanitizeSecretName(name + "_CREDENTIAL"), "TENANT",
                    "Credential for the " + name + " tool.", null);
            }
            if (secretRef != null) {
                auth = new ToolAuth(authType, secretRef);
            }
        }
        return new ToolSpec(ref, kind, name, specUrl, auth, codeDescription);
    }
}
