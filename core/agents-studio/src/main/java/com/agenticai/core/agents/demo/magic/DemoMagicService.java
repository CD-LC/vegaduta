package com.agenticai.core.agents.demo.magic;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agentspi.HostedImageModel;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.demo.DemoImageService;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflow;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflowRepository;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Magic Studio orchestration: persists planned workflows, publishes them as public run pages
 * ({@code /w/{shareId}}), and executes runs - the owner's preview and the public page's one-shot
 * runs - with the demo surface's standing contract: <b>spend is pre-charged, every limit is
 * enforced before the provider is called, and the visitor-facing path never returns a raw
 * error.</b>
 *
 * <h2>Public run limits (the product rule: "once per IP, at most 3 people")</h2>
 *
 * <ol>
 *   <li><b>Once per IP:</b> a Redis SET of runner IPs per share ({@code
 *       agentic:demo-magic-runners:<shareId>}), the exact {@code DemoShareService#admitViewer}
 *       pattern. An IP already in the set is refused with {@code already_ran} when {@code
 *       demo.magic.per-ip-runs} (default 1) is spent.
 *   <li><b>Distinct-runner cap:</b> the same set's size against {@code demo.magic.run-cap} (default
 *       3, admin-tunable live via platform settings - the same PlatformSettingsService switchboard
 *       the maintenance console already edits).
 *   <li><b>Durable ceiling:</b> {@code runs_used < run_cap} consumed atomically on the row ({@link
 *       DemoMagicWorkflowRepository #tryConsumeRun}) - survives a Redis flush.
 *   <li><b>The money limits stay where they always were:</b> every run spends the owner session's
 *       own message/image allowance and rides the FREE-plan platform budgets inside the chat/image
 *       paths.
 * </ol>
 */
@Service
public class DemoMagicService {

    private static final Logger log = LoggerFactory.getLogger(DemoMagicService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SHARE_ID_BYTES = 18;
    private static final Pattern SHARE_ID_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final String RUNNER_SET_KEY_PREFIX = "agentic:demo-magic-runners:";
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(45);

    private final DemoMagicWorkflowRepository workflowRepository;
    private final DemoMagicPlanner planner;
    private final DemoSessionService demoSessionService;
    private final DemoImageService demoImageService;
    private final HostedImageModel imageClient;
    private final AgentChatService agentChatService;
    private final AgentService agentService;
    private final PlatformSettingsService platformSettings;
    private final SafePublicUrlFetcher urlFetcher;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final String appPublicUrl;

    public DemoMagicService(
            DemoMagicWorkflowRepository workflowRepository,
            DemoMagicPlanner planner,
            DemoSessionService demoSessionService,
            DemoImageService demoImageService,
            HostedImageModel imageClient,
            AgentChatService agentChatService,
            AgentService agentService,
            PlatformSettingsService platformSettings,
            SafePublicUrlFetcher urlFetcher,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            @Value("${app.web.public-url:}") String appPublicUrl) {
        this.workflowRepository = workflowRepository;
        this.planner = planner;
        this.demoSessionService = demoSessionService;
        this.demoImageService = demoImageService;
        this.imageClient = imageClient;
        this.agentChatService = agentChatService;
        this.agentService = agentService;
        this.platformSettings = platformSettings;
        this.urlFetcher = urlFetcher;
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.appPublicUrl =
                appPublicUrl != null && appPublicUrl.endsWith("/")
                        ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                        : appPublicUrl;
    }

    // ------------------------------------------------------------------
    // Settings (all live-tunable from the platform maintenance console)
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return platformSettings.getBoolean("demo.magic.enabled", true);
    }

    public int runCap() {
        return Math.max(1, platformSettings.getInt("demo.magic.run-cap", 3));
    }

    public int perIpRuns() {
        return Math.max(1, platformSettings.getInt("demo.magic.per-ip-runs", 1));
    }

    public int perSessionWorkflowLimit() {
        return Math.max(1, platformSettings.getInt("demo.magic.per-session-limit", 3));
    }

    // ------------------------------------------------------------------
    // Plan + persist
    // ------------------------------------------------------------------

    public record PlannedWorkflow(DemoMagicWorkflow workflow, DemoMagicPlanner.MagicPlan plan) {}

    /**
     * Plans the workflow and persists it in one shot (status: unpublished). The dynamic agent
     * create is best-effort: a FREE-plan agent cap or a transient failure degrades to the session's
     * seeded agent at run time, never to an error here.
     */
    public Optional<PlannedWorkflow> planAndPersist(
            DemoSession session, String prompt, String link) {
        if (workflowRepository.countBySessionId(session.getId()) >= perSessionWorkflowLimit()) {
            return Optional.empty();
        }
        DemoMagicPlanner.MagicPlan plan =
                planner.plan(
                        prompt,
                        link,
                        demoImageService.isEnabled() ? demoImageService.model() : null);

        UUID agentId = createWorkflowAgent(session, plan);

        DemoMagicWorkflow workflow =
                new DemoMagicWorkflow(
                        session.getId(),
                        plan.title(),
                        plan.intent().name(),
                        agentId,
                        toJson(plan),
                        toJson(plan.formSpec()),
                        runCap(),
                        session.getExpiresAt());
        return Optional.of(new PlannedWorkflow(workflowRepository.save(workflow), plan));
    }

    /**
     * "Create the necessary workflow agents dynamically" - literally, through {@link
     * AgentService#create} inside the demo tenant's schema, the same honest path {@code
     * DemoAgentSeeder} takes (FREE coercion, plan caps, SIMPLE experience all apply). Best-effort
     * by contract.
     */
    private UUID createWorkflowAgent(DemoSession session, DemoMagicPlanner.MagicPlan plan) {
        try {
            return demoSessionService.inDemoTenantContext(
                    session,
                    () ->
                            agentService
                                    .create(
                                            new AgentCreateRequest(
                                                    plan.agentName(),
                                                    plan.agentSystemPrompt(),
                                                    ProviderName.PLATFORM_HOSTED,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    ExperienceMode.SIMPLE,
                                                    ReasoningDepth.FAST,
                                                    TuningMode.SIMPLE,
                                                    null, // edgeRoutingPolicy - no server policy
                                                    // for demo agents
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null))
                                    .id());
        } catch (RuntimeException e) {
            log.info(
                    "Magic workflow agent create failed for sandbox {} (falling back to the seeded"
                            + " agent): {}",
                    session.getId(),
                    e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Publish + resolve
    // ------------------------------------------------------------------

    public record PublishedLink(String shareId, String shareUrl) {}

    public Optional<PublishedLink> publish(DemoSession session, UUID workflowId) {
        Optional<DemoMagicWorkflow> found = workflowRepository.findById(workflowId);
        if (found.isEmpty() || !found.get().getSessionId().equals(session.getId())) {
            return Optional.empty();
        }
        DemoMagicWorkflow workflow = found.get();
        if (workflow.getShareId() == null) {
            byte[] bytes = new byte[SHARE_ID_BYTES];
            RANDOM.nextBytes(bytes);
            workflow.setShareId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            workflowRepository.save(workflow);
        }
        return Optional.of(
                new PublishedLink(
                        workflow.getShareId(), appPublicUrl + "/w/" + workflow.getShareId()));
    }

    public Optional<DemoMagicWorkflow> resolveByShareId(String shareId) {
        if (shareId == null || !SHARE_ID_SHAPE.matcher(shareId).matches()) {
            return Optional.empty();
        }
        return workflowRepository.findByShareId(shareId);
    }

    public Optional<DemoMagicWorkflow> findOwned(DemoSession session, UUID workflowId) {
        return workflowRepository
                .findById(workflowId)
                .filter(w -> w.getSessionId().equals(session.getId()));
    }

    // ------------------------------------------------------------------
    // Public-run admission (once per IP, N distinct runners, durable ceiling)
    // ------------------------------------------------------------------

    public enum RunAdmission {
        ALLOWED,
        ALREADY_RAN,
        RUNNER_CAP,
        RUNS_EXHAUSTED
    }

    public RunAdmission admitRunner(DemoMagicWorkflow workflow, String clientIp) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis != null && clientIp != null && !clientIp.isBlank()) {
            String key = RUNNER_SET_KEY_PREFIX + workflow.getShareId();
            try {
                boolean known = Boolean.TRUE.equals(redis.opsForSet().isMember(key, clientIp));
                if (known && perIpRuns() <= 1) {
                    return RunAdmission.ALREADY_RAN;
                }
                if (!known) {
                    Long size = redis.opsForSet().size(key);
                    if (size != null && size >= runCap()) {
                        return RunAdmission.RUNNER_CAP;
                    }
                    redis.opsForSet().add(key, clientIp);
                    redis.expireAt(key, workflow.getExpiresAt());
                }
            } catch (RuntimeException e) {
                // Fail open, same documented posture as the share viewer cap:
                // the durable row ceiling and the money limits still hold.
                log.warn(
                        "Magic runner-cap check failed for share {} - failing open",
                        workflow.getShareId(),
                        e);
            }
        }
        // Durable, atomic - the ceiling that survives a Redis flush.
        if (workflowRepository.tryConsumeRun(workflow.getId()) == 0) {
            return RunAdmission.RUNS_EXHAUSTED;
        }
        return RunAdmission.ALLOWED;
    }

    // ------------------------------------------------------------------
    // Execution (shared by the owner preview and the public run)
    // ------------------------------------------------------------------

    public record RunResult(String type, String text, String imageUrl, boolean simulated) {

        static RunResult text(String text, boolean simulated) {
            return new RunResult("text", text, null, simulated);
        }

        static RunResult image(String imageUrl) {
            return new RunResult("image", null, imageUrl, false);
        }
    }

    /**
     * Executes one run of the workflow: knowledge injection (public-link scrape) ->
     * intent-appropriate execution -> never a raw error. The caller has already done admission +
     * moderation + pre-charging.
     */
    public RunResult execute(
            DemoSession session,
            DemoMagicWorkflow workflow,
            String prompt,
            String link,
            String clientIp) {
        String composed = composeMessage(prompt, link);
        if ("IMAGE".equals(workflow.getIntent())) {
            return executeImage(session, prompt, clientIp);
        }
        return executeAgentTurn(session, workflow, composed, clientIp);
    }

    /**
     * The knowledge step: fetch the public page (SSRF-guarded), inject as a labelled context block.
     */
    private String composeMessage(String prompt, String link) {
        String base = prompt == null ? "" : prompt.strip();
        if (link == null || link.isBlank()) {
            return base;
        }
        Optional<SafePublicUrlFetcher.FetchedPage> page = urlFetcher.fetchText(link);
        if (page.isEmpty()) {
            return base
                    + "\n\n"
                    + "(The provided link could not be fetched - it may be private, unreachable, or"
                    + " not a public web page. Say so briefly and answer from the request alone.)";
        }
        return "[Public page content from "
                + page.get().url()
                + " - use as context, do not invent beyond it]\n"
                + page.get().textExcerpt()
                + "\n[End of page content]\n\n"
                + base;
    }

    private RunResult executeImage(DemoSession session, String prompt, String clientIp) {
        String cleaned = prompt == null ? "" : prompt.strip();
        if (!demoImageService.isEnabled() || !imageClient.isAvailable()) {
            return RunResult.text(simulatedImageFallback(), true);
        }
        if (demoImageService.isCapReached(session)) {
            return RunResult.text(
                    "This sandbox has used its image allowance ("
                            + demoImageService.perSessionLimit()
                            + " images). Start a fresh sandbox on the landing page to generate"
                            + " more.",
                    true);
        }
        String refusal = demoImageService.refusalFor(cleaned);
        if (refusal != null) {
            return RunResult.text(refusal, true);
        }
        // Pre-charged BEFORE the provider call - a failed call must not be free.
        demoImageService.recordAttempt(session);
        try {
            HostedImageModel.Image generated =
                    imageClient.generate(cleaned, demoImageService.model());
            UUID imageId =
                    demoImageService.store(
                            session,
                            generated.bytes(),
                            generated.mimeType(),
                            cleaned,
                            generated.model(),
                            clientIp);
            return RunResult.image("/api/demo/image/" + imageId);
        } catch (RuntimeException e) {
            log.warn(
                    "Magic image run failed for sandbox {} - serving a labelled fallback",
                    session.getId(),
                    e);
            return RunResult.text(simulatedImageFallback(), true);
        }
    }

    private RunResult executeAgentTurn(
            DemoSession session, DemoMagicWorkflow workflow, String message, String clientIp) {
        UUID agentId = workflow.getAgentId();
        if (agentId == null) {
            agentId = demoSessionService.ensureSeededAgent(session, null).orElse(null);
        }
        if (agentId == null) {
            return RunResult.text(simulatedTextFallback(workflow), true);
        }
        UUID finalAgentId = agentId;
        String chatSessionId = "magic:" + workflow.getId() + ":" + ipHash(clientIp);
        String subjectKey = "demo:" + session.getId();
        ChatTurnRequest turn =
                ChatTurnRequest.builder(finalAgentId, chatSessionId, subjectKey, message).build();
        try {
            String reply =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () ->
                                    agentChatService
                                            .chat(
                                                    // ADR-0022 stage 4: the demo tenant set by
                                                    // inDemoTenantContext just above.
                                                    TurnContext.forEntry(
                                                            TenantContext.current().orElse(null),
                                                            turn),
                                                    turn)
                                            .collect(Collectors.joining())
                                            .block(TURN_TIMEOUT));
            if (reply == null || reply.isBlank()) {
                return RunResult.text(simulatedTextFallback(workflow), true);
            }
            return RunResult.text(reply, false);
        } catch (RuntimeException e) {
            log.warn(
                    "Magic run turn failed for workflow {} - serving a labelled fallback",
                    workflow.getId(),
                    e);
            return RunResult.text(simulatedTextFallback(workflow), true);
        }
    }

    // ------------------------------------------------------------------

    public DemoMagicPlanner.MagicPlan readPlan(DemoMagicWorkflow workflow) {
        try {
            return objectMapper.readValue(workflow.getPlanJson(), DemoMagicPlanner.MagicPlan.class);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Unreadable plan payload for workflow " + workflow.getId(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise a magic plan payload", e);
        }
    }

    private static String simulatedImageFallback() {
        return "(Sample response - the image model is busy right now.) On a live run this workflow "
                + "sends your refined prompt to the platform's hosted image model and returns the "
                + "generated image here, ready to download.";
    }

    private static String simulatedTextFallback(DemoMagicWorkflow workflow) {
        return "(Sample response - the live model is busy right now, so here's the shape of what"
                + " this workflow produces.)\n\n"
                + "This is \""
                + workflow.getTitle()
                + "\" - a workflow built in a free VegaDuta sandbox. On a live run the dedicated"
                + " agent processes your input through the platform's hosted model pool and returns"
                + " the finished result right here.";
    }

    private static String ipHash(String clientIp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    (clientIp == null ? "" : clientIp)
                                            .getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
