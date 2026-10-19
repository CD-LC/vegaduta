package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.StreamEndExecutor;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes {@code agent.message.completed} once an assistant reply is persisted, extracted
 * verbatim from {@link AgentChatService} (extract-only split). The publisher is still
 * setter-injected through {@link AgentChatService#setWorkflowEventPublisher}.
 */
final class AgentMessageEvents {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /**
     * Tenant webhooks (V241) - publishes {@code agent.message.completed} on the platform event bus
     * once an assistant reply is persisted. Setter-injected rather than a 41st constructor
     * parameter: a dozen test fixtures build this class by hand and several parallel workstreams
     * edit this file, so a constructor-signature change is a guaranteed merge conflict for work
     * that has nothing to do with events. {@code ObjectProvider} keeps slim contexts (no eventbus
     * beans) constructible - the emit helper is a no-op then.
     */
    private volatile WorkflowEventPublisher workflowEventPublisher;

    void setPublisher(WorkflowEventPublisher publisher) {
        this.workflowEventPublisher = publisher;
    }

    /**
     * Tenant webhooks (V241) - emits {@link WorkflowEventTypes#AGENT_MESSAGE_COMPLETED} once an
     * assistant reply has been persisted to session memory. Called from every persist site: cache
     * hits, the FAST stream-completion callback and the buffered-depth paths. {@code tenantId} is
     * passed as data (never re-read from {@code TenantContext}) because the streaming path calls
     * this on a completion worker thread where the ambient context is already gone - the
     * publisher's explicit-tenant overload exists for exactly this case. Never throws into the
     * turn.
     */
    void emitAgentMessageCompleted(
            UUID agentId, String sessionId, String tenantId, String answer, String source) {
        WorkflowEventPublisher publisher = this.workflowEventPublisher;
        if (publisher == null || answer == null) {
            return;
        }
        // After the reply is in session history (every caller appends first), off the streaming
        // thread when enabled.
        // Bind the tenant for the publish itself (belt and braces next to the publisher's own
        // explicit-tenant scope): this runs on the completion thread or a pool thread, neither of
        // which holds a TenantContext. TenantScope restores whatever the thread had.
        TenantScope scope =
                tenantId == null || tenantId.isBlank()
                        ? TenantScope.of((TenantContext) null)
                        : TenantScope.of(TenantContext.of(tenantId, "system:stream-end", Set.of()));
        streamEnd.run(
                () ->
                        scope.run(
                                () -> {
                                    try {
                                        String excerpt =
                                                answer.length() > 500
                                                        ? answer.substring(0, 500)
                                                        : answer;
                                        publisher.publish(
                                                WorkflowEventTypes.AGENT_MESSAGE_COMPLETED,
                                                sessionId,
                                                Map.of(
                                                        "agentId",
                                                                agentId == null
                                                                        ? ""
                                                                        : agentId.toString(),
                                                        "sessionId",
                                                                sessionId == null ? "" : sessionId,
                                                        "answerChars", answer.length(),
                                                        "answerExcerpt", excerpt,
                                                        "source", source == null ? "live" : source),
                                                tenantId);
                                    } catch (RuntimeException e) {
                                        log.warn(
                                                "agent.message.completed emission failed for agent {} session {} -"
                                                        + " the reply itself is unaffected",
                                                agentId,
                                                sessionId,
                                                e);
                                    }
                                }));
    }

    /** Set by {@link StreamEndWiring}; inline until then (hand-built fixtures, flag off). */
    private volatile StreamEndExecutor streamEnd = StreamEndExecutor.INLINE;

    void setStreamEnd(StreamEndExecutor streamEnd) {
        this.streamEnd = streamEnd == null ? StreamEndExecutor.INLINE : streamEnd;
    }
}
