package com.agenticai.core.tutor;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tutor.dto.TutorDtos.TutorAgentResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Find-or-create of the caller's own tutor agent, so a learner can talk to a tutor about the lesson
 * they are on without setting an agent up by hand.
 *
 * <p>The tutor is an ordinary agent the caller owns ({@code createdBy} = their subject) with the
 * reserved name {@link #TUTOR_AGENT_NAME}. It is created through {@link AgentService#create} - so
 * the plan's agent ceiling, FREE-tier hosted-pool coercion and per-provider model resolution all
 * apply unchanged - then given exactly the built-in {@code tutor} and {@code generative-ui} skills
 * and tenant-shared memory (what makes {@code remember_fact} / recall work across sessions).
 *
 * <p>Teaching behaviour lives in the {@code tutor} skill, loaded on demand; the agent's own system
 * prompt is a few lines. Idempotent: a second call returns the same agent, and concurrent calls
 * from one member are serialised in-process and re-checked, so a double-click makes one agent.
 * Across replicas two can still be created at the same moment; everyone converges on the oldest and
 * the newer one is deleted by the call that made it.
 */
@Service
@ConditionalOnProperty(prefix = "app.tutor", name = "enabled", havingValue = "true")
public class TutorAgentProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TutorAgentProvisioner.class);

    public static final String TUTOR_AGENT_NAME = "Learn Anything Tutor";
    static final Set<String> TUTOR_SKILLS = Set.of("tutor", "generative-ui");
    private static final int LOCK_STRIPES = 64;

    static final String TUTOR_SYSTEM_PROMPT =
            """
        You are the learner's personal tutor on this platform. Teach one lesson at a time, in the learner's \
        own language, with patience and plain words. Use your tutor skill for how to teach, check \
        understanding and handle quizzes. When a message starts with a lesson context block, teach from it \
        and stay on that lesson. Be accurate: if you are not sure of a fact, say so rather than guess.""";

    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final SkillService skillService;
    private final TenantLimits tenantLimits;
    private final ProviderCredentialResolver credentialResolver;
    private final StudyPlanService studyPlans;

    /**
     * Fixed lock stripes keyed by member. Entries are never removed, so two waiters for one member
     * always share a monitor (a remove-on-exit map let a third caller in beside a waiter), and the
     * set cannot grow with the number of members.
     */
    private final Object[] lockStripes = newStripes();

    public TutorAgentProvisioner(
            AgentRepository agentRepository,
            AgentService agentService,
            SkillService skillService,
            TenantLimits tenantLimits,
            ProviderCredentialResolver credentialResolver,
            StudyPlanService studyPlans) {
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.skillService = skillService;
        this.tenantLimits = tenantLimits;
        this.credentialResolver = credentialResolver;
        this.studyPlans = studyPlans;
    }

    /**
     * @param principalName the caller (controller-supplied principal name)
     * @param planId optional: one of the caller's own plans to remember the tutor on (404 if not
     *     theirs)
     * @throws ResponseStatusException 409 when the workspace's plan has no agent slot left
     */
    // Deliberately NOT @Transactional: each step commits on its own, so the agent is visible to the
    // next caller before the in-process lock is released (a surrounding transaction would commit
    // after the lock and let a double-click create two). A half-finished provisioning is repaired
    // by the next call - see findOrCreate.
    public TutorAgentResponse ensureTutorAgent(String principalName, UUID planId) {
        // AgentService.create stamps createdBy from TenantContext, so the lookup must use the same
        // key.
        String owner =
                TenantContext.current()
                        .map(TenantContext::getSubject)
                        .filter(StringUtils::hasText)
                        .orElse(principalName);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String lockKey = (tenantId == null ? "-" : tenantId) + ":" + owner;
        TutorAgentResponse response;
        synchronized (lockStripes[Math.floorMod(lockKey.hashCode(), LOCK_STRIPES)]) {
            response = findOrCreate(owner, tenantId);
        }
        if (planId != null) {
            studyPlans.setTutorAgent(principalName, planId, response.agentId());
        }
        return response;
    }

    private TutorAgentResponse findOrCreate(String owner, String tenantId) {
        Optional<Agent> existing = findTutor(owner);
        if (existing.isPresent()) {
            UUID id = existing.get().getId();
            if (!skillService.getAgentSettings(id).allowedSkillNames().contains("tutor")
                    || !agentService.isShareMemoryWithTenant(id)) {
                // An earlier call created the agent but did not get as far as configuring it
                // (either step may be the one that failed).
                try {
                    configure(id);
                } catch (TenantLimitExceededException e) {
                    // EditLockedException included: a FREE workspace's agent is edit-locked after
                    // its
                    // grace window. The tutor still exists and still teaches - return it rather
                    // than
                    // failing every request with an upgrade prompt for a repair the member never
                    // asked for.
                    log.warn(
                            "Tutor agent {} could not be fully configured (skills/shared memory): {}",
                            id,
                            e.getMessage());
                }
            }
            return new TutorAgentResponse(id, false);
        }
        AgentResponse created;
        try {
            created =
                    agentService.create(
                            new AgentCreateRequest(
                                    TUTOR_AGENT_NAME,
                                    TUTOR_SYSTEM_PROMPT,
                                    resolveProviderName(tenantId),
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null));
        } catch (TenantLimitExceededException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your workspace has no room for the tutor agent: "
                            + e.getMessage()
                            + " Lessons, quizzes and reviews keep working without it.");
        }
        configure(created.id());
        log.info("Tutor agent {} provisioned for a member of tenant '{}'", created.id(), tenantId);
        // Re-query: if another replica created one at the same moment, everyone converges on the
        // oldest.
        UUID agentId = findTutor(owner).map(Agent::getId).orElse(created.id());
        if (!agentId.equals(created.id())) {
            // This call lost the race: remove its own agent so it neither lingers in the member's
            // agent list nor counts against the workspace's agent ceiling.
            try {
                agentService.delete(created.id());
            } catch (RuntimeException e) {
                log.warn("Duplicate tutor agent {} could not be removed", created.id(), e);
            }
        }
        return new TutorAgentResponse(agentId, agentId.equals(created.id()));
    }

    private static Object[] newStripes() {
        Object[] stripes = new Object[LOCK_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private void configure(UUID agentId) {
        skillService.setAgentSettings(agentId, true, TUTOR_SKILLS);
        agentService.setShareMemoryWithTenant(agentId, true);
    }

    /**
     * The caller's own agent with the reserved name; the oldest one when there is more than one.
     */
    private Optional<Agent> findTutor(String owner) {
        List<Agent> own = agentRepository.findByCreatedBy(owner);
        return own.stream()
                .filter(agent -> TUTOR_AGENT_NAME.equals(agent.getName()))
                .min(
                        Comparator.comparing(
                                        (Agent agent) ->
                                                agent.getCreatedAt() == null
                                                        ? Instant.EPOCH
                                                        : agent.getCreatedAt())
                                .thenComparing(agent -> agent.getId().toString()));
    }

    /**
     * Same resolution as the agent generator: the tenant's own active BYOK provider when it has
     * one, otherwise the platform-hosted pool ({@code AgentService#create} coerces FREE to it
     * regardless).
     */
    private ProviderName resolveProviderName(String tenantId) {
        if (tenantId == null || tenantLimits.plan(tenantId) == TenantPlan.FREE) {
            return ProviderName.PLATFORM_HOSTED;
        }
        try {
            return credentialResolver.resolveAnyActiveKey().providerName();
        } catch (RuntimeException e) {
            return ProviderName.PLATFORM_HOSTED;
        }
    }
}
