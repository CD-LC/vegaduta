package com.agenticai.core.rl.policy;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.rl.api.RlArmConfigValidator;
import com.agenticai.core.rl.api.RlArmConflictException;
import com.agenticai.core.rl.api.RlArmNotFoundException;
import com.agenticai.core.rl.api.RlArmRejectedException;
import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.web.dto.PolicyArmRequest;
import com.agenticai.core.rl.web.dto.PolicyArmResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arm management for the admin Agent-Learning surface - the missing half of {@link PolicyStore}
 * that made the whole bandit unable to learn.
 *
 * <p><b>The bug this closes.</b> {@code MODEL_ROUTE} seeded arms only from {@code
 * rl.policy.model-route-arms}, which defaults to empty and is set nowhere; {@link
 * PolicyStore#addArm} existed but no endpoint reached it. In production every tenant's {@code
 * MODEL_ROUTE} policy had zero arms - one of them with {@code enabled = true} - so {@link
 * ContextualBanditService#selectArm} returned empty on every turn, {@code policyArmId} was always
 * null, and every piece of human feedback trained nothing. Arms are now an explicit operator action
 * through this service; the config default stays empty on purpose (an auto-seeded model route is a
 * routing decision nobody made).
 *
 * <p><b>Why creation is gated so hard.</b> A {@code MODEL_ROUTE} arm's config overrides the agent's
 * model for a turn while KEEPING the agent's own provider (see {@code
 * AgentChatService#buildChatClientFor} - the arm names a model, not a provider). An arbitrary
 * string there would break every BYOK agent whose provider does not serve that model,
 * intermittently and only for the fraction of turns the bandit happens to pick that arm. So a
 * proposed arm is validated against the read side BEFORE it is persisted, by an {@link
 * RlArmConfigValidator} that lives in the same module as the code that consumes it. See that
 * interface's javadoc for why validation is an SPI.
 *
 * <p><b>FREE plan: refused outright, not silently inert.</b> {@code DefaultRlPolicyPort#selectArm}
 * short-circuits to empty for a FREE tenant before ever consulting the bandit, on EVERY dimension -
 * so any arm a FREE tenant created would be dead on arrival. Creating one would reproduce the exact
 * failure mode this task exists to remove (configuration that looks applied and does nothing), so
 * {@link #create} rejects it with the plan's usual upgrade message. Listing and deleting stay open
 * on every plan: the UI must still be able to render, and a tenant that downgrades must still be
 * able to clean up arms it created while paid.
 */
@Service
public class PolicyArmAdminService {

    /**
     * Jackson is thread-safe for reads/writes with default config; static + shared, same posture as
     * {@code AgentChatService.RL_ARM_MAPPER} on the read side. {@code FAIL_ON_TRAILING_TOKENS}
     * makes this write-side parse STRICTER than the read side deliberately: {@code readTree}
     * otherwise accepts {@code {"model":"x"} oops} by reading the first value and ignoring the
     * rest, which would quietly store something the operator did not mean.
     */
    private static final ObjectMapper MAPPER = JsonMappers.strictTrailingTokens();

    /**
     * A soft ceiling on arms per policy. A bandit with hundreds of arms never gathers enough pulls
     * per arm to distinguish them - this is a guard against an operator (or a script) turning the
     * policy into noise, not a plan limit. Well above any realistic model-route pool.
     */
    static final int MAX_ARMS_PER_POLICY = 32;

    private final PolicyStore policyStore;
    private final TenantLimits tenantLimits;
    private final ObjectProvider<RlArmConfigValidator> armConfigValidator;

    public PolicyArmAdminService(
            PolicyStore policyStore,
            TenantLimits tenantLimits,
            ObjectProvider<RlArmConfigValidator> armConfigValidator) {
        this.policyStore = policyStore;
        this.tenantLimits = tenantLimits;
        this.armConfigValidator = armConfigValidator;
    }

    /**
     * This agent+dimension's arms, or an empty list when the policy row does not exist yet.
     * Deliberately does NOT seed the policy the way {@code RlReportingService#policyStates} does -
     * a GET stays side-effect-free (same reasoning as {@code summarize}), and "no policy yet" and
     * "policy with no arms" are the same thing to the editor anyway.
     */
    @Transactional(readOnly = true)
    public List<PolicyArmResponse> list(UUID agentId, PolicyDimension dimension) {
        return policyStore
                .findPolicy(agentId, dimension)
                .map(
                        policy ->
                                policyStore.listArms(policy.getId()).stream()
                                        .map(PolicyArmResponse::from)
                                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Validates then persists one new arm. Order matters: everything that can reject runs BEFORE
     * {@link PolicyStore#ensurePolicy}, so a rejected request never leaves a half-created policy
     * row behind for an agent id that turned out not to exist.
     */
    @Transactional
    public PolicyArmResponse create(
            UUID agentId, PolicyDimension dimension, PolicyArmRequest request) {
        requireBanditIsLiveForThisPlan();

        String armKey = requireArmKey(request.armKey());
        String contextBucket = normalizedContextBucket(request.contextBucket());
        String armConfigJson = canonicalJsonObject(request.armConfigJson());

        // Agent existence/reachability + dimension-specific semantics. Throws
        // the consuming module's own not-found for an agent id that isn't this
        // tenant's, and RlArmRejectedException for a config the runtime could
        // not safely apply.
        validatorFor(dimension).validateArm(agentId, dimension, armKey, armConfigJson);

        AgentPolicy policy = policyStore.ensurePolicy(agentId, dimension);
        if (policyStore.findArmByKey(policy.getId(), armKey).isPresent()) {
            throw new RlArmConflictException(
                    "An arm named \""
                            + armKey
                            + "\" already exists on this agent's "
                            + dimension
                            + " policy. Delete it first, or pick a different arm key.");
        }
        List<PolicyArm> existing = policyStore.listArms(policy.getId());
        if (existing.size() >= MAX_ARMS_PER_POLICY) {
            throw new RlArmRejectedException(
                    "This "
                            + dimension
                            + " policy already has "
                            + existing.size()
                            + " arms (the maximum is "
                            + MAX_ARMS_PER_POLICY
                            + "). A bandit with more arms than that never gets enough pulls per arm "
                            + "to tell them apart - remove an arm before adding another.");
        }
        return PolicyArmResponse.from(
                policyStore.createArm(policy.getId(), armKey, armConfigJson, contextBucket));
    }

    /**
     * Removes one arm. The arm must belong to the policy named in the URL - an arm id alone is not
     * authority to delete, so a {@code GEN_PARAMS} arm cannot be deleted through the {@code
     * MODEL_ROUTE} URL, and (because RL tables are schema-per-tenant) another tenant's arm id
     * resolves to nothing at all.
     */
    @Transactional
    public void delete(UUID agentId, PolicyDimension dimension, UUID armId) {
        AgentPolicy policy =
                policyStore
                        .findPolicy(agentId, dimension)
                        .orElseThrow(
                                () ->
                                        new RlArmNotFoundException(
                                                "No "
                                                        + dimension
                                                        + " policy exists for this agent, so it has no arms to delete."));
        PolicyArm arm =
                policyStore
                        .findArmById(armId)
                        .filter(candidate -> policy.getId().equals(candidate.getPolicyId()))
                        .orElseThrow(
                                () ->
                                        new RlArmNotFoundException(
                                                "No such arm on this agent's "
                                                        + dimension
                                                        + " policy."));
        policyStore.deleteArm(arm);
    }

    /**
     * The bandit is a paid capability: {@code DefaultRlPolicyPort#selectArm} returns empty for a
     * FREE tenant on every dimension, so an arm created here could never be pulled. Refused with
     * the same {@link TenantLimitExceededException} (403 + upgrade copy) the rest of the plan gates
     * use. No tenant in context - an internal/offline caller with no request thread-local - is
     * treated permissively, matching {@code DefaultRlPolicyPort} and {@code AgentService}.
     */
    private void requireBanditIsLiveForThisPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return;
        }
        if (tenantLimits.plan(tenantId) == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Agent Learning arms are a Premium feature - on the FREE plan every turn uses fixed default "
                            + "routing, so an arm added here would never be selected. Upgrade your plan to let agents "
                            + "explore.");
        }
    }

    /**
     * The validator that owns this dimension, or a no-op for dimensions with no runtime consumer
     * yet. FAILS CLOSED for {@code MODEL_ROUTE}: that is the one dimension a bad arm can actually
     * break live chat through, so if the module that applies it isn't present there is nothing that
     * can responsibly approve an arm for it.
     */
    private RlArmConfigValidator validatorFor(PolicyDimension dimension) {
        RlArmConfigValidator validator = armConfigValidator.getIfAvailable();
        if (validator != null) {
            return validator;
        }
        if (dimension == PolicyDimension.MODEL_ROUTE) {
            throw new RlArmRejectedException(
                    "MODEL_ROUTE arms cannot be validated on this deployment (the agent runtime that applies them "
                            + "is not available), and an unvalidated model route can break live chat - refusing to "
                            + "create it.");
        }
        return (agentId, dim, armKey, armConfigJson) -> {};
    }

    private static String requireArmKey(String armKey) {
        String trimmed = armKey == null ? "" : armKey.trim();
        if (trimmed.isEmpty()) {
            throw new RlArmRejectedException(
                    "armKey is required - it is how this arm is named and how rewards find it.");
        }
        if (trimmed.length() > 128) {
            throw new RlArmRejectedException("armKey must be 128 characters or fewer.");
        }
        return trimmed;
    }

    /**
     * Blank/whitespace collapses to null - the global bucket, which is what the chat path requests.
     */
    private static String normalizedContextBucket(String contextBucket) {
        if (contextBucket == null || contextBucket.isBlank()) {
            return null;
        }
        String trimmed = contextBucket.trim();
        if (trimmed.length() > 64) {
            throw new RlArmRejectedException("contextBucket must be 64 characters or fewer.");
        }
        return trimmed;
    }

    /**
     * Parses {@code armConfigJson} and re-serializes it compactly. Parsing here is what turns a
     * malformed config into a 400 with a useful message instead of a 500 (or, worse, a row that
     * silently parses to null every time the runtime reads it - {@code
     * AgentChatService#modelFromArmConfig} swallows parse failures and returns null by design, so a
     * malformed arm stored today would just be an invisible no-op that still consumes exploration
     * budget). Re-serializing also normalises whitespace and rejects trailing garbage after the
     * object.
     */
    private static String canonicalJsonObject(String armConfigJson) {
        if (armConfigJson == null || armConfigJson.isBlank()) {
            throw new RlArmRejectedException(
                    "armConfigJson is required and must be a JSON object, e.g. {\"model\":\"gpt-4o\"}.");
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(armConfigJson);
        } catch (Exception e) {
            // Never echo the raw body back - same posture as GlobalExceptionHandler's
            // HttpMessageNotReadableException handler (request bodies in this app
            // can carry secrets).
            throw new RlArmRejectedException(
                    "armConfigJson is not valid JSON. It must be a JSON object, e.g. {\"model\":\"gpt-4o\"}.");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new RlArmRejectedException(
                    "armConfigJson must be a JSON OBJECT, e.g. {\"model\":\"gpt-4o\"} - not an array, string or number.");
        }
        return parsed.toString();
    }
}
