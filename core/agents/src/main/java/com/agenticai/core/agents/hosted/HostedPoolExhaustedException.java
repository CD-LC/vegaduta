package com.agenticai.core.agents.hosted;

import com.agenticai.core.chat.UpstreamProviderException;
import java.util.List;

/**
 * Every enabled provider in the platform-hosted pool was tried for this turn
 * and every one of them failed.
 *
 * <p>Extends {@link UpstreamProviderException} so {@code
 * GlobalExceptionHandler#handleUpstreamProvider} (and {@code
 * QuickTaskService}'s SSE error branch, and {@code
 * DelegateToAgentToolCallback}) keep working unchanged - they all read {@link
 * #getConsumerMessage()} and none of them need to know this subtype exists.
 *
 * <p>The reason it IS a distinct type: the message the last provider's own
 * error would have produced is actively misleading for a hosted-pool user. The
 * live production symptom - a Gemini row whose model id Google rejects with a
 * 404 - translates to "The selected AI model isn't available from this
 * provider anymore. Choose a different model for this agent," which a FREE
 * tenant cannot act on at all: they do not choose the model, the platform
 * does. So once the whole chain is exhausted the consumer message becomes
 * pool-shaped and honest ("our hosted service is down, not your request"),
 * while the technical message keeps the full per-provider detail for the
 * server log, the admin LLM Calls log, and the Issues portal.
 *
 * <p>Only ever thrown after the chain is EXHAUSTED - including the same-turn
 * synchronous self-heal retry ({@code
 * AgentChatService#syncHealChainExhaustionAndRebuild}), so this really is "the
 * platform has nothing left to try", not just one dead row. A failure that is
 * not retryable (content policy, context length - see {@link
 * HostedFailoverPolicy}) still surfaces its own original exception with its
 * own actionable consumer message, unwrapped.
 *
 * <p>{@link #isEdgeAiRecoverable()} is {@code true} - deterministic recovery
 * chain per the platform's fallback contract is Selected Model -> Platform LLM
 * -> Edge AI -> user-facing error; this exception marks exactly the "Platform
 * LLM step exhausted, try Edge AI before giving up" point in that chain (Edge
 * AI itself only runs client-side, so this server-side type can only signal
 * it, not invoke it).
 */
public class HostedPoolExhaustedException extends UpstreamProviderException {

    /**
     * 503, not the first provider's upstream status: from the caller's point
     * of view what happened is "the hosted service is unavailable", and the
     * individual upstream status (a 404 on one row's model id) no longer
     * describes the failure the API is reporting. The real per-provider status
     * is preserved verbatim inside the technical message and the cause.
     */
    private static final int SERVICE_UNAVAILABLE = 503;

    /**
     * Worded for every install, not only the hosted service: on a personal or self-hosted install
     * the "pool" is the operator's own model (often a local Ollama), and "contact support" sent
     * the one person who could fix it looking for someone else. It names where the per-model
     * error is kept instead (the ERROR line HostedChainFailover writes).
     */
    private static final String CONSUMER_MESSAGE =
        "The AI model didn't respond - every model this workspace can use failed on this message. "
            + "Try again in a minute. If it keeps happening, the model may be stopped, still loading or "
            + "out of memory: whoever runs this install can see which one failed, and why, in the "
            + "server log.";

    public HostedPoolExhaustedException(List<HostedChainLink> chain, Throwable firstError) {
        super(SERVICE_UNAVAILABLE, buildTechnicalMessage(chain, firstError), CONSUMER_MESSAGE, null, true);
        initCause(firstError);
    }

    private static String buildTechnicalMessage(List<HostedChainLink> chain, Throwable firstError) {
        StringBuilder sb = new StringBuilder("All ")
            .append(chain.size())
            .append(" enabled platform-hosted provider(s) failed for this turn [");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            HostedChainLink link = chain.get(i);
            sb.append(link.label()).append('/').append(link.model());
        }
        sb.append("]; first error: ").append(firstError == null ? "unknown" : firstError.getMessage());
        return sb.toString();
    }
}
