package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.core.Agent;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Builds the two Agent Cards this server publishes: the PLATFORM card at
 * {@code /.well-known/agent-card.json} (what an external agent discovers
 * first - "VegaDuta speaks A2A, here is the endpoint and the auth scheme, no
 * skills at this level") and a PER-AGENT card at
 * {@code /api/agents/{agentId}/a2a/agent-card.json} (one skill, id = the
 * agent id, whose {@code url} is the agent-scoped JSON-RPC route so a client
 * that discovered THAT card never has to pass {@code metadata.agentId}).
 *
 * <p>Why skills are empty on the platform card: the well-known path is
 * unauthenticated by necessity (discovery precedes auth), and every agent on
 * this platform belongs to a tenant. Listing agents there would leak tenant
 * data to anyone on the internet; listing "the caller's agents" is impossible
 * before the caller has authenticated. The per-agent card is the honest
 * middle: it sits behind the same RUNNER access check as chatting with that
 * agent, so whoever can fetch it can also use it.
 *
 * <p>Both cards carry the 0.3 fields ({@code url}, {@code preferredTransport})
 * AND the 1.0 ones ({@code supportedInterfaces}) so a client of either
 * generation finds what it parses - the two are non-conflicting and cheap.
 * {@code securitySchemes}/{@code security} advertise exactly what the endpoint
 * enforces (a bearer JWT via the main Spring Security chain) and nothing more.
 */
@Service
public class A2aAgentCardService {

    static final String PLATFORM_NAME = "VegaDuta";
    static final String PROTOCOL_VERSION = "1.0";
    static final String PROVIDER_URL = "https://github.com/CD-LC/vegaduta";
    static final String PLATFORM_JSONRPC_PATH = "/api/a2a";
    static final String DEVELOPMENT_VERSION = "0.1.0-dev";
    private static final int DESCRIPTION_MAX_CHARS = 240;

    private final A2aProperties properties;
    private final A2aCardSigningService signingService;

    public A2aAgentCardService(A2aProperties properties, A2aCardSigningService signingService) {
        this.properties = properties;
        this.signingService = signingService;
    }

    public Map<String, Object> platformCard(String publicBaseUrl) {
        String endpoint = publicBaseUrl + PLATFORM_JSONRPC_PATH;
        return card(
            PLATFORM_NAME,
            "VegaDuta agentic AI platform. Authenticate with a bearer token, then send messages to a specific agent "
                + "by setting metadata.agentId - or discover one agent's own card at "
                + "/api/agents/{agentId}/a2a/agent-card.json and use its agent-scoped endpoint.",
            endpoint,
            List.of()
        );
    }

    public Map<String, Object> agentCard(Agent agent, String publicBaseUrl) {
        String endpoint = publicBaseUrl + "/api/agents/" + agent.getId() + "/a2a";
        String description = describe(agent);
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("id", agent.getId().toString());
        skill.put("name", agent.getName());
        skill.put("description", description);
        skill.put("tags", List.of("chat"));
        skill.put("examples", List.of());
        skill.put("inputModes", List.of("text/plain"));
        skill.put("outputModes", List.of("text/plain"));
        return card(PLATFORM_NAME + " - " + agent.getName(), description, endpoint, List.of(skill));
    }

    private Map<String, Object> card(String name, String description, String endpoint, List<Object> skills) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", name);
        card.put("description", description);
        card.put("version", resolveVersion());
        card.put("protocolVersion", PROTOCOL_VERSION);
        // 0.3 clients read url/preferredTransport; 1.0 clients read supportedInterfaces.
        card.put("url", endpoint);
        card.put("preferredTransport", "JSONRPC");
        // JSON-RPC stays first (the preferred interface, and what 0.3 clients read);
        // the HTTP+JSON/REST binding (spec section 11) lives under <endpoint>/rest.
        card.put("supportedInterfaces", List.of(
            Map.of("url", endpoint, "protocolBinding", "JSONRPC", "protocolVersion", PROTOCOL_VERSION),
            Map.of("url", endpoint + "/rest", "protocolBinding", "HTTP+JSON", "protocolVersion", PROTOCOL_VERSION)
        ));
        card.put("provider", Map.of("organization", PLATFORM_NAME, "url", PROVIDER_URL));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("streaming", true);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", false);
        capabilities.put("extendedAgentCard", false);
        card.put("capabilities", capabilities);
        card.put("defaultInputModes", List.of("text/plain"));
        card.put("defaultOutputModes", List.of("text/plain"));
        card.put("securitySchemes", Map.of("bearer", Map.of(
            "type", "http",
            "scheme", "bearer",
            "bearerFormat", "JWT"
        )));
        card.put("security", List.of(Map.of("bearer", List.of())));
        card.put("skills", skills);
        // JWS signatures go on LAST: they cover the card exactly as built
        // above, minus this field itself (see A2aCardSigningService).
        List<Map<String, Object>> signatures = signingService.sign(card);
        if (!signatures.isEmpty()) {
            card.put("signatures", signatures);
        }
        return card;
    }

    /**
     * {@code app.a2a.public-base-url} when set, else the origin of the current
     * request. {@code fromContextPath} honors {@code X-Forwarded-*} because
     * {@code server.forward-headers-strategy=native} is on (application.yml),
     * so behind Caddy this yields the public https origin, not the container's.
     */
    public String resolvePublicBaseUrl(HttpServletRequest request) {
        return A2aPublicBaseUrl.resolve(properties, request);
    }

    String resolveVersion() {
        if (StringUtils.hasText(properties.getVersion())) {
            return properties.getVersion();
        }
        Package pkg = A2aAgentCardService.class.getPackage();
        String manifest = pkg == null ? null : pkg.getImplementationVersion();
        return StringUtils.hasText(manifest) ? manifest : DEVELOPMENT_VERSION;
    }

    /**
     * Agents have no separate description column; the system prompt IS the
     * persona. A card description is a one-line summary for a directory
     * listing, so: first paragraph, whitespace-collapsed, capped - never the
     * whole prompt (which can be pages long and may contain instructions the
     * tenant considers proprietary).
     */
    static String describe(Agent agent) {
        String prompt = agent.getSystemPrompt();
        if (!StringUtils.hasText(prompt)) {
            return "VegaDuta agent \"" + agent.getName() + "\".";
        }
        String firstParagraph = prompt.strip().split("\\R\\s*\\R", 2)[0];
        String collapsed = firstParagraph.replaceAll("\\s+", " ").strip();
        if (collapsed.length() <= DESCRIPTION_MAX_CHARS) {
            return collapsed;
        }
        int cut = collapsed.lastIndexOf(' ', DESCRIPTION_MAX_CHARS - 1);
        return collapsed.substring(0, cut > 40 ? cut : DESCRIPTION_MAX_CHARS - 1).strip() + "...";
    }
}
