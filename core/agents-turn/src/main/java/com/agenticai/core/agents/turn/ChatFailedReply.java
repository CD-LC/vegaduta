package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Keeps a turn that failed with NO text in the transcript (prod 2026-10-05: after "The AI provider
 * rejected our credentials" the reloaded session showed the question with nothing under it).
 * Extracted verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatFailedReply {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final SessionMemoryService sessionMemoryService;

    /**
     * The prompt registry's view for an agent, for the sentence stored when a failure has no message of
     * its own; the shipped text until {@link ChatServiceParts} wires the settings bean.
     */
    volatile java.util.function.Function<UUID, ResolvedDirectives> prompts =
            ResolvedDirectives.DEFAULTS_FOR_AGENT;

    ChatFailedReply(SessionMemoryService sessionMemoryService) {
        this.sessionMemoryService = sessionMemoryService;
    }

    /**
     * The user turn is appended before the stream starts; when the whole turn then errors without
     * having emitted anything, this appends the assistant side as {@link ChatTurn#failedAssistant}
     * carrying the same message the client is shown ({@link
     * AgentChatStreamResponder#midStreamFailureMessage}).
     *
     * <p>Placed on the OUTER stream of the turn, after provider fallback and the hosted chain: an
     * inner attempt that fails and is retried on another provider never reaches here, so no phantom
     * "failed" turn is written for a turn that went on to answer. A failure AFTER text was emitted
     * is the other case, already kept by {@code ChatTurnExecutor#withAssistantTurnPersistence} as
     * the partial reply plus {@link AgentChatService#INTERRUPTED_REPLY_MARKER}; this writes nothing
     * then. A cancelled turn completes rather than errors, so it writes nothing either.
     * Persistence must never mask the original error.
     */
    Flux<String> withFailedReplyPersistence(
            Flux<String> stream, UUID agentId, String sessionId, Supplier<ToolLedger> toolRecord) {
        AtomicBoolean emittedText = new AtomicBoolean(false);
        // Taken here, on the request thread: the error callback below has no tenant of its own, and
        // the object keeps this one for its lookup.
        ResolvedDirectives pd = prompts.apply(agentId);
        return stream.doOnNext(
                        chunk -> {
                            if (chunk != null && !chunk.isBlank()) {
                                emittedText.set(true);
                            }
                        })
                .doOnError(
                        error -> {
                            if (emittedText.get()) {
                                return;
                            }
                            persistFailedReply(agentId, sessionId, error, toolRecord.get(), pd);
                        });
    }

    /**
     * Builds the turn's reply stream and keeps a failure that is raised WHILE BUILDING it (not as a
     * stream error): the user turn is already in the transcript at this point, so a synchronous
     * throw here (a prompt that cannot be assembled, a hosted chain that cannot be built) would
     * otherwise leave it with no answer and no error after a reload.
     */
    Flux<String> buildingTurnStream(UUID agentId, String sessionId, Supplier<Flux<String>> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            persistFailedReply(agentId, sessionId, e, null, prompts.apply(agentId));
            throw e;
        }
    }

    /** Appends the failed assistant entry; never throws. */
    private void persistFailedReply(
            UUID agentId,
            String sessionId,
            Throwable error,
            ToolLedger toolRecord,
            ResolvedDirectives pd) {
        try {
            String generic = pd.textOrShipped(AgentChatStreamResponder.GENERIC_FAILURE_KEY);
            sessionMemoryService.appendTurn(
                    sessionId,
                    ChatTurn.failedAssistant(
                                    AgentChatStreamResponder.midStreamFailureMessage(error, generic), generic)
                            .withTools(toolRecord));
        } catch (RuntimeException persistFailure) {
            log.warn(
                    "Could not persist the failed reply for agent {} session {}",
                    agentId,
                    sessionId,
                    persistFailure);
        }
    }
}
