package com.agenticai.core.agents.turn;

import static com.agenticai.core.agents.turn.ChatPromptDirectives.containsFabricatedGenerationUrl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import com.agenticai.core.agents.tools.ToolCallLogService;

/**
 * The fabricated-generation-URL guard: replies that invent a link to a generation service get
 * checked against the turn's tool-call evidence and, when nothing corroborates them, replaced by
 * the platform's honest correction. Extracted from {@link ChatTurnExecutor} (file-size ratchet)
 * with the {@link #collectToolEvidenceSince} read it shares; behavior is byte-for-byte what the
 * two methods had there - the javadoc below is theirs.
 *
 * <p>{@code prompts} is read through a supplier because {@link ChatTurnExecutor#prompts} is a
 * volatile field wired after construction ({@code ChatServiceParts}); capturing the field itself
 * would freeze the pre-wiring defaults forever.
 */
final class GenerationClaimGuard {

    private static final Logger log = LoggerFactory.getLogger(GenerationClaimGuard.class);

    private final ToolCallLogService toolCallLogService;
    private final Supplier<Function<UUID, ResolvedDirectives>> prompts;

    GenerationClaimGuard(
            ToolCallLogService toolCallLogService,
            Supplier<Function<UUID, ResolvedDirectives>> prompts) {
        this.toolCallLogService = toolCallLogService;
        this.prompts = prompts;
    }

    /**
     * The buffered form of the guard ({@code app.chat.parity.fast-buffering-mode} = ALL): joins the
     * finished reply, runs the fabricated-URL check against {@link
     * ChatPromptDirectives#containsFabricatedGenerationUrl}'s evidence, and emits either the real
     * text or the correction.
     *
     * <p>Errors on the upstream stream pass through unchanged; this only inspects a stream that
     * completed successfully.
     */
    Flux<String> buffer(
            TurnContext context,
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            Instant turnStartedAt) {
        // Taken here, on the request thread: the map below runs on the stream-completion thread,
        // and the object keeps the turn's tenant for its lookups.
        ResolvedDirectives pd = prompts.get().apply(agentId);
        return stream.collectList()
                .map(chunks -> String.join("", chunks))
                .map(
                        joined -> {
                            // A reply the output guardrails withheld (FastStreamTurn checked and
                            // stored it) is sent as the replacement, never as the text that was
                            // held.
                            String replacement = context.reply().replacement();
                            String fullText = replacement != null ? replacement : joined;
                            // The tool-log read runs in the turn's tenant scope (ADR-0022): this
                            // map fires on the stream-completion thread, not the request thread.
                            if (containsFabricatedGenerationUrl(fullText, pd)
                                    && context.scope()
                                                    .get(
                                                            () ->
                                                                    collectToolEvidenceSince(
                                                                            agentId,
                                                                            sessionId,
                                                                            turnStartedAt))
                                            == null) {
                                log.warn(
                                        "Agent {} session {} - buffered generation-adjacent reply"
                                                + " contained a fabricated generation-service URL with"
                                                + " no corroborating tool call; substituting an honest"
                                                + " correction before it ever reached the client",
                                        agentId,
                                        sessionId);
                                return pd.textOrShipped("message.fabricated_url.not_generated");
                            }
                            return fullText;
                        })
                .flatMapMany(Flux::just);
    }

    /**
     * The streamed form of {@link #buffer} ({@code app.chat.parity.fast-buffering-mode} =
     * FROM_FIRST_LINK): the reply streams as it is generated and only the part from the first link
     * on is held. When the finished reply carries an invented generation link (the same check, the
     * same tool evidence), the held part is replaced by the correction, so the link never reaches
     * the person; otherwise it is sent as written.
     */
    Flux<String> stream(
            TurnContext context,
            Flux<String> stream,
            UUID agentId,
            String sessionId,
            Instant turnStartedAt,
            List<String> holdMarkers) {
        // Taken here, on the request thread, as in buffer.
        ResolvedDirectives pd = prompts.get().apply(agentId);
        java.util.LinkedHashSet<String> markers = new java.util.LinkedHashSet<>(holdMarkers);
        markers.addAll(ChatPromptDirectives.fabricatedUrlHostMarkers(pd));
        return LinkHoldingStream.apply(
                stream,
                markers,
                (whole, held) -> {
                    if (containsFabricatedGenerationUrl(whole, pd)
                            && context.scope()
                                            .get(
                                                    () ->
                                                            collectToolEvidenceSince(
                                                                    agentId,
                                                                    sessionId,
                                                                    turnStartedAt))
                                    == null) {
                        log.warn(
                                "Agent {} session {} - streamed generation-adjacent reply contained a fabricated"
                                        + " generation-service URL with no corroborating tool call; the held part"
                                        + " was replaced by an honest correction before it reached the client",
                                agentId,
                                sessionId);
                        String correction =
                                pd.textOrShipped("message.fabricated_url.not_generated");
                        return whole.length() == held.length() ? correction : "\n\n" + correction;
                    }
                    return held;
                });
    }

    /**
     * The tool-call evidence for the turn, in the same since-filter shape {@code
     * firstToolFailureSince} and {@code chatWithParams}'s {@code ToolCallSummary} mapping already
     * use, just reading back tool name + output instead of only failures. Returns {@code null} when
     * no tool ran this turn, so a tool-free turn's checker prompts are byte-identical to before
     * this fix.
     */
    String collectToolEvidenceSince(UUID agentId, String sessionId, Instant since) {
        List<String> entries =
                toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                        .filter(entry -> entry.getStartedAt().isAfter(since))
                        .map(
                                entry ->
                                        entry.getToolName()
                                                + " -> "
                                                + (entry.getToolOutput() == null
                                                        ? ""
                                                        : entry.getToolOutput()))
                        .toList();
        return entries.isEmpty() ? null : String.join("\n", entries);
    }
}
