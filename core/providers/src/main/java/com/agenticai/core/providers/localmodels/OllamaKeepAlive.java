package com.agenticai.core.providers.localmodels;

/**
 * The JSON value Ollama accepts for {@code keep_alive}: a number of seconds (negative = keep the model loaded until the
 * server stops, 0 = unload now) or a duration string with a unit ({@code "30m"}, {@code "24h"}). A configured
 * {@code "-1"} must therefore go out as the number -1: as the string "-1" Ollama answers 400 (no unit), the model
 * load fails and the server falls back to its own five-minute default, so the next message after a short pause
 * reloads the model and re-reads the whole prompt.
 */
public final class OllamaKeepAlive {

    private OllamaKeepAlive() {}

    /** A Long or Double for a plain number, the trimmed text for a duration, or null when blank (send nothing). */
    public static Object jsonValue(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String value = configured.trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notWhole) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException notNumber) {
                return value;
            }
        }
    }
}
