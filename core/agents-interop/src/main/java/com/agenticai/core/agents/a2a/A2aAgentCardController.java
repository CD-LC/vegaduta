package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A Agent Card discovery.
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} - the canonical (0.3+/1.0)
 *       well-known path. {@code permitAll()} in {@code SecurityConfig}: discovery
 *       necessarily precedes authentication, and the platform card carries no
 *       tenant data (see {@link A2aAgentCardService}'s javadoc for why its
 *       {@code skills} is empty).</li>
 *   <li>{@code GET /.well-known/agent.json} - the pre-0.3 legacy path, same body.
 *       Kept because several client SDKs still try it first (or only).</li>
 *   <li>{@code GET /api/agents/{agentId}/a2a/agent-card.json} - one agent's card.
 *       Falls through to the main chain's {@code anyRequest().authenticated()}
 *       and additionally requires RUNNER access on that agent, the same gate
 *       {@code AgentController#chat} applies: a card is only ever handed to a
 *       caller who could chat with the agent it describes. A denied/unknown id
 *       404s (never 403) via {@code GlobalExceptionHandler}'s existing
 *       {@code ResourceAccessDeniedException}/{@code AgentNotFoundException}
 *       mappings - same anti-enumeration posture as the rest of {@code /api/agents}.</li>
 * </ul>
 *
 * <p>Gated on {@code app.a2a.enabled} (default on): off, none of these routes
 * exist and the well-known probes 404 - see {@link A2aProperties#isEnabled()}.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@RestController
@ConditionalOnProperty(prefix = "app.a2a", name = "enabled", havingValue = "true", matchIfMissing = true)
public class A2aAgentCardController {

    static final String WELL_KNOWN_CARD = "/.well-known/agent-card.json";
    static final String WELL_KNOWN_LEGACY = "/.well-known/agent.json";
    static final String AGENT_CARD = "/api/agents/{agentId}/a2a/agent-card.json";

    static final String JWKS = "/.well-known/jwks.json";

    private final A2aAgentCardService cardService;
    private final AgentService agentService;
    private final A2aCardSigningService signingService;

    public A2aAgentCardController(A2aAgentCardService cardService, AgentService agentService,
        A2aCardSigningService signingService) {
        this.cardService = cardService;
        this.agentService = agentService;
        this.signingService = signingService;
    }

    @GetMapping(value = {WELL_KNOWN_CARD, WELL_KNOWN_LEGACY}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> platformCard(HttpServletRequest request) {
        return ResponseEntity.ok()
            // Public, cacheable for a few minutes: the card only changes on a
            // deploy, and every A2A client re-fetches it before its first call.
            .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
            .body(cardService.platformCard(cardService.resolvePublicBaseUrl(request)));
    }

    @GetMapping(value = AGENT_CARD, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentCard(@PathVariable UUID agentId, HttpServletRequest request) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        return ResponseEntity.ok()
            // Per-caller, behind auth - never let a shared cache hold it.
            .cacheControl(CacheControl.noStore())
            .body(cardService.agentCard(agent, cardService.resolvePublicBaseUrl(request)));
    }

    /**
     * {@code GET /.well-known/jwks.json} - the Ed25519 public key(s) card
     * signatures verify against. Public like the card itself: a verifier
     * resolves a signature's {@code kid} against the SAME origin that served
     * the card, so this must be reachable without auth. 404-equivalent empty
     * key set when signing is off.
     */
    @GetMapping(value = JWKS, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        // Short cache: an ephemeral dev key changes on every restart, and a
        // pinned key changes only on deliberate rotation.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.MINUTES).cachePublic())
            .body(Map.of("keys", signingService.isEnabled()
                ? java.util.List.of(signingService.publicJwk())
                : java.util.List.of()));
    }
}
