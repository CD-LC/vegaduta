package com.agenticai.core.agents.edge;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.devices.DeviceAuthResolver;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.EdgeAgentPolicyLookup;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Edge Wave 2 (EDGE-A/EDGE-B) - the {@code core/agents}-side implementation of {@link
 * EdgeAgentPolicyLookup}, injected into {@code EdgeAgentsConfigController} (core/tenancy) via
 * {@code ObjectProvider} because the Maven dependency arrow points agents-&gt;tenancy - see the
 * interface's own javadoc for the full seam rationale.
 *
 * <p>Credential handling mirrors the safe subset of {@code EdgeToolExecutionController}'s
 * {@code withCallerSubject}, deliberately: the same two credential forms (Keycloak JWT with
 * {@code TenantContext} already populated by the filter chain, or {@code X-Device-Token}
 * resolved through {@link DeviceAuthResolver#run} which populates {@code TenantContext} itself),
 * the same {@code AnonymousAuthenticationToken} exclusion (this path is permitAll at the filter
 * chain, so an unauthenticated request still carries a non-null anonymous principal), and the
 * same RUNNER-level {@code requireAccess} anti-enumeration gate. The one difference is failure
 * shape: this is a read-only enrichment of a permitAll config endpoint, so EVERY failure
 * (missing credential, invalid device token, missing/cross-tenant/unshared agent) collapses to
 * null rather than a status code - a probe learns nothing it couldn't learn from omitting
 * {@code agentId} entirely.
 */
@Service
public class EdgeAgentPolicyLookupService implements EdgeAgentPolicyLookup {

    private static final Logger log = LoggerFactory.getLogger(EdgeAgentPolicyLookupService.class);

    /**
     * The guardrail ids the client's pattern tier ({@code web/app/lib/edge/edgeGuardrails.ts})
     * can actually represent - the regex-portable pair ported from {@code PiiGuardrail} /
     * {@code JailbreakDetectorGuardrail}. An agent whose enabled set contains ANYTHING outside
     * this set gets {@code guardrailsPatternRepresentable=false} and the client routes its
     * turns hosted (honest degradation per the EDGE-A strategy: semantic-only rules -
     * profanity/toxicity/policy_violation/custom - have no on-device equivalent yet).
     * KEEP IN SYNC with the client module's own PATTERN_REPRESENTABLE_GUARDRAILS note.
     */
    static final Set<String> PATTERN_TIER_GUARDRAIL_IDS = Set.of("pii", "jailbreak");

    private final AgentService agentService;
    private final DeviceAuthResolver deviceAuthResolver;

    public EdgeAgentPolicyLookupService(AgentService agentService, DeviceAuthResolver deviceAuthResolver) {
        this.agentService = agentService;
        this.deviceAuthResolver = deviceAuthResolver;
    }

    @Override
    public EdgeAgentPolicyView lookup(UUID agentId, Principal principal, String deviceToken) {
        if (agentId == null) {
            return null;
        }
        try {
            if (isKeycloakAuthenticated(principal)) {
                return resolve(agentId);
            }
            if (StringUtils.hasText(deviceToken)) {
                // DeviceAuthResolver populates TenantContext for the duration of
                // the callback, exactly like the edge tool endpoints' device path.
                return deviceAuthResolver.run(deviceToken, device -> resolve(agentId));
            }
            return null;
        } catch (RuntimeException e) {
            // AgentNotFoundException / ResourceAccessDeniedException /
            // DeviceException all land here - null by design (see class javadoc).
            log.debug("Edge agent policy lookup declined for agent {}: {}", agentId, e.toString());
            return null;
        }
    }

    private EdgeAgentPolicyView resolve(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        Set<String> guardrails = agent.getEnabledGuardrails();
        boolean guardrailsEnabled = !guardrails.isEmpty();
        boolean patternRepresentable = PATTERN_TIER_GUARDRAIL_IDS.containsAll(guardrails);
        EdgeRoutingPolicy policy = agent.getEdgeRoutingPolicy();
        return new EdgeAgentPolicyView(
            policy == null ? null : policy.name(),
            guardrailsEnabled,
            patternRepresentable
        );
    }

    private static boolean isKeycloakAuthenticated(Principal principal) {
        return principal != null
            && StringUtils.hasText(principal.getName())
            && !(principal instanceof AnonymousAuthenticationToken);
    }
}
