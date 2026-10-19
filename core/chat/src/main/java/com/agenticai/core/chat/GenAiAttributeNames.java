package com.agenticai.core.chat;

import java.util.List;
import java.util.Locale;

/**
 * The one place that decides which OpenTelemetry GenAI span-attribute NAMES this application emits
 * for attributes the semantic conventions renamed, so a backend (or a dashboard/alert query) can
 * migrate on its own schedule: {@code app.observability.genai-attrs} = {@code legacy | new | both}
 * (default {@code both}; an unknown or blank value also means {@code both}, the safe superset).
 *
 * <h2>What was renamed (verified 2026-10-03)</h2>
 * <ul>
 *   <li>{@code gen_ai.system} is deprecated and replaced by {@code gen_ai.provider.name}
 *       (semantic conventions v1.37.0, now maintained in
 *       https://github.com/open-telemetry/semantic-conventions-genai; deprecation table at
 *       https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/). The VALUE is
 *       unchanged here (the provider name this app already emitted); only the key differs.</li>
 *   <li>The token-usage names this application emits ({@code gen_ai.usage.input_tokens},
 *       {@code gen_ai.usage.output_tokens}, {@code gen_ai.usage.reasoning.output_tokens}) are
 *       already the CURRENT names: the old {@code gen_ai.usage.prompt_tokens} /
 *       {@code completion_tokens} were never emitted by this code, so they are deliberately not
 *       handled here (emitting them would add attributes, not rename them).</li>
 * </ul>
 * Span names, attribute values and every other attribute are untouched.
 *
 * <p>Pure and immutable: no Spring, no I/O. {@link LlmModelSupport} resolves the config value once
 * and hands the instance to the metrics advisors it builds.
 */
public final class GenAiAttributeNames {

    /** Deprecated provider key (pre-v1.37.0). */
    public static final String PROVIDER_LEGACY = "gen_ai.system";
    /** Current provider key. */
    public static final String PROVIDER_CURRENT = "gen_ai.provider.name";

    /** Which generation of attribute names to emit. */
    public enum Mode {
        LEGACY, NEW, BOTH;

        /** Case-insensitive; {@code null}, blank or unknown means {@link #BOTH}. */
        public static Mode parse(String raw) {
            if (raw == null) {
                return BOTH;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "legacy" -> LEGACY;
                case "new" -> NEW;
                default -> BOTH;
            };
        }
    }

    /** What an unconfigured advisor (tests, hand-built clients) emits: both generations. */
    public static final GenAiAttributeNames DEFAULT = new GenAiAttributeNames(Mode.BOTH);

    private final Mode mode;

    public GenAiAttributeNames(Mode mode) {
        this.mode = mode == null ? Mode.BOTH : mode;
    }

    /** Parses {@code app.observability.genai-attrs}. */
    public static GenAiAttributeNames of(String configured) {
        return new GenAiAttributeNames(Mode.parse(configured));
    }

    public Mode mode() {
        return mode;
    }

    /** Attribute key(s) to carry the provider name: legacy {@code gen_ai.system}, new {@code gen_ai.provider.name}, or both. */
    public List<String> providerKeys() {
        return switch (mode) {
            case LEGACY -> List.of(PROVIDER_LEGACY);
            case NEW -> List.of(PROVIDER_CURRENT);
            case BOTH -> List.of(PROVIDER_LEGACY, PROVIDER_CURRENT);
        };
    }
}
