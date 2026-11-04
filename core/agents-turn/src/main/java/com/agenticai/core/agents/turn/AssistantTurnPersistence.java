package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.AgentChatService.INTERRUPTED_REPLY_MARKER;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * Persists the assistant turn of a streamed reply exactly once, on completion or (marked as
 * interrupted) on error or cancel. See {@link ChatTurnExecutor#withAssistantTurnPersistence}.
 */
final class AssistantTurnPersistence {

    private AssistantTurnPersistence() {}

    static Flux<String> wrap(
            SessionMemoryService sessionMemoryService,
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            String memorySessionId,
            StringBuilder accumulated,
            Consumer<String> onCompleteFullAnswer,
            Supplier<ToolLedger> toolRecord) {
        AtomicBoolean assistantTurnPersisted = new AtomicBoolean(false);
        return stream.doOnNext(accumulated::append)
                .doOnComplete(
                        () -> {
                            if (!accumulated.isEmpty()
                                    && assistantTurnPersisted.compareAndSet(false, true)) {
                                onCompleteFullAnswer.accept(accumulated.toString());
                            }
                        })
                .doOnError(
                        e -> {
                            ChatTurnExecutor.log.error(
                                    "Chat stream failed for agent {} session {}",
                                    agentId,
                                    sessionId,
                                    e);
                            persistInterrupted(sessionMemoryService, memorySessionId,
                                    accumulated,
                                    assistantTurnPersisted,
                                    toolRecord);
                        })
                .doOnCancel(
                        () ->
                                persistInterrupted(sessionMemoryService, memorySessionId,
                                        accumulated,
                                        assistantTurnPersisted,
                                        toolRecord));
    }

    /**
     * The error/cancel half of {@link #wrap}: persist the partial answer
     * once, marked, and never let a persistence failure (e.g. Redis down at the exact moment the
     * stream died) mask the original stream error on its way to the client.
     */
    private static void persistInterrupted(
            SessionMemoryService sessionMemoryService,
            String sessionId,
            StringBuilder accumulated,
            AtomicBoolean assistantTurnPersisted,
            Supplier<ToolLedger> toolRecord) {
        String partial = accumulated.toString();
        if (partial.isEmpty() || !assistantTurnPersisted.compareAndSet(false, true)) {
            return;
        }
        try {
            sessionMemoryService.appendTurn(
                    sessionId,
                    ChatTurn.assistant(partial + INTERRUPTED_REPLY_MARKER, toolRecord.get()));
        } catch (RuntimeException persistFailure) {
            ChatTurnExecutor.log.warn(
                    "Could not persist interrupted assistant turn for session {}",
                    sessionId,
                    persistFailure);
        }
    }
}
