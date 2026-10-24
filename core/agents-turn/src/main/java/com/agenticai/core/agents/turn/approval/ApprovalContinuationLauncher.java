package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.agents.turn.ApprovalContinuationPort;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.concurrent.TenantAwareSchedulers;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Continues a chat session's task by itself once a human has decided one of its gated tool calls:
 * the server-driven half of human-in-the-loop that used to be left to the browser ("Approved. Carry
 * on with the task.", sent only by the Assistant pane, only with a folder attached).
 *
 * <h2>What it does</h2>
 * {@link #afterDecision} is called by the approval executor when the gated call has run, failed or
 * been denied. It builds an {@link ApprovalContinuation} from the request (the goal, the call, its
 * outcome, the tools already used), renders the continuation message ({@link
 * ApprovalContinuationPrompt}) and starts an ordinary chat turn for the same agent, session and
 * person through {@link AgentChatService#chat}, so every guard, budget and history rule of a normal
 * turn applies. The turn runs detached from the approving HTTP request; its user message and reply
 * are stored in the conversation like any other, which is how the web sees it (the user turn opens
 * with {@link ApprovalContinuation#MESSAGE_MARKER}).
 *
 * <h2>Safety properties</h2>
 * <ul>
 *   <li><b>Once.</b> The decision is claimed in Redis before anything starts ({@link
 *       ApprovalContinuationClaims}); a repeat starts nothing.
 *   <li><b>As the requester, in the requester's tenant.</b> The turn runs as the subject recorded in
 *       the envelope with no roles - exactly what the replay of the tool itself ran as - never as the
 *       approver, and a request whose recorded tenant differs from the calling thread's is refused.
 *       The approver decides one action; they do not gain the right to act in the conversation.
 *   <li><b>Not for jobs.</b> A job session ({@code job:<id>}) is continued by the job machinery,
 *       which resumes the job and builds its turn with the same {@link ApprovalContinuationPrompt}.
 *   <li><b>Never throws.</b> A failure to continue must not turn an executed approval into a failed
 *       one; it is logged, the claim is released when nothing started, and the history note the
 *       executor wrote remains.
 * </ul>
 */
@Service
public class ApprovalContinuationLauncher implements ApprovalContinuationPort {

    private static final Logger log = LoggerFactory.getLogger(ApprovalContinuationLauncher.class);

    /**
     * The first characters of an agent job's session id ({@code AgentJobSession.PREFIX}; a test pins
     * the two together). Duplicated rather than imported because the job package depends on the
     * turn path, and this package may not depend back on it.
     */
    static final String JOB_SESSION_PREFIX = "job:";

    private final AgentChatService chat;
    private final ApprovalContinuationFactory factory;
    private final ApprovalContinuationPrompt prompt;
    private final ApprovalContinuationClaims claims;
    private final ApprovalContinuationProperties properties;

    public ApprovalContinuationLauncher(
            AgentChatService chat,
            ApprovalContinuationFactory factory,
            ApprovalContinuationPrompt prompt,
            ApprovalContinuationClaims claims,
            ApprovalContinuationProperties properties) {
        this.chat = chat;
        this.factory = factory;
        this.prompt = prompt;
        this.claims = claims;
        this.properties = properties;
    }

    private volatile SettingsResolver settings;

    /**
     * The settings catalog, so a tenant's or an agent's {@code approvals.continuation.enabled} decides whether a
     * decision continues the task. Absent (hand-built instances): {@code app.approvals.continuation.enabled}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSettingsResolver(SettingsResolver resolver) {
        this.settings = resolver;
    }

    static final String ENABLED_KEY = "approvals.continuation.enabled";

    /**
     * Whether the continuation is on for the agent the request was made for (tenant level when the request names none):
     * decided on the thread that decides the request, which carries the request's tenant.
     */
    private boolean enabledFor(ApprovalRequest request) {
        SettingsResolver catalog = settings;
        if (catalog == null) {
            return properties.isEnabled();
        }
        try {
            return catalog.getBool(ENABLED_KEY, SettingContext.forAgent(factory.requestedAgentId(request).orElse(null)));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using app.approvals.continuation.enabled: {}", ENABLED_KEY, e.toString());
            return properties.isEnabled();
        }
    }

    @Override
    public boolean afterDecision(ApprovalRequest request, Outcome outcome, String text) {
        if (!enabledFor(request)) {
            return false;
        }
        try {
            Optional<ApprovalContinuation> built = factory.build(request, outcome, text);
            if (built.isEmpty()) {
                return false;
            }
            ApprovalContinuation continuation = built.get();
            if (continuation.sessionId().startsWith(JOB_SESSION_PREFIX)) {
                // The job machinery resumes the job and continues its turn itself.
                return false;
            }
            if (!sameTenantAsCaller(continuation)) {
                log.warn(
                        "Approval request {} belongs to another tenant than the one deciding it; the task is not"
                                + " continued",
                        request.getId());
                return false;
            }
            if (!claims.tryClaim(continuation.requestId())) {
                log.info(
                        "Approval request {} was already continued (or cannot be claimed); not starting the task again",
                        continuation.requestId());
                return false;
            }
            try {
                start(continuation);
                return true;
            } catch (RuntimeException e) {
                claims.release(continuation.requestId());
                throw e;
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Could not continue the task after the decision on approval request {} ({}); the outcome note is in"
                            + " the conversation history and the person can ask the agent to carry on",
                    request.getId(),
                    outcome,
                    e);
            return false;
        }
    }

    private void start(ApprovalContinuation continuation) {
        TenantContext tenant =
                TenantContext.of(continuation.tenantId(), continuation.subject(), Set.of());
        ChatTurnRequest turn =
                ChatTurnRequest.builder(
                                continuation.requestedAgentId(),
                                continuation.sessionId(),
                                continuation.subjectKey(),
                                prompt.render(continuation))
                        .continuation(continuation)
                        // The parked turn's run-scoped tool selection, if it had one: the task goes on with the same tools.
                        .toolNames(continuation.runToolNames())
                        .build();
        TurnContext context = TurnContext.forEntry(tenant, turn);
        log.info(
                "Continuing session '{}' of agent {} after the {} decision on approval request {} ('{}')",
                continuation.sessionId(),
                continuation.requestedAgentId(),
                continuation.outcome(),
                continuation.requestId(),
                continuation.toolName());
        // Subscribed under the turn's own tenant so the tenant-aware scheduler carries it to the
        // worker; the approving thread's context (the approver's request) is left untouched.
        TenantScope.of(tenant)
                .run(
                        () ->
                                Flux.defer(() -> chat.chat(context, turn))
                                        .subscribeOn(TenantAwareSchedulers.boundedElastic())
                                        .timeout(properties.getTurnTimeout())
                                        .then()
                                        .subscribe(
                                                ok ->
                                                        log.debug(
                                                                "Continuation of approval request {} finished",
                                                                continuation.requestId()),
                                                error ->
                                                        log.warn(
                                                                "The continuation turn of approval request {} failed",
                                                                continuation.requestId(),
                                                                error)));
    }

    /** Defence in depth: the calling thread's tenant, when it has one, must be the request's. */
    private static boolean sameTenantAsCaller(ApprovalContinuation continuation) {
        return TenantContext.current()
                .map(current -> continuation.tenantId().equals(current.getTenantId()))
                .orElse(true);
    }
}
