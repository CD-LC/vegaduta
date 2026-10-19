package com.agenticai.core.agents.trust;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.core.Agent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Builds the signed <b>agent capability manifest</b> - the Know-Your-Agent
 * document a relying party consumes before delegating work or money to one of
 * our agents. It answers "what can this agent do, on which endpoints, under
 * whose signature" without the caller needing an authenticated session.
 *
 * <p>Wire shape mirrors the A2A card's detached-JWS {@code signatures} field
 * ({@link A2aCardSigningService#sign}) over the same operator key - one JWKS
 * ({@code /.well-known/jwks.json}) verifies cards, manifests and delegation
 * tokens alike.
 *
 * <p>Capability claims are derived from the agent's actual configuration
 * (tool allow-list, opt-in feature flags) - the manifest asserts nothing the
 * agent cannot do, which is what makes it evidence rather than marketing.
 * An empty {@code allowedToolNames} means "unrestricted" per {@code Agent}'s
 * own contract and is rendered as {@code tools: {"mode":"unrestricted"}} so a
 * reader cannot confuse it with "no tools".
 */
@Service
public class CapabilityManifestService {

    public static final String MANIFEST_VERSION = "vegaduta-agent-manifest/1";

    private final ObjectProvider<A2aCardSigningService> signingService;

    public CapabilityManifestService(ObjectProvider<A2aCardSigningService> signingService) {
        this.signingService = signingService;
    }

    /**
     * The manifest for {@code agent}, signed when signing is enabled.
     * {@code publicBaseUrl} is the externally reachable API origin (see
     * {@code A2aAgentCardService#resolvePublicBaseUrl}).
     */
    public Map<String, Object> manifest(Agent agent, String publicBaseUrl) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifest_version", MANIFEST_VERSION);
        manifest.put("issued_at", Instant.now().toString());
        manifest.put("agent_platform", "vegaduta");

        Map<String, Object> agentBlock = new LinkedHashMap<>();
        agentBlock.put("id", agent.getId().toString());
        agentBlock.put("name", agent.getName());
        agentBlock.put("autonomy_tier", agent.getAutonomyTier().name());
        manifest.put("agent", agentBlock);

        manifest.put("capabilities", capabilities(agent));

        Map<String, Object> endpoints = new LinkedHashMap<>();
        if (StringUtils.hasText(publicBaseUrl)) {
            String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
            endpoints.put("a2a_card", base + "/api/agents/" + agent.getId() + "/a2a/agent-card.json");
            endpoints.put("a2a_jsonrpc", base + "/api/agents/" + agent.getId() + "/a2a");
            endpoints.put("jwks", base + "/.well-known/jwks.json");
            endpoints.put("delegation", base + "/api/agents/" + agent.getId() + "/delegations");
            endpoints.put("directory", base + "/api/directory/agents/" + agent.getId());
        }
        manifest.put("endpoints", endpoints);

        A2aCardSigningService signer = signingService.getIfAvailable();
        if (signer != null && signer.isEnabled()) {
            manifest.put("signatures", signer.sign(manifest));
        }
        return manifest;
    }

    private Map<String, Object> capabilities(Agent agent) {
        Map<String, Object> caps = new LinkedHashMap<>();
        if (agent.getAllowedToolNames().isEmpty()) {
            caps.put("tools", Map.of("mode", "unrestricted"));
        } else {
            List<String> tools = new ArrayList<>(agent.getAllowedToolNames());
            java.util.Collections.sort(tools);
            caps.put("tools", Map.of("mode", "allowlist", "names", tools));
        }
        List<String> protocols = new ArrayList<>();
        protocols.add("a2a/1.0");
        protocols.add("delegation/1.0");
        if (agent.isE2bEnabled()) {
            protocols.add("code-execution/e2b");
        }
        caps.put("protocols", protocols);
        return caps;
    }
}
