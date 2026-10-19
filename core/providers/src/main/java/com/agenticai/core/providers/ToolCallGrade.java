package com.agenticai.core.providers;

import java.util.Locale;

/**
 * How a hosted-pool model makes tool calls, measured by {@link ToolCallCapabilityProbe} when the
 * row is saved and stored in {@code platform_hosted_llm_provider.tool_call_grade} (V499).
 *
 * <ul>
 *   <li>{@link #STRUCTURED}: a canned two-tool prompt came back with a structured {@code
 *       tool_calls} entry. The provider's own tool loop works.
 *   <li>{@link #TEXT}: the reply was the call written as text (the shapes {@code
 *       core.chat.TextToolCallParser} recognises). Recovery and the constrained router apply.
 *   <li>{@link #NONE}: the model answered in prose, or the server says it has no tool support
 *       (Ollama {@code /api/show} capabilities without {@code "tools"}). Chat only.
 * </ul>
 */
public enum ToolCallGrade {
    STRUCTURED,
    TEXT,
    NONE;

    /** The stored value, or {@code null} for null, blank or unknown text (an older or newer row). */
    public static ToolCallGrade fromStored(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
