package com.agenticai.core.agents.demo.magic;

import com.agenticai.core.guardrails.moderation.ModerationContext;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflow;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRepository;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Magic Studio HTTP surface - two halves with two identity models, following
 * the demo package's established split:
 *
 * <ul>
 *   <li><b>Owner half</b> ({@code /api/demo/magic/plan}, {@code
 *       /{workflowId}/preview}, {@code /{workflowId}/publish}) - identified
 *       by {@code X-Demo-Token} exactly like {@code /api/demo/chat}: these
 *       are the endpoints that plan, spend and publish, so they must be
 *       attributable to a sandbox with an allowance.</li>
 *   <li><b>Public half</b> ({@code GET/POST /api/demo/magic/run/{shareId}})
 *       - possession of the unguessable share id is the whole credential,
 *       the same capability-handle posture as {@code DemoShareController}.
 *       The GET returns ONLY what the mobile run page needs: title + the
 *       creator's form spec. The plan, the agent prompt, the diagram - the
 *       behind-the-scenes - deliberately never ships on the public meta,
 *       because the product rule for the run page is "just the form".</li>
 * </ul>
 *
 * <p>Error posture on the public half: machine-readable {@code {"error":
 * ...}} keys with real statuses ({@code already_ran}, {@code runner_cap},
 * {@code runs_exhausted}, {@code budget_exhausted}, {@code moderated},
 * {@code expired}, {@code invalid}, {@code disabled}) - the
 * DemoShareController contract, extended with the run-cap vocabulary. An
 * upstream/model failure still never surfaces as an error; the run degrades
 * to a labelled {@code simulated: true} result inside {@link
 * DemoMagicService}.
 */
@RestController
@RequestMapping("/api/demo/magic")
public class DemoMagicController {

    private static final Logger log = LoggerFactory.getLogger(DemoMagicController.class);

    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";
    private static final int MAX_PROMPT_LENGTH = 2000;
    private static final int MAX_LINK_LENGTH = 2048;

    private final DemoMagicService magicService;
    private final DemoSessionService demoSessionService;
    private final DemoSessionRepository demoSessionRepository;
    private final ObjectProvider<ModerationService> moderationService;

    public DemoMagicController(
        DemoMagicService magicService,
        DemoSessionService demoSessionService,
        DemoSessionRepository demoSessionRepository,
        ObjectProvider<ModerationService> moderationService
    ) {
        this.magicService = magicService;
        this.demoSessionService = demoSessionService;
        this.demoSessionRepository = demoSessionRepository;
        this.moderationService = moderationService;
    }

    // ------------------------------------------------------------------
    // Wire shapes
    // ------------------------------------------------------------------

    public record MagicPlanRequest(
        @Size(max = MAX_PROMPT_LENGTH) String prompt,
        @Size(max = MAX_LINK_LENGTH) String link
    ) {
    }

    public record MagicPlanResponse(UUID workflowId, DemoMagicPlanner.MagicPlan plan) {
    }

    public record MagicRunRequest(Map<String, String> values) {

        String value(String key) {
            if (values == null) {
                return null;
            }
            String raw = values.get(key);
            return raw == null ? null : raw.strip();
        }
    }

    public record MagicPublishResponse(String shareId, String shareUrl, int runCap, int perIpRuns) {
    }

    /** The public run page's ENTIRE world: title + form. Nothing behind-the-scenes. */
    public record MagicRunMetaResponse(
        String title,
        List<DemoMagicPlanner.FormField> fields,
        Instant expiresAt,
        int runsRemaining
    ) {
    }

    // ------------------------------------------------------------------
    // Owner half (X-Demo-Token)
    // ------------------------------------------------------------------

    @PostMapping("/plan")
    public ResponseEntity<?> plan(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @Valid @RequestBody(required = false) MagicPlanRequest request
    ) {
        if (!magicService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "disabled"));
        }
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now())) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        String prompt = request == null || request.prompt() == null ? "" : request.prompt().strip();
        if (prompt.isEmpty() || prompt.length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
        }
        Optional<DemoMagicService.PlannedWorkflow> planned =
            magicService.planAndPersist(session, prompt, request == null ? null : request.link());
        if (planned.isEmpty()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "workflow_cap"));
        }
        return ResponseEntity.ok(new MagicPlanResponse(
            planned.get().workflow().getId(), planned.get().plan()));
    }

    /**
     * The owner's own test run of an unpublished (or published) workflow.
     * Spends the owner's allowances (message or image) but never the public
     * run caps - trying your own creation must not eat your 3 public runs.
     */
    @PostMapping("/{workflowId}/preview")
    public ResponseEntity<?> preview(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @PathVariable("workflowId") UUID workflowId,
        @RequestBody(required = false) MagicRunRequest request,
        HttpServletRequest servletRequest
    ) {
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        DemoSession session = found.get();
        Optional<DemoMagicWorkflow> workflow = magicService.findOwned(session, workflowId);
        if (workflow.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        return runInternal(session, workflow.get(), request, servletRequest, false);
    }

    @PostMapping("/{workflowId}/publish")
    public ResponseEntity<?> publish(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @PathVariable("workflowId") UUID workflowId
    ) {
        if (!magicService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "disabled"));
        }
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        Optional<DemoMagicService.PublishedLink> link = magicService.publish(found.get(), workflowId);
        if (link.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        return ResponseEntity.ok(new MagicPublishResponse(
            link.get().shareId(), link.get().shareUrl(), magicService.runCap(), magicService.perIpRuns()));
    }

    // ------------------------------------------------------------------
    // Public half (share id is the credential)
    // ------------------------------------------------------------------

    @GetMapping("/run/{shareId}")
    public ResponseEntity<?> runMeta(@PathVariable("shareId") String shareId) {
        if (!magicService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "disabled"));
        }
        Optional<DemoMagicWorkflow> found = magicService.resolveByShareId(shareId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        DemoMagicWorkflow workflow = found.get();
        if (workflow.isExpired(Instant.now()) || workflow.isRevoked()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        DemoMagicPlanner.MagicPlan plan = magicService.readPlan(workflow);
        return ResponseEntity.ok(new MagicRunMetaResponse(
            workflow.getTitle(),
            plan.formSpec(),
            workflow.getExpiresAt(),
            Math.max(0, workflow.getRunCap() - workflow.getRunsUsed())
        ));
    }

    @PostMapping("/run/{shareId}")
    public ResponseEntity<?> run(
        @PathVariable("shareId") String shareId,
        @RequestBody(required = false) MagicRunRequest request,
        HttpServletRequest servletRequest
    ) {
        if (!magicService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "disabled"));
        }
        Optional<DemoMagicWorkflow> found = magicService.resolveByShareId(shareId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invalid"));
        }
        DemoMagicWorkflow workflow = found.get();
        Instant now = Instant.now();
        if (workflow.isExpired(now) || workflow.isRevoked()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        Optional<DemoSession> session = demoSessionRepository.findById(workflow.getSessionId());
        if (session.isEmpty() || session.get().isExpired(now)) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }

        // Admission BEFORE spend: once per IP + N distinct runners (Redis) +
        // the durable atomic row ceiling. See DemoMagicService#admitRunner.
        String clientIp = servletRequest.getRemoteAddr();
        switch (magicService.admitRunner(workflow, clientIp)) {
            case ALREADY_RAN -> {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "already_ran"));
            }
            case RUNNER_CAP -> {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "runner_cap"));
            }
            case RUNS_EXHAUSTED -> {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "runs_exhausted"));
            }
            case ALLOWED -> {
                // fall through
            }
        }
        return runInternal(session.get(), workflow, request, servletRequest, true);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<?> runInternal(
        DemoSession session, DemoMagicWorkflow workflow, MagicRunRequest request,
        HttpServletRequest servletRequest, boolean isPublicRun
    ) {
        String prompt = request == null ? null : request.value("prompt");
        String link = request == null ? null : request.value("link");
        if (prompt == null || prompt.isBlank() || prompt.length() > MAX_PROMPT_LENGTH
            || (link != null && link.length() > MAX_LINK_LENGTH)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
        }

        boolean imageIntent = "IMAGE".equals(workflow.getIntent());
        // Text runs spend the owner session's message allowance (image runs
        // spend the image allowance inside the service, DemoImageController
        // parity). Checked and pre-charged BEFORE the provider call.
        if (!imageIntent) {
            if (demoSessionService.isMessageCapReached(session)) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "budget_exhausted"));
            }
        }

        // Moderation on the raw public input BEFORE dispatch - a stranger
        // typing into someone's published workflow is the least-trusted input
        // this surface accepts. Owner previews get the same check; the chat
        // path re-moderates inbound anyway.
        if (isModerationRejected(session, workflow, prompt)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "moderated"));
        }

        if (!imageIntent) {
            demoSessionService.recordMessage(session);
        }
        DemoMagicService.RunResult result = magicService.execute(
            session, workflow, prompt, link, servletRequest.getRemoteAddr());
        return ResponseEntity.ok(result);
    }

    private boolean isModerationRejected(DemoSession session, DemoMagicWorkflow workflow, String message) {
        ModerationService moderation = moderationService.getIfAvailable();
        if (moderation == null) {
            return false;
        }
        UUID agentId = workflow.getAgentId() != null ? workflow.getAgentId() : session.getAgentId();
        if (agentId == null) {
            return false;
        }
        try {
            ModerationOutcome outcome = demoSessionService.inDemoTenantContext(session, () ->
                moderation.moderate(message, new ModerationContext(
                    session.getTenantId().toString(),
                    "FREE",
                    agentId,
                    "magic:" + workflow.getId(),
                    "demo:" + session.getId(),
                    "magic_run_input"
                ))
            );
            if (!outcome.isAllowed()) {
                log.info("Moderation rejected a magic-run input for workflow {} (decision={}, layer={})",
                    workflow.getId(), outcome.decision(), outcome.decidingLayer());
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("Magic-run moderation failed for workflow {} - failing open", workflow.getId(), e);
            return false;
        }
    }
}
