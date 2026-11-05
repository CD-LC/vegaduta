package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.memorystore.MemoryContentScreen;
import com.agenticai.core.knowledge.MemoryWriteScreen;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Screens unified-memory item text with the knowledge module's {@link MemoryWriteScreen}, the
 * normalization-first multilingual screen the legacy memory and knowledge write tools already use
 * (ADR-0028 WP4c). It augments, not replaces, {@code BasicMemoryContentScreen}: the store requires
 * every registered screen to pass.
 *
 * <p>Fail closed: a screen that throws rejects the text. The reason code is a fixed token, never
 * the matched text.
 */
@Component
@ConditionalOnProperty(prefix = "app.memory.unified", name = "enabled", havingValue = "true")
public class KnowledgeMemoryContentScreen implements MemoryContentScreen {

    static final String REJECTED = "WRITE_SCREEN";
    static final String UNAVAILABLE = "WRITE_SCREEN_UNAVAILABLE";

    @Override
    public Optional<String> reject(String text) {
        try {
            return MemoryWriteScreen.refusal(text).map(reason -> REJECTED);
        } catch (RuntimeException e) {
            return Optional.of(UNAVAILABLE);
        }
    }
}
