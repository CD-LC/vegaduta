package com.agenticai.core.agents.memorystore;

import java.util.Optional;

/**
 * The always-on screen: non-blank, within {@code max-item-chars}, and no control characters other
 * than newline and tab.
 */
public class BasicMemoryContentScreen implements MemoryContentScreen {

    private final MemoryUnifiedProperties properties;

    public BasicMemoryContentScreen(MemoryUnifiedProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> reject(String text) {
        if (text == null || text.isBlank()) {
            return Optional.of("BLANK");
        }
        if (text.length() > properties.getMaxItemChars()) {
            return Optional.of("TOO_LONG");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r') {
                return Optional.of("CONTROL_CHAR");
            }
        }
        return Optional.empty();
    }
}
