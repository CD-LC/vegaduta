package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.GuardrailPromptScope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Phase P6 - the content-moderation orchestrator. Runs the enabled policy
 * packs for a piece of content through three layers, in this order, and
 * returns exactly one of {@code BLOCK | ALLOW | QUEUE_FOR_REVIEW}:
 *
 * <ol>
 *   <li><b>Pattern</b> ({@link PolicyPackPatternMatcher}) - deterministic
 *       regex/keyword rules; a match is treated as full (100) confidence.</li>
 *   <li><b>Semantic</b> ({@link PolicyPackSemanticClassifier}) - an optional,
 *       per-pack LLM-judge confidence score 0-100; fail-open (0) if no
 *       {@link ChatClient} or on any error.</li>
 *   <li><b>External classifier</b> ({@link ExternalSafetyClassifier}) - the
 *       highest-stakes seam (CSAM, credible violence). A match routes to the
 *       RESTRICTED audit path ({@link ModerationRestrictedAuditService}),
 *       NEVER the general audit log, and withholds the content.</li>
 * </ol>
 *
 * <p><b>Banding.</b> Each pack has an admin-configured review and block
 * threshold. For a firing signal of confidence {@code c}: {@code c >= block}
 * → BLOCK; {@code review <= c < block} → QUEUE_FOR_REVIEW; {@code c < review}
 * → nothing. The layers short-circuit on the first BLOCK; otherwise the
 * highest-confidence review-band signal across all layers/packs becomes a
 * human review-queue item. This is the "human-reviewable by design" middle
 * band: content that is neither clearly fine nor clearly disallowed is NOT
 * auto-decided - a trust-&-safety reviewer decides it.
 *
 * <p><b>Default-safe / opt-in.</b> If the master switch is off (the default),
 * or no packs are enabled for the tenant/plan, this returns ALLOW immediately
 * and writes nothing - the platform behaves exactly as it did before P6.
 *
 * <p><b>Never a silent block.</b> Every BLOCK and every QUEUE decision is
 * written to {@link AuditLogService} with the firing pack, layer, confidence,
 * and the effective thresholds (the "firing policy") - except an external-
 * classifier match, whose existence is recorded ONLY on the restricted path
 * by design.
 *
 * <p><b>Not wired into the live chat path here.</b> P6 builds this capability
 * as a self-contained, opt-in subsystem; deliberately NOT touching {@code
 * AgentChatService} or any request path (per the P6 scope). A future change
 * calls {@link #moderate} at whatever input/output boundary a deployment
 * chooses.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    static final String ACTION_PATTERN = "moderation.pattern";
    static final String ACTION_SEMANTIC = "moderation.semantic";
    static final String ACTION_EXTERNAL = "moderation.external";
    static final String TARGET_TYPE = "moderation_content";

    private final ModerationPolicyService policyService;
    private final PolicyPackPatternMatcher patternMatcher;
    private final PolicyPackSemanticClassifier semanticClassifier;
    private final ExternalSafetyClassifier externalClassifier;
    private final ModerationRestrictedAuditService restrictedAuditService;
    private final ModerationReviewService reviewService;
    private final AuditLogService auditLogService;

    /**
     * Spring constructor. The external classifier is resolved through an
     * {@link ObjectProvider} so the pluggable seam backs off deterministically:
     * a deployment that registers exactly one real {@link
     * ExternalSafetyClassifier} bean gets it; otherwise the shipped {@link
     * NoOpExternalSafetyClassifier} is used. This avoids the {@code
     * @ConditionalOnMissingBean} ordering caveats that apply to component-
     * scanned beans - see {@link NoOpExternalSafetyClassifier}'s javadoc.
     */
    @Autowired
    public ModerationService(
        ModerationPolicyService policyService,
        PolicyPackPatternMatcher patternMatcher,
        PolicyPackSemanticClassifier semanticClassifier,
        ObjectProvider<ExternalSafetyClassifier> externalClassifierProvider,
        ModerationRestrictedAuditService restrictedAuditService,
        ModerationReviewService reviewService,
        AuditLogService auditLogService
    ) {
        this(policyService, patternMatcher, semanticClassifier,
            externalClassifierProvider.getIfAvailable(NoOpExternalSafetyClassifier::new),
            restrictedAuditService, reviewService, auditLogService);
    }

    /**
     * Direct-injection constructor - used by tests and by the Spring
     * constructor above once the classifier has been resolved.
     */
    public ModerationService(
        ModerationPolicyService policyService,
        PolicyPackPatternMatcher patternMatcher,
        PolicyPackSemanticClassifier semanticClassifier,
        ExternalSafetyClassifier externalClassifier,
        ModerationRestrictedAuditService restrictedAuditService,
        ModerationReviewService reviewService,
        AuditLogService auditLogService
    ) {
        this.policyService = policyService;
        this.patternMatcher = patternMatcher;
        this.semanticClassifier = semanticClassifier;
        this.externalClassifier = externalClassifier;
        this.restrictedAuditService = restrictedAuditService;
        this.reviewService = reviewService;
        this.auditLogService = auditLogService;
    }

    /** Pattern + external layers only (no semantic layer without a ChatClient). */
    public ModerationOutcome moderate(String content, ModerationContext context) {
        return moderate(content, context, null);
    }

    /**
     * Runs the full layered flow.
     *
     * @param content the content to moderate
     * @param context who/what/where - drives config resolution and audit
     * @param chatClient the caller's ChatClient for the semantic layer;
     *     null skips the semantic layer entirely (fail-open)
     */
    public ModerationOutcome moderate(String content, ModerationContext context, ChatClient chatClient) {
        // The semantic layer reads its judge prompt from the prompt registry for the agent the content is for
        // (see GuardrailPromptScope).
        try (GuardrailPromptScope scope = GuardrailPromptScope.openIfKnown(context != null ? context.agentId() : null)) {
            return moderateInScope(content, context, chatClient);
        }
    }

    private ModerationOutcome moderateInScope(String content, ModerationContext context, ChatClient chatClient) {
        if (content == null || content.isBlank()) {
            return ModerationOutcome.allow();
        }
        if (!policyService.isModerationEnabled()) {
            return ModerationOutcome.allow();
        }

        List<ModerationPolicyPack> packs = policyService.enabledPacks(context);
        boolean externalEnabled = policyService.isExternalClassifierEnabled();
        if (packs.isEmpty() && !externalEnabled) {
            return ModerationOutcome.allow();
        }

        QueueCandidate candidate = null;

        // LAYER 1 - PATTERN (deterministic, confidence 100).
        for (ModerationPolicyPack pack : packs) {
            var match = patternMatcher.firstMatch(pack, content);
            if (match.isEmpty()) {
                continue;
            }
            PolicyPackConfig cfg = policyService.resolve(pack, context);
            int confidence = 100;
            ModerationDecision decision = band(confidence, cfg);
            String reason = "matched pattern rule '" + match.get() + "'";
            recordLayerDecision(ACTION_PATTERN, context, pack, ModerationLayer.PATTERN, confidence, cfg, decision, reason);
            if (decision == ModerationDecision.BLOCK) {
                return ModerationOutcome.blocked(ModerationLayer.PATTERN, pack, confidence, reason);
            }
            if (decision == ModerationDecision.QUEUE_FOR_REVIEW) {
                candidate = QueueCandidate.better(candidate,
                    new QueueCandidate(ModerationLayer.PATTERN, pack, confidence, reason));
            }
        }

        // LAYER 2 - SEMANTIC (graded, optional per pack, needs a ChatClient).
        if (chatClient != null) {
            for (ModerationPolicyPack pack : packs) {
                PolicyPackConfig cfg = policyService.resolve(pack, context);
                if (!cfg.semanticEnabled()) {
                    continue;
                }
                int confidence = semanticClassifier.score(chatClient, pack, content);
                if (confidence <= 0) {
                    continue;
                }
                ModerationDecision decision = band(confidence, cfg);
                if (decision == ModerationDecision.ALLOW) {
                    continue;
                }
                String reason = "semantic classifier scored " + confidence + "/100";
                recordLayerDecision(ACTION_SEMANTIC, context, pack, ModerationLayer.SEMANTIC, confidence, cfg, decision, reason);
                if (decision == ModerationDecision.BLOCK) {
                    return ModerationOutcome.blocked(ModerationLayer.SEMANTIC, pack, confidence, reason);
                }
                candidate = QueueCandidate.better(candidate,
                    new QueueCandidate(ModerationLayer.SEMANTIC, pack, confidence, reason));
            }
        }

        // LAYER 3 - EXTERNAL CLASSIFIER (highest-stakes; restricted path only).
        if (externalEnabled) {
            try {
                ExternalSafetyVerdict verdict = externalClassifier.classify(content, context);
                if (verdict != null && verdict.matched()) {
                    // RESTRICTED path ONLY - never the general audit log.
                    restrictedAuditService.record(context, verdict, content);
                    // Withhold the content. The general outcome carries NO
                    // category detail - that stays on the restricted path.
                    return ModerationOutcome.blocked(
                        ModerationLayer.EXTERNAL_CLASSIFIER, null, verdict.confidence(),
                        "withheld pending restricted safety review");
                }
            } catch (Exception ex) {
                // Fail-safe for the CONTENT flow (a vendor outage must never
                // wrongly block ordinary content), but NOT silent - an outage
                // of the highest-stakes detector is itself operationally
                // significant. Content-free log line by design.
                log.warn("External safety classifier failed - treated as no-match for the content flow. "
                    + "The highest-stakes layer was unavailable for this call.", ex);
            }
        }

        if (candidate != null) {
            ModerationReviewItem item = reviewService.enqueue(
                context, content, candidate.pack(), candidate.layer(), candidate.confidence(), candidate.reason());
            return ModerationOutcome.queued(candidate.layer(), candidate.pack(), candidate.confidence(), candidate.reason(), item.getId());
        }

        return ModerationOutcome.allow();
    }

    private ModerationDecision band(int confidence, PolicyPackConfig cfg) {
        if (confidence >= cfg.blockThreshold()) {
            return ModerationDecision.BLOCK;
        }
        if (confidence >= cfg.reviewThreshold()) {
            return ModerationDecision.QUEUE_FOR_REVIEW;
        }
        return ModerationDecision.ALLOW;
    }

    private void recordLayerDecision(
        String actionType,
        ModerationContext context,
        ModerationPolicyPack pack,
        ModerationLayer layer,
        int confidence,
        PolicyPackConfig cfg,
        ModerationDecision decision,
        String reason
    ) {
        AuditOutcome outcome = switch (decision) {
            case BLOCK -> AuditOutcome.REJECTED;
            case QUEUE_FOR_REVIEW -> AuditOutcome.PENDING_APPROVAL;
            case ALLOW -> AuditOutcome.SUCCESS;
        };
        String detail = "decision=" + decision
            + " pack=" + pack.configKey()
            + " layer=" + layer
            + " confidence=" + confidence
            + " firingPolicy=[review>=" + cfg.reviewThreshold() + ",block>=" + cfg.blockThreshold() + "]"
            + " (" + reason + ")";
        auditLogService.record(
            context != null ? context.actorId() : null,
            actionType,
            TARGET_TYPE,
            context != null && context.sessionId() != null ? context.sessionId() : "n/a",
            outcome,
            detail
        );
    }

    /**
     * The best (highest-confidence) review-band signal seen so far. Only used
     * when no layer produced a BLOCK - it becomes the review-queue item.
     */
    private record QueueCandidate(ModerationLayer layer, ModerationPolicyPack pack, int confidence, String reason) {

        static QueueCandidate better(QueueCandidate current, QueueCandidate other) {
            if (current == null) {
                return other;
            }
            return other.confidence() > current.confidence() ? other : current;
        }
    }
}
