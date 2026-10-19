package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementMode;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The writes behind {@link AgentService}'s tool allow-list, experience-mode and collaborator
 * endpoints, which share the lethal-trifecta enforcement hooks, plus the trifecta read side. Not a
 * Spring bean: {@link AgentService} builds it and forwards its optional detector/enforcement
 * setter injections, so every transaction boundary stays on the {@link AgentService} methods.
 */
final class AgentToolAccessWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentAccessService access;
    private final AgentVersioningService versioning;
    private final AgentSchemaService agentSchemaService;
    private LethalTrifectaDetector lethalTrifectaDetector;
    private TrifectaEnforcementService trifectaEnforcement;

    AgentToolAccessWriter(
            AgentAccessService access,
            AgentVersioningService versioning,
            AgentSchemaService agentSchemaService) {
        this.access = access;
        this.versioning = versioning;
        this.agentSchemaService = agentSchemaService;
    }

    void setLethalTrifectaDetector(LethalTrifectaDetector lethalTrifectaDetector) {
        this.lethalTrifectaDetector = lethalTrifectaDetector;
    }

    void setTrifectaEnforcement(TrifectaEnforcementService trifectaEnforcement) {
        this.trifectaEnforcement = trifectaEnforcement;
    }

    private void warnIfLethalTrifecta(UUID agentId, Set<String> toolNames) {
        if (lethalTrifectaDetector == null || toolNames == null || toolNames.isEmpty()) {
            return;
        }
        try {
            lethalTrifectaDetector.warnIfLethal(String.valueOf(agentId), toolNames);
        } catch (RuntimeException e) {
            log.debug("Lethal-trifecta check failed for agent {}", agentId, e);
        }
    }

    /**
     * Config-time lethal-trifecta analysis of the agent's tool allow-list. An empty allow-list
     * means "all tools" and cannot be enumerated here, so it reports no legs. Returns an empty
     * report when the detector is unavailable.
     */
    LethalTrifectaDetector.Report lethalTrifectaReport(UUID agentId) {
        Set<String> names = access.getEntity(agentId).getAllowedToolNames();
        if (lethalTrifectaDetector == null) {
            return LethalTrifectaDetector.analyseDecisions(Map.of());
        }
        return lethalTrifectaDetector.analyse(names);
    }

    /**
     * Lethal-trifecta analysis plus what enforcement does about it, for the tools tab. Needs VIEWER
     * (a denied caller gets the same 404 as a missing agent, which the console treats as "hide the
     * warning").
     */
    TrifectaStatusResponse trifectaStatus(UUID agentId) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.VIEWER);
        if (trifectaEnforcement == null) {
            LethalTrifectaDetector.Report report =
                    lethalTrifectaDetector == null
                            ? LethalTrifectaDetector.analyseDecisions(Map.of())
                            : lethalTrifectaDetector.analyse(agent.getAllowedToolNames());
            return TrifectaStatusResponse.of(report, TrifectaEnforcementMode.WARN, false, false);
        }
        return trifectaEnforcement.status(
                agentId, agent.getAllowedToolNames(), agent.getAutonomyTier());
    }

    /**
     * Save-time lethal-trifecta check for the tool mix {@code after}, delegated to {@link
     * TrifectaEnforcementService#evaluateSave}. {@code enforce=true} (user-facing saves): block
     * mode throws {@link IllegalArgumentException} (400) BEFORE the caller mutates anything. {@code
     * enforce=false} (server-side writers): never throws, a block verdict becomes the SUPERVISED
     * fallback. Returns {@code null} when enforcement is not wired.
     */
    private TrifectaEnforcementService.SaveVerdict trifectaVerdict(
            UUID agentId, Set<String> before, Set<String> after, boolean enforce) {
        if (trifectaEnforcement == null) {
            warnIfLethalTrifecta(agentId, after);
            return null;
        }
        return trifectaEnforcement.evaluateSave(agentId, before, after, enforce);
    }

    /**
     * Applies the verdict (SUPERVISED tier, cache invalidation); returns the version-history note
     * suffix.
     */
    private String applyTrifectaVerdict(
            Agent agent, TrifectaEnforcementService.SaveVerdict verdict) {
        return trifectaEnforcement == null ? "" : trifectaEnforcement.applyVerdict(agent, verdict);
    }

    /**
     * The allow-list save. {@code enforce=false} is the server-side writers' path (template install,
     * demo studio, service envoys, research runs): never rejected on lethal-trifecta policy, an
     * agent that ends up with the three legs is moved to SUPERVISED instead. {@code enforce=true} is
     * the user-facing save: in block mode a save that makes the trifecta worse is rejected (400)
     * before anything is mutated. An empty set clears the restriction ("allow all").
     */
    AgentResponse updateAllowedToolNames(
            UUID agentId, Set<String> allowedToolNames, boolean enforce) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        // Lethal-trifecta enforcement (app.tools.classification.trifecta-enforcement): advisory in
        // warn mode,
        // rejects a worsening save in block mode when enforcing (thrown here, before any mutation),
        // forces the
        // SUPERVISED autonomy tier in require-approval mode.
        TrifectaEnforcementService.SaveVerdict trifecta =
                trifectaVerdict(
                        agentId,
                        new HashSet<>(agent.getAllowedToolNames()),
                        allowedToolNames,
                        enforce);
        agent.setAllowedToolNames(allowedToolNames);
        agentSchemaService.refresh(agent);
        String forced = applyTrifectaVerdict(agent, trifecta);
        return versioning.saveVersioned(
                agent,
                (allowedToolNames == null || allowedToolNames.isEmpty()
                                ? "Cleared tool allow-list (all tools permitted)"
                                : "Changed tool allow-list (" + allowedToolNames.size() + " tools)")
                        + forced);
    }

    /**
     * Per-agent Simple/Advanced override - see {@code Agent.experienceMode}'s javadoc. Passing
     * {@code null} clears the override (back to "inherit the tenant's current default"); passing
     * {@code SIMPLE} also turns the knowledge base on and clears any tool-access restriction,
     * mirroring what {@code create} already does for brand-new Simple-mode agents - switching an
     * existing agent INTO Simple mode should behave the same as creating one that way, not leave a
     * stale manual restriction behind that Simple mode's own UI no longer shows a way to edit.
     */
    AgentResponse updateExperienceMode(
            UUID agentId, ExperienceMode experienceMode, boolean enforce) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        // Clearing the allow-list is "all tools": only analysed when trifecta-analyse-auto-select
        // is on, but
        // then it must be subject to the same enforcement as an explicit allow-list save.
        TrifectaEnforcementService.SaveVerdict trifecta =
                experienceMode == ExperienceMode.SIMPLE
                        ? trifectaVerdict(
                                agentId,
                                new HashSet<>(agent.getAllowedToolNames()),
                                Set.of(),
                                enforce)
                        : null;
        agent.setExperienceMode(experienceMode);
        if (experienceMode == ExperienceMode.SIMPLE) {
            agent.setKnowledgeBaseEnabled(true);
            agent.setAllowedToolNames(Set.of());
        }
        String forced =
                experienceMode == ExperienceMode.SIMPLE
                        ? applyTrifectaVerdict(agent, trifecta)
                        : "";
        return versioning.saveVersioned(
                agent,
                (experienceMode == null
                                ? "Cleared experience mode (inherit tenant default)"
                                : "Changed experience mode to " + experienceMode)
                        + forced);
    }

    /**
     * Empty/null clears every delegation permission. Validated here rather than left to the
     * database's own {@code CHECK (agent_id <> collaborator_agent_id)} constraint (migration V56)
     * so a bad request gets a clean 400 with an explanation instead of a raw SQL constraint
     * violation - the same "service validates, DB constraint is the backstop, not the primary error
     * path" split this codebase uses elsewhere. Every id must exist in this tenant's schema (the
     * repository only queries it, so a cross-tenant id is simply not found).
     */
    AgentResponse setCollaboratorAgentIds(UUID agentId, Set<UUID> collaboratorAgentIds) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        Set<UUID> requested = collaboratorAgentIds == null ? Set.of() : collaboratorAgentIds;
        if (requested.contains(agentId)) {
            throw new IllegalArgumentException("An agent cannot be its own collaborator");
        }
        for (UUID collaboratorId : requested) {
            // 404 if missing/cross-tenant. A newly added one must also be runnable by the caller,
            // or an editor could wire in a private agent they cannot run themselves.
            if (agent.getCollaboratorAgentIds().contains(collaboratorId)) {
                access.getEntity(collaboratorId);
            } else {
                access.requireAccess(collaboratorId, PermissionLevel.RUNNER);
            }
        }
        agent.setCollaboratorAgentIds(requested);
        return versioning.saveVersioned(
                agent,
                requested.isEmpty()
                        ? "Removed all collaborator agents"
                        : "Changed collaborator agents (" + requested.size() + ")");
    }
}
