package com.agenticai.core.agents.demo.studio;

import static com.agenticai.core.agents.demo.studio.DemoStudioService.CATALOG_NAMES;
import static com.agenticai.core.agents.demo.studio.DemoStudioService.ENFORCED_GUARDRAILS;
import static com.agenticai.core.agents.demo.studio.DemoStudioService.LIVE_AUTONOMY;
import static com.agenticai.core.agents.demo.studio.DemoStudioService.NO_TOOLS_SENTINEL;
import static com.agenticai.core.agents.demo.studio.DemoStudioService.TOOL_CATALOG;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.demo.studio.DemoStudioService.AgentView;
import com.agenticai.core.agents.demo.studio.DemoStudioService.GuardrailOption;
import com.agenticai.core.agents.demo.studio.DemoStudioService.Palette;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.agents.demo.studio.DemoStudioService.ToolAvailability;
import com.agenticai.core.agents.demo.studio.DemoStudioService.ToolOption;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless halves of {@link DemoStudioService}, split out of it to keep it under the file-size cap:
 * the palette, the wire view of an agent, the per-sandbox and per-address agent caps, and the
 * parsing and clamping of visitor input. Same rules as before the split; the service delegates.
 */
final class DemoStudioSupport {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioService.class);

    private static final int MAX_MISSION_PROMPT_CHARS = 4000;
    private static final int MAX_NAME_CHARS = 60;

    private DemoStudioSupport() {}

    static Palette palette(
            DemoSession session,
            UUID probeAgentId,
            DemoSessionService demoSessionService,
            AgentToolPort toolProvider,
            AgentGuardrailService guardrailService,
            int maxAgentsPerSession,
            DemoStudioDocumentService.DocumentLimits documentLimits,
            boolean connectorsEnabled,
            int missionTimeoutSeconds) {
        List<ToolAvailability> tools = new ArrayList<>();
        Set<String> resolvable = new HashSet<>();
        if (probeAgentId != null) {
            try {
                resolvable.addAll(
                        demoSessionService.inDemoTenantContext(
                                session,
                                () ->
                                        toolProvider.listAvailableToolsFor(probeAgentId).stream()
                                                .map(ToolDescriptor::name)
                                                .toList()));
            } catch (RuntimeException e) {
                log.warn(
                        "Tool discovery for studio palette failed (sandbox {}) - offering nothing as available",
                        session.getId(),
                        e);
            }
        }
        for (ToolOption option : TOOL_CATALOG) {
            tools.add(
                    new ToolAvailability(
                            option.name(),
                            option.label(),
                            option.description(),
                            option.group(),
                            resolvable.contains(option.name())));
        }
        List<GuardrailOption> guardrails = new ArrayList<>();
        Set<String> available = new HashSet<>();
        try {
            available.addAll(
                    demoSessionService.inDemoTenantContext(
                            session,
                            () ->
                                    guardrailService.getAvailableGuardrailsForPlan().stream()
                                            .map(ContentGuardrailType::getId)
                                            .toList()));
        } catch (RuntimeException e) {
            log.warn("Guardrail availability lookup failed for sandbox {}", session.getId(), e);
        }
        for (ContentGuardrailType type : ContentGuardrailType.values()) {
            if ("custom".equals(type.getId())) {
                continue; // needs the workspace Guardrails screen - meaningless in a sandbox
            }
            guardrails.add(new GuardrailOption(type.getId(), available.contains(type.getId())));
        }
        return new Palette(
                tools,
                guardrails,
                List.of(
                        ReasoningDepth.FAST.name(),
                        ReasoningDepth.CAREFUL.name(),
                        ReasoningDepth.RIGOROUS.name(),
                        ReasoningDepth.PLANNED.name()),
                LIVE_AUTONOMY.stream().map(Enum::name).sorted().toList(),
                List.of(AgentAutonomyTier.SUPERVISED.name()),
                maxAgentsPerSession,
                documentLimits,
                connectorsEnabled,
                List.copyOf(ENFORCED_GUARDRAILS.stream().filter(available::contains).toList()),
                missionTimeoutSeconds);
    }

    static void enforceCreateCaps(
            DemoSession session,
            int mine,
            DemoSessionService demoSessionService,
            AgentRepository agentRepository,
            int maxAgentsPerSession,
            int maxAgentsPerIp) {
        // The per-address cap is an abuse limit (one address cannot flood the shared tenant with
        // sandboxes'
        // worth of agents); it counts neighbours' agents but never exposes them.
        int group = mine;
        for (DemoSession other : demoSessionService.liveSessionsSharingIp(session, Instant.now())) {
            if (!other.getId().equals(session.getId())) {
                String subject = DemoSessionService.actorSubject(other.getId());
                group +=
                        demoSessionService
                                .inDemoTenantContext(
                                        session, () -> agentRepository.findByCreatedBy(subject))
                                .size();
            }
        }
        if (mine >= maxAgentsPerSession) {
            throw new StudioException(
                    "agent_cap",
                    429,
                    "This sandbox can hold "
                            + maxAgentsPerSession
                            + " agents. Delete one, or create a free account for more.");
        }
        if (group >= maxAgentsPerIp) {
            throw new StudioException(
                    "agent_cap",
                    429,
                    "Your network already has "
                            + maxAgentsPerIp
                            + " sandbox agents. Delete one, or create a free account for more.");
        }
    }

    static AgentView toView(Agent agent, DemoSession session) {
        Set<String> allowed =
                agent.getAllowedToolNames() == null ? Set.of() : agent.getAllowedToolNames();
        boolean toolsOff = allowed.contains(NO_TOOLS_SENTINEL);
        // An EMPTY allow-list is "no restriction" in AgentService - the seeded
        // sandbox agent and every SIMPLE-mode agent start that way. Report it
        // as the whole catalog, because that is what the agent can actually
        // reach; showing "0 tools" for an agent that has all of them would be
        // the page lying about the one thing it exists to show.
        List<String> tools =
                toolsOff
                        ? List.of()
                        : allowed.isEmpty()
                                ? TOOL_CATALOG.stream().map(ToolOption::name).toList()
                                : allowed.stream()
                                        .filter(
                                                t ->
                                                        CATALOG_NAMES.contains(t)
                                                                || DemoStudioConnectorService
                                                                        .isConnectorTool(
                                                                                agent.getId(), t))
                                        .sorted()
                                        .toList();
        String creator = agent.getCreatedBy();
        String createdBySession =
                DemoSessionService.sessionIdFromSubject(creator).map(UUID::toString).orElse(null);
        return new AgentView(
                agent.getId(),
                agent.getName(),
                agent.getSystemPrompt(),
                agent.getReasoningDepth() == null
                        ? ReasoningDepth.FAST.name()
                        : agent.getReasoningDepth().name(),
                agent.getAutonomyTier() == null
                        ? AgentAutonomyTier.SEMI_AUTONOMOUS.name()
                        : agent.getAutonomyTier().name(),
                tools,
                toolsOff,
                agent.getEnabledGuardrails() == null
                        ? List.of()
                        : agent.getEnabledGuardrails().stream().sorted().toList(),
                agent.getTemperature(),
                agent.getMaxTokens(),
                true, // "mine": kept for the wire contract - a sandbox only ever sees its own
                // agents
                agent.getId().equals(session.getAgentId()),
                createdBySession,
                agent.getCreatedAt(),
                agent.getUpdatedAt());
    }

    /**
     * Catalog tools, plus - when an agent id is given - that agent's OWN MCP connector tools and
     * nobody else's.
     */
    static Set<String> toolSetFor(Collection<String> requested, UUID agentId) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : requested) {
            if (name == null) {
                continue;
            }
            String n = name.trim();
            if (CATALOG_NAMES.contains(n)
                    || (agentId != null
                            && DemoStudioConnectorService.isConnectorTool(agentId, n))) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            out.add(NO_TOOLS_SENTINEL);
        }
        return out;
    }

    static Set<String> guardrailSetFor(Collection<String> requested) {
        Set<String> valid =
                java.util.Arrays.stream(ContentGuardrailType.values())
                        .map(ContentGuardrailType::getId)
                        .collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        for (String id : requested) {
            if (id != null
                    && valid.contains(id.trim().toLowerCase(Locale.ROOT))
                    && !"custom".equals(id.trim().toLowerCase(Locale.ROOT))) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static ReasoningDepth parseDepth(String raw, ReasoningDepth fallback) {
        if (raw == null || raw.isBlank()) {
            if (fallback == null) {
                throw new StudioException("invalid", 400, "Unknown reasoning depth.");
            }
            return fallback;
        }
        try {
            return ReasoningDepth.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new StudioException("invalid", 400, "Unknown reasoning depth: " + raw);
        }
    }

    static AgentAutonomyTier parseAutonomy(String raw) {
        try {
            return AgentAutonomyTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new StudioException("invalid", 400, "Unknown autonomy tier: " + raw);
        }
    }

    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[^\\p{L}\\p{N} '\\-&.]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_NAME_CHARS ? cleaned.substring(0, MAX_NAME_CHARS) : cleaned;
    }

    static String cleanMission(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_MISSION_PROMPT_CHARS
                ? cleaned.substring(0, MAX_MISSION_PROMPT_CHARS)
                : cleaned;
    }

    static double clampTemperature(double t) {
        return Math.max(0.0, Math.min(1.5, t));
    }

    static int clampTokens(int n) {
        return Math.max(128, Math.min(4096, n));
    }
}
