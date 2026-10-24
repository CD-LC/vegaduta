package com.agenticai.core.chat;

import java.util.List;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Where {@link FinishReasonAdvisor} leaves the finish reason of the model call it watched. The tool
 * loop runs inside Spring AI, so the application only ever sees the final text ({@code .content()});
 * the provider's stop reason is on the response metadata that call discards. One capture belongs to
 * one turn (or one call); {@link #begin()} runs when a new request starts, so a retry, a fallback
 * provider or a continuation pass is reported on its own, not mixed with the attempt before it.
 *
 * <p>The reason of a response stream is the LAST non-blank one: a tool-using stream carries
 * {@code tool_use} on its intermediate rounds and the real reason on the final one, and providers
 * that stream put the reason on the last data chunk. Thread-safe: chunks arrive on a client thread
 * and the reader is the turn.
 */
public final class FinishReasonCapture {

    private volatile String raw;
    private volatile boolean observed;

    /** Forgets the previous request's reason. Called by the advisor when a request starts. */
    public void begin() {
        raw = null;
        observed = false;
    }

    /** Records the finish reason of {@code response}, if it carries one. */
    public void observe(ChatResponse response) {
        if (response == null) {
            return;
        }
        List<Generation> generations = response.getResults();
        if (generations == null) {
            return;
        }
        for (Generation generation : generations) {
            ChatGenerationMetadata metadata = generation == null ? null : generation.getMetadata();
            String reason = metadata == null ? null : metadata.getFinishReason();
            if (reason != null && !reason.isBlank()) {
                raw = reason;
                observed = true;
            }
        }
    }

    /** The provider's own string, or {@code null} when none was reported (or no call ran). */
    public String raw() {
        return raw;
    }

    /** The reason in the shared vocabulary; {@link FinishReason#UNKNOWN} when none was reported. */
    public FinishReason reason() {
        return FinishReason.of(raw);
    }

    /** True once any finish reason was seen since {@link #begin()}. */
    public boolean wasObserved() {
        return observed;
    }
}
