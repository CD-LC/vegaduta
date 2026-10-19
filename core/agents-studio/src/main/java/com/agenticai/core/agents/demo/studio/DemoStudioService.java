package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * /try Agent Studio - the no-signup surface where a visitor designs REAL agents in the shared demo
 * tenant and watches them work.
 *
 * <p><b>What is real here.</b> Every agent is an ordinary {@code agent} row in the demo schema
 * created through {@link AgentService#create}; every control on the page (mission prompt, reasoning
 * depth, autonomy tier, tool allow-list, guardrails, temperature, response length) writes through
 * the same {@link AgentService} setters the authenticated workbench uses; every mission run is a
 * real {@link AgentChatService#chat} turn with the real {@link AgentStepEventEmitter} tool trace.
 * Nothing in this class simulates.
 *
 * <p><b>Visibility is per sandbox - own agents only.</b> The demo tenant is one schema for every
 * visitor, so isolation is enforced here: a session sees, edits, runs and deletes an agent only
 * when the agent's {@code created_by} subject ({@code demo:<session id>}) is its OWN. Anything else
 * - another sandbox on the same public address included - behaves exactly like "no such agent"
 * (404, same message), so ids cannot be probed. Mutations still run under the owner's subject
 * because {@code ResourceAccessService} only recognises the creating subject as the owner - see
 * {@link DemoSessionService#inDemoTenantContextAs}; here that is always the caller's own subject.
 *
 * <p><b>Moderation.</b> Every piece of visitor text entering this service passes {@link
 * DemoStudioModerationService#check} before anything is persisted or sent to a model.
 *
 * <p><b>Caps.</b> The shared tenant is exempt from the per-schema plan caps (see {@code
 * TenantLimitsServiceImpl}); this class enforces per-session and per-IP agent counts instead (the
 * per-IP count is an abuse cap, not a visibility rule), and every mission spends one message of the
 * sandbox's existing allowance.
 */
@Service
public class DemoStudioService {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioService.class);

    /**
     * Sentinel stored in {@code allowedToolNames} when the visitor switches every tool off. An
     * EMPTY allow-list means "no restriction, allow all" ({@link
     * AgentService#setAllowedToolNames}), so "no tools" needs a name that matches nothing. This is
     * the "chatbot mode" toggle - the single most instructive control on the page, because the
     * visitor can run the same mission with and without hands and see the difference.
     */
    public static final String NO_TOOLS_SENTINEL = "studio:none";

    /**
     * The tools a sandbox agent may be given, in display order. Read-only or self-contained
     * capabilities only: nothing that sends, pays, posts or touches a channel. Availability is NOT
     * assumed - each one is only offered when {@code AgentToolProvider#listAvailableToolsFor}
     * actually resolves it in this deployment (web_search needs a platform Brave key, run_code an
     * E2B credential, ...).
     */
    static final List<ToolOption> TOOL_CATALOG =
            List.of(
                    new ToolOption(
                            "web_search",
                            "Search the web",
                            "Live search results for a query.",
                            "hands"),
                    new ToolOption(
                            "fetch_web_page",
                            "Read a web page",
                            "Fetches a URL and reads its text.",
                            "hands"),
                    new ToolOption(
                            "http_request",
                            "Call an API",
                            "GET/POST any public JSON API.",
                            "hands"),
                    new ToolOption(
                            "run_code",
                            "Run code",
                            "Executes Python in a sandbox for maths, parsing and data work.",
                            "hands"),
                    new ToolOption(
                            "remember_fact",
                            "Remember",
                            "Keeps facts across missions for this agent.",
                            "memory"),
                    new ToolOption(
                            "recall_related_facts",
                            "Recall",
                            "Looks up what it remembered earlier.",
                            "memory"),
                    new ToolOption(
                            "search_knowledge_base",
                            "Search knowledge",
                            "Searches documents attached to the agent.",
                            "memory"));

    /**
     * Switched on for every sandbox agent and not removable: an anonymous, unlimited sandbox must
     * not be a way to get a hosted model to help with illegal or abusive requests, nor to have it
     * answer abusively ({@code profanity}, {@code toxicity} screen the agent's output). Applied
     * only where the plan offers them.
     */
    static final List<String> ENFORCED_GUARDRAILS =
            List.of("policy_violation", "jailbreak", "profanity", "toxicity");

    static final Set<String> CATALOG_NAMES =
            TOOL_CATALOG.stream().map(ToolOption::name).collect(Collectors.toSet());

    /**
     * Autonomy tiers the sandbox can run live. SUPERVISED routes every tool call through the
     * approvals inbox, which needs an account.
     */
    static final Set<AgentAutonomyTier> LIVE_AUTONOMY =
            Set.of(AgentAutonomyTier.SEMI_AUTONOMOUS, AgentAutonomyTier.FULLY_AUTONOMOUS);

    private static final Duration STEP_HEARTBEAT = Duration.ofSeconds(15);
    private static final int MAX_MISSION_CHARS = 2000;

    private final DemoSessionService demoSessionService;
    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentGuardrailService guardrailService;
    private final AgentToolPort toolProvider;
    private final AgentChatService agentChatService;
    private final int maxAgentsPerSession;
    private final int maxAgentsPerIp;
    private final org.springframework.beans.factory.ObjectProvider<DemoStudioDocumentService>
            documentService;
    private final org.springframework.beans.factory.ObjectProvider<DemoStudioConnectorService>
            connectorService;
    private final boolean connectorsEnabled;
    private final Duration missionTimeout;
    private final DemoStudioModerationService moderation;

    public DemoStudioService(
            DemoSessionService demoSessionService,
            AgentService agentService,
            AgentRepository agentRepository,
            AgentGuardrailService guardrailService,
            AgentToolPort toolProvider,
            AgentChatService agentChatService,
            @Value("${app.demo.studio.max-agents-per-session:12}") int maxAgentsPerSession,
            @Value("${app.demo.studio.max-agents-per-ip:40}") int maxAgentsPerIp,
            org.springframework.beans.factory.ObjectProvider<DemoStudioDocumentService>
                    documentService,
            org.springframework.beans.factory.ObjectProvider<DemoStudioConnectorService>
                    connectorService,
            @Value("${app.demo.studio.mcp-connectors-enabled:false}") boolean connectorsEnabled,
            @Value("${app.demo.studio.mission-timeout-seconds:120}") int missionTimeoutSeconds,
            DemoStudioModerationService moderation) {
        this.demoSessionService = demoSessionService;
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.guardrailService = guardrailService;
        this.toolProvider = toolProvider;
        this.agentChatService = agentChatService;
        this.maxAgentsPerSession = maxAgentsPerSession;
        this.maxAgentsPerIp = maxAgentsPerIp;
        this.documentService = documentService;
        this.connectorService = connectorService;
        this.connectorsEnabled = connectorsEnabled;
        this.missionTimeout = Duration.ofSeconds(Math.max(15, missionTimeoutSeconds));
        this.moderation = moderation;
    }

    /**
     * Hard wall-clock cap on one agent turn, in seconds - shown on the page so the limit is never a
     * surprise.
     */
    public int missionTimeoutSeconds() {
        return (int) missionTimeout.toSeconds();
    }

    // ------------------------------------------------------------------
    // Wire shapes
    // ------------------------------------------------------------------

    public record ToolOption(String name, String label, String description, String group) {}

    /**
     * One entry of the tool palette: the catalog entry plus whether THIS deployment can actually
     * offer it.
     */
    public record ToolAvailability(
            String name, String label, String description, String group, boolean available) {}

    public record GuardrailOption(String id, boolean available) {}

    /** The palette the designer renders: what can be switched on, honestly labelled. */
    public record Palette(
            List<ToolAvailability> tools,
            List<GuardrailOption> guardrails,
            List<String> reasoningDepths,
            List<String> autonomyTiers,
            List<String> lockedAutonomyTiers,
            int maxAgentsPerSession,
            DemoStudioDocumentService.DocumentLimits documents,
            boolean connectorsEnabled,
            List<String> enforcedGuardrails,
            int missionTimeoutSeconds) {}

    public record AgentView(
            UUID id,
            String name,
            String mission,
            String reasoningDepth,
            String autonomy,
            List<String> tools,
            boolean toolsOff,
            List<String> guardrails,
            double temperature,
            int maxTokens,
            boolean mine,
            boolean live,
            String createdBySession,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * Every field optional: null means "leave as is". Tools: an empty list means "no tools"
     * (chatbot mode).
     */
    public record AgentUpdate(
            String name,
            String mission,
            String reasoningDepth,
            String autonomy,
            List<String> tools,
            List<String> guardrails,
            Double temperature,
            Integer maxTokens) {}

    /**
     * Thrown for every visitor-facing refusal; the controller maps it to a JSON {code, message}.
     */
    public static class StudioException extends RuntimeException {
        private final String code;
        private final int status;

        public StudioException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    // ------------------------------------------------------------------
    // Visibility - a sandbox sees only what it created itself
    // ------------------------------------------------------------------

    /**
     * This sandbox's own agents, oldest first. Nothing else is ever listed - not even from the same
     * network address.
     */
    public List<AgentView> listAgents(DemoSession session) {
        List<Agent> agents = new ArrayList<>(ownAgents(session));
        agents.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return agents.stream().map(a -> DemoStudioSupport.toView(a, session)).toList();
    }

    private List<Agent> ownAgents(DemoSession session) {
        String subject = DemoSessionService.actorSubject(session.getId());
        return demoSessionService.inDemoTenantContext(
                session, () -> agentRepository.findByCreatedBy(subject));
    }

    /**
     * Loads an agent the caller OWNS. Any other agent - another sandbox's, even one on the same
     * network address - is a 404 with the same message as an id that does not exist, so ids cannot
     * be probed (same anti-enumeration posture as the console). {@link Owned#creator()} is always
     * the caller.
     */
    Owned requireVisible(DemoSession session, UUID agentId) {
        String mySubject = DemoSessionService.actorSubject(session.getId());
        Optional<Agent> found =
                demoSessionService.inDemoTenantContext(
                        session, () -> agentRepository.findById(agentId));
        if (found.isEmpty() || !mySubject.equals(found.get().getCreatedBy())) {
            throw new StudioException("not_found", 404, "That agent isn't in your studio.");
        }
        return new Owned(found.get(), session);
    }

    record Owned(Agent agent, DemoSession creator) {}

    public AgentView getAgent(DemoSession session, UUID agentId) {
        return DemoStudioSupport.toView(requireVisible(session, agentId).agent(), session);
    }

    // ------------------------------------------------------------------
    // Palette
    // ------------------------------------------------------------------

    public Palette palette(DemoSession session, UUID probeAgentId) {
        return DemoStudioSupport.palette(
                session,
                probeAgentId,
                demoSessionService,
                toolProvider,
                guardrailService,
                maxAgentsPerSession,
                documentService.getObject().limits(),
                connectorsEnabled,
                missionTimeoutSeconds());
    }

    // ------------------------------------------------------------------
    // Create / update / delete
    // ------------------------------------------------------------------

    public AgentView createAgent(DemoSession session, AgentUpdate spec) {
        String name = DemoStudioSupport.cleanName(spec.name());
        if (name == null) {
            throw new StudioException("invalid", 400, "Give the agent a name.");
        }
        String mission = DemoStudioSupport.cleanMission(spec.mission());
        if (mission == null) {
            throw new StudioException("invalid", 400, "Describe the agent's mission.");
        }
        moderation.check(session, Map.of("agent name", name, "agent mission", mission));
        DemoStudioSupport.enforceCreateCaps(
                session,
                ownAgents(session).size(),
                demoSessionService,
                agentRepository,
                maxAgentsPerSession,
                maxAgentsPerIp);

        AgentResponse created =
                demoSessionService.inDemoTenantContext(
                        session,
                        () ->
                                agentService.create(
                                        new AgentCreateRequest(
                                                name,
                                                mission,
                                                ProviderName.PLATFORM_HOSTED,
                                                null,
                                                spec.maxTokens() == null
                                                        ? Integer.valueOf(1024)
                                                        : Integer.valueOf(
                                                                DemoStudioSupport.clampTokens(
                                                                        spec.maxTokens())),
                                                spec.temperature() == null
                                                        ? Double.valueOf(0.4)
                                                        : Double.valueOf(
                                                                DemoStudioSupport.clampTemperature(
                                                                        spec.temperature())),
                                                Double.valueOf(0.95),
                                                Boolean.FALSE,
                                                // ADVANCED + MANUAL on purpose: SIMPLE would force
                                                // the knowledge
                                                // base on and wipe the tool allow-list on every
                                                // save, and
                                                // TuningMode.SIMPLE would overwrite the visitor's
                                                // temperature.
                                                // The whole point of this page is that these knobs
                                                // are theirs.
                                                ExperienceMode.ADVANCED,
                                                DemoStudioSupport.parseDepth(
                                                        spec.reasoningDepth(),
                                                        ReasoningDepth.CAREFUL),
                                                TuningMode.MANUAL,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)));

        AgentUpdate rest =
                new AgentUpdate(
                        null,
                        null,
                        null,
                        spec.autonomy(),
                        spec.tools(),
                        spec.guardrails() == null ? List.of() : spec.guardrails(),
                        null,
                        null);
        return updateAgent(session, created.id(), rest);
    }

    public AgentView updateAgent(DemoSession session, UUID agentId, AgentUpdate spec) {
        Owned owned = requireVisible(session, agentId);
        Map<String, String> visitorText = new LinkedHashMap<>();
        visitorText.put("agent name", spec.name());
        visitorText.put("agent mission", spec.mission());
        moderation.check(session, visitorText);
        UUID actor = owned.creator().getId();
        demoSessionService.inDemoTenantContextAs(
                session,
                actor,
                () -> {
                    // The seeded agent is ExperienceMode.SIMPLE + TuningMode.SIMPLE:
                    // SIMPLE wipes tool allow-lists and re-infers sampling on its own
                    // triggers. The studio's whole point is that these are the
                    // visitor's decisions, so the first explicit edit of either kind
                    // moves the agent to the manual modes first.
                    Agent before = owned.agent();
                    if (spec.tools() != null
                            && before.getExperienceMode() == ExperienceMode.SIMPLE) {
                        agentService.setExperienceMode(agentId, ExperienceMode.ADVANCED);
                    }
                    if ((spec.temperature() != null || spec.maxTokens() != null)
                            && before.getTuningMode() == TuningMode.SIMPLE) {
                        agentService.setTuningMode(agentId, TuningMode.MANUAL);
                    }
                    if (spec.mission() != null) {
                        String mission = DemoStudioSupport.cleanMission(spec.mission());
                        if (mission == null) {
                            throw new StudioException(
                                    "invalid", 400, "The mission can't be empty.");
                        }
                        agentService.updateSystemPrompt(agentId, mission);
                    }
                    if (spec.reasoningDepth() != null) {
                        agentService.setReasoningDepth(
                                agentId, DemoStudioSupport.parseDepth(spec.reasoningDepth(), null));
                    }
                    if (spec.autonomy() != null) {
                        AgentAutonomyTier tier = DemoStudioSupport.parseAutonomy(spec.autonomy());
                        if (!LIVE_AUTONOMY.contains(tier)) {
                            throw new StudioException(
                                    "locked",
                                    403,
                                    "Supervised mode sends every tool call to an approvals inbox,"
                                            + " which needs an account. Use the workflow's approval"
                                            + " step to see a human-in-the-loop pause here.");
                        }
                        agentService.setAutonomyTier(agentId, tier);
                    }
                    if (spec.tools() != null) {
                        agentService.setAllowedToolNames(
                                agentId, DemoStudioSupport.toolSetFor(spec.tools(), agentId));
                    }
                    if (spec.guardrails() != null) {
                        Set<String> wanted =
                                new LinkedHashSet<>(
                                        DemoStudioSupport.guardrailSetFor(spec.guardrails()));
                        Set<String> offered =
                                guardrailService.getAvailableGuardrailsForPlan().stream()
                                        .map(ContentGuardrailType::getId)
                                        .collect(Collectors.toSet());
                        ENFORCED_GUARDRAILS.stream().filter(offered::contains).forEach(wanted::add);
                        guardrailService.updateAgentGuardrails(agentId, wanted);
                    }
                    if (spec.temperature() != null || spec.maxTokens() != null) {
                        Agent current = agentRepository.findById(agentId).orElseThrow();
                        double temperature =
                                spec.temperature() != null
                                        ? DemoStudioSupport.clampTemperature(spec.temperature())
                                        : current.getTemperature();
                        int maxTokens =
                                spec.maxTokens() != null
                                        ? DemoStudioSupport.clampTokens(spec.maxTokens())
                                        : current.getMaxTokens();
                        agentService.updateGenerationParams(
                                agentId,
                                new com.agenticai.core.agents.dto
                                        .AgentGenerationParamsUpdateRequest(
                                        maxTokens, temperature, current.getTopP()));
                    }
                    return null;
                });
        return getAgent(session, agentId);
    }

    /**
     * Repository delete, not {@link AgentService#delete}: that path refuses on FREE plans (the demo
     * tenant's plan whenever free mode is off) and the sandbox must be able to throw its own agents
     * away.
     *
     * <p>The LIVE agent (the one share links and the Telegram bridge point at) is deletable too - a
     * visitor who restarts to try a different use case must be able to clear the slate. Its slot
     * passes to another agent of the same sandbox if there is one, otherwise it is left empty, and
     * the share and channel surfaces already treat an empty slot as "nothing live yet". The agent's
     * documents go with it.
     */
    public void deleteAgent(DemoSession session, UUID agentId) {
        Owned owned = requireVisible(session, agentId);
        demoSessionService.inDemoTenantContextAs(
                session,
                owned.creator().getId(),
                () -> {
                    documentService.getObject().deleteAllFor(agentId);
                    connectorService.getObject().deleteAllFor(agentId);
                    agentRepository.delete(owned.agent());
                    agentRepository.flush();
                    return null;
                });
        DemoSession creator = owned.creator();
        if (agentId.equals(creator.getAgentId())) {
            String subject = DemoSessionService.actorSubject(creator.getId());
            UUID successor =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () ->
                                    agentRepository.findByCreatedBy(subject).stream()
                                            .filter(a -> !a.getId().equals(agentId))
                                            .map(Agent::getId)
                                            .findFirst()
                                            .orElse(null));
            if (successor != null) {
                demoSessionService.setLiveAgent(creator, successor);
            } else {
                demoSessionService.clearLiveAgent(creator);
            }
        }
    }

    public AgentView makeLive(DemoSession session, UUID agentId) {
        Owned owned = requireVisible(session, agentId);
        demoSessionService.setLiveAgent(session, owned.agent().getId());
        return DemoStudioSupport.toView(owned.agent(), session);
    }

    // ------------------------------------------------------------------
    // Missions
    // ------------------------------------------------------------------

    /**
     * One real agent turn as a token stream, with the real step-event trace merged in (same wire
     * format as the authenticated Playground, so the page's trace parser is the console's, not a
     * demo fork). The caller has already admitted the turn (expiry / read-only / message cap) and
     * pre-charged the allowance.
     */
    public Flux<String> runMission(
            DemoSession session, UUID agentId, String mission, String chatSessionId) {
        Owned owned = requireVisible(session, agentId);
        String text = mission == null ? "" : mission.strip();
        if (text.isEmpty()) {
            throw new StudioException("invalid", 400, "Give the agent something to do.");
        }
        if (text.length() > MAX_MISSION_CHARS) {
            text = text.substring(0, MAX_MISSION_CHARS);
        }
        moderation.check(session, Map.of("mission", text));
        AgentStepEventEmitter stepEvents = AgentStepEventEmitter.active(STEP_HEARTBEAT);
        String subject = DemoSessionService.actorSubject(session.getId());
        ChatTurnRequest turn = studioTurn(agentId, chatSessionId, subject, text, stepEvents);
        Flux<String> tokens =
                demoSessionService.inDemoTenantContextAs(
                        session,
                        owned.creator().getId(),
                        () -> agentChatService.chat(demoTurnContext(turn), turn));
        return Flux.merge(
                tokens.doFinally(signal -> stepEvents.complete()).timeout(missionTimeout),
                stepEvents.asFlux());
    }

    /**
     * Blocking variant for workflow steps: the whole reply, with the step trace routed to {@code
     * stepEvents}. The visitor's own text in a workflow (its input, step instructions, labels) is
     * moderated when the workflow is saved and when it is started ({@link
     * DemoStudioWorkflowRunner}); what arrives here also carries the previous agent's reply, which
     * is the agents' guardrails' job, so it is not re-classified per step.
     */
    public String runMissionBlocking(
            DemoSession session,
            UUID agentId,
            String mission,
            String chatSessionId,
            AgentStepEventEmitter stepEvents) {
        Owned owned = requireVisible(session, agentId);
        String subject = DemoSessionService.actorSubject(session.getId());
        String text =
                mission.length() > MAX_MISSION_CHARS
                        ? mission.substring(0, MAX_MISSION_CHARS)
                        : mission;
        ChatTurnRequest turn = studioTurn(agentId, chatSessionId, subject, text, stepEvents);
        return demoSessionService.inDemoTenantContextAs(
                session,
                owned.creator().getId(),
                () ->
                        agentChatService
                                .chat(demoTurnContext(turn), turn)
                                .collect(Collectors.joining())
                                .block(missionTimeout));
    }

    /** The studio's turn shape: empty attachment and extra-tool lists, live step events. */
    private static ChatTurnRequest studioTurn(
            UUID agentId,
            String chatSessionId,
            String subject,
            String text,
            AgentStepEventEmitter stepEvents) {
        return new ChatTurnRequest(
                agentId,
                chatSessionId,
                subject,
                text,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                false,
                stepEvents,
                false);
    }

    /**
     * ADR-0022 stage 4: built inside {@code inDemoTenantContextAs}, so the turn context carries the
     * demo tenant that call put on this thread.
     */
    private static TurnContext demoTurnContext(ChatTurnRequest turn) {
        return TurnContext.forEntry(TenantContext.current().orElse(null), turn);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static Set<String> toolSetFor(Collection<String> requested) {
        return DemoStudioSupport.toolSetFor(requested, null);
    }

    /** See {@link DemoStudioSupport#toolSetFor(Collection, UUID)}. */
    static Set<String> toolSetFor(Collection<String> requested, UUID agentId) {
        return DemoStudioSupport.toolSetFor(requested, agentId);
    }

    static Set<String> guardrailSetFor(Collection<String> requested) {
        return DemoStudioSupport.guardrailSetFor(requested);
    }

    static String cleanName(String raw) {
        return DemoStudioSupport.cleanName(raw);
    }
}
