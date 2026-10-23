package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.HostedPoolExhaustedException;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Best-effort platform-issue recording for provider fallback, hosted-pool exhaustion and
 * fabricated generation URLs, extracted verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatIssueRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final AgentService agentService;
    private final ObjectProvider<PlatformIssueService> platformIssueService;

    /**
     * The prompt registry's view for an agent; the shipped text until {@link ChatServiceParts} wires
     * the settings bean.
     */
    volatile java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

    ChatIssueRecorder(AgentService agentService, ObjectProvider<PlatformIssueService> platformIssueService) {
        this.agentService = agentService;
        this.platformIssueService = platformIssueService;
    }

    /** Reactor callbacks carry no tenant: record under the turn's, captured on the request thread. */
    void recordFallbackTriggeredIssue(
            Optional<TenantContext> tenant, UUID agentId, Agent agent, Throwable primaryError) {
        TenantScope.of(tenant).run(() -> recordFallbackTriggeredIssue(agentId, agent, primaryError));
    }

    /** As {@link #recordFallbackTriggeredIssue(Optional, UUID, Agent, Throwable)}. */
    void recordFallbackAlsoFailedIssue(
            Optional<TenantContext> tenant,
            UUID agentId,
            Agent agent,
            Throwable primaryError,
            Throwable fallbackError) {
        TenantScope.of(tenant)
                .run(() -> recordFallbackAlsoFailedIssue(agentId, agent, primaryError, fallbackError));
    }

    /**
     * Fallback observability follow-up (#954) - records a WARNING-severity
     * issue the moment a provider fallback is actually attempted (whether or
     * not that attempt goes on to succeed), so the admin Issues portal shows
     * how often a tenant's agents are leaning on their fallback provider even
     * when it's working fine - a signal that the primary provider deserves a
     * look, well before it degrades into outright failures. Fingerprinted on
     * {@code PROVIDER_FALLBACK + agentId + title} (see
     * PlatformIssueService#record's fingerprint doc), so repeated fires from
     * the same agent bump one recurring row's occurrenceCount rather than
     * flooding the portal with a new row per chat turn. Best-effort only,
     * same "never let an observability write break the primary operation"
     * posture as every other record() call site in this codebase.
     */
    void recordFallbackTriggeredIssue(UUID agentId, Agent agent, Throwable primaryError) {
        // #955 - the durable per-agent counter (Agent.fallbackTriggeredCount)
        // that Fallback UI/telemetry reads from. Recorded here rather than a
        // third call site, since this method already runs exactly once per
        // fallback attempt from both streamFast and callWithProviderFallback.
        try {
            agentService.recordFallbackTriggered(agentId);
        } catch (RuntimeException counterFailure) {
            log.warn("Failed to increment fallback-triggered counter for agent {}", agentId, counterFailure);
        }
        PlatformIssueService service = platformIssueService.getIfAvailable();
        if (service == null) return;
        try {
            service.record(
                IssueSource.PROVIDER_FALLBACK,
                agentId.toString(),
                IssueSeverity.WARNING,
                "Provider fallback triggered",
                "Agent " + agentId + ": primary provider " + agent.getProviderName()
                    + " failed (" + primaryError.getMessage() + "), retried on fallback provider "
                    + agent.getFallbackProviderName()
            );
        } catch (RuntimeException recordingFailure) {
            log.warn("Failed to record platform issue for agent {} provider fallback", agentId, recordingFailure);
        }
    }

    /**
     * Fallback observability follow-up (#954) - records an ERROR-severity
     * issue when the fallback path could not rescue the turn either (its
     * provider couldn't be resolved, or its own call also failed) - covering
     * both failure shapes uniformly, since either way the user got no answer
     * despite the agent having a fallback configured, which is exactly the
     * "your safety net didn't catch anything" case an admin needs to know
     * about. Same fingerprint/best-effort posture as {@link
     * #recordFallbackTriggeredIssue} above.
     */
    void recordFallbackAlsoFailedIssue(UUID agentId, Agent agent, Throwable primaryError, Throwable fallbackError) {
        // #955 - counterpart counter (Agent.fallbackFailedCount).
        try {
            agentService.recordFallbackFailed(agentId);
        } catch (RuntimeException counterFailure) {
            log.warn("Failed to increment fallback-failed counter for agent {}", agentId, counterFailure);
        }
        PlatformIssueService service = platformIssueService.getIfAvailable();
        if (service == null) return;
        try {
            service.record(
                IssueSource.PROVIDER_FALLBACK,
                agentId.toString(),
                IssueSeverity.ERROR,
                "Provider fallback also failed",
                "Agent " + agentId + ": primary provider " + agent.getProviderName()
                    + " failed (" + primaryError.getMessage() + "), and fallback provider "
                    + agent.getFallbackProviderName() + " could not rescue the turn ("
                    + fallbackError.getMessage() + ") - the user received an error."
            );
        } catch (RuntimeException recordingFailure) {
            log.warn("Failed to record platform issue for agent {} fallback-also-failed", agentId, recordingFailure);
        }
    }

    /**
     * Records the "the whole hosted pool is down" case into the admin Issues
     * portal - the pool-level counterpart of {@link
     * #recordFallbackAlsoFailedIssue}, which only covers a BYOK agent's own
     * configured fallback. Fingerprinted on the agent, same best-effort posture
     * (an observability write must never be what breaks a turn that has already
     * failed). Only fires on {@link HostedPoolExhaustedException}, so a
     * fail-fast content-policy refusal or a guardrail block never lands here.
     */
    void recordHostedPoolExhaustedIssue(UUID agentId, Throwable error) {
        if (!(error instanceof HostedPoolExhaustedException)) {
            return;
        }
        PlatformIssueService service = platformIssueService.getIfAvailable();
        if (service == null) return;
        try {
            service.record(
                IssueSource.PROVIDER_FALLBACK,
                agentId.toString(),
                IssueSeverity.ERROR,
                "Platform-hosted LLM pool exhausted",
                "Agent " + agentId + ": " + error.getMessage()
                    + " - the user received an error. Check the hosted provider pool in Platform Admin."
            );
        } catch (RuntimeException recordingFailure) {
            log.warn("Failed to record platform issue for agent {} hosted-pool exhaustion", agentId, recordingFailure);
        }
    }

    /**
     * Best-effort: logs, records a {@link PlatformIssueService} entry for
     * observability, and returns a corrected version of {@code fullAnswer}
     * with the fabricated URL/signature replaced by an honest note - this is
     * what gets written to session memory instead of the raw fabrication, so
     * a later turn's history replay never re-reads the lie as established
     * fact. Never throws; falls back to persisting the original text
     * unmodified if the platform-issue write itself fails.
     */
    String recordAndCorrectFabricatedGenerationUrl(UUID agentId, String sessionId, String fullAnswer) {
        return recordAndCorrectFabricatedGenerationUrl(agentId, sessionId, fullAnswer, prompts.apply(agentId));
    }

    /**
     * As above, with the prompt settings the caller already holds for this turn (a caller on a
     * completion thread takes them in the turn's tenant).
     */
    String recordAndCorrectFabricatedGenerationUrl(
            UUID agentId, String sessionId, String fullAnswer, ResolvedDirectives pd) {
        log.warn(
            "Agent {} session {} produced a response containing a known fabricated generation-service "
                + "URL pattern with no corroborating tool call this turn - correcting the persisted history",
            agentId, sessionId
        );
        PlatformIssueService service = platformIssueService.getIfAvailable();
        if (service != null) {
            try {
                service.record(
                    IssueSource.LLM_QUALITY,
                    agentId.toString(),
                    IssueSeverity.WARNING,
                    "Fabricated generation-service URL detected",
                    "Agent " + agentId + " session " + sessionId + ": response referenced a known "
                        + "AI-generation-service storage domain with zero real tool calls made this turn - "
                        + "the model invented a plausible-looking file/media URL instead of using an "
                        + "actual generation tool or admitting it could not."
                );
            } catch (RuntimeException recordingFailure) {
                log.warn("Failed to record platform issue for agent {} fabricated-URL detection", agentId, recordingFailure);
            }
        }
        return pd.textOrShipped("message.fabricated_url.correction");
    }
}
