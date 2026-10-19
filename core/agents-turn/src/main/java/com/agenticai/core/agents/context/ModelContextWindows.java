package com.agenticai.core.agents.context;

import java.util.Locale;

/**
 * Pure fallback for "how big is this model's context window" when the provider model catalog has no
 * {@code context_window} for it (a hand-typed id, a hosted-pool link, a model the catalog never
 * listed). The catalog value always wins; this only fills the gap.
 *
 * <p>The figures are deliberately CONSERVATIVE FLOORS for the model families named, not vendor
 * maximums: an underestimate only makes clearing start a little earlier on a huge-window model, while an
 * overestimate would let a run hit the real limit before any compaction fires. Anything unrecognised
 * falls back to the operator-configured {@code app.context.compaction.default-window-tokens}.
 */
public final class ModelContextWindows {

    /** Used when nothing is known about a model and the config does not say otherwise. */
    public static final int DEFAULT_WINDOW_TOKENS = 128_000;

    private ModelContextWindows() {
    }

    /**
     * @param catalogWindow the catalog's {@code context_window} for the model, may be null/zero
     * @param modelId the model id, may be null
     * @param fallback the configured default, used when neither source knows the model
     */
    public static int resolve(Integer catalogWindow, String modelId, int fallback) {
        if (catalogWindow != null && catalogWindow > 0) {
            return catalogWindow;
        }
        return heuristic(modelId, fallback > 0 ? fallback : DEFAULT_WINDOW_TOKENS);
    }

    /** Name-based floor for a handful of well-known families; {@code fallback} for everything else. */
    public static int heuristic(String modelId, int fallback) {
        if (modelId == null || modelId.isBlank()) {
            return fallback;
        }
        String id = modelId.toLowerCase(Locale.ROOT);
        if (id.contains("claude")) {
            return 200_000;
        }
        if (id.contains("gemini")) {
            return 200_000;
        }
        if (id.contains("gpt-5") || id.contains("gpt-4o") || id.contains("gpt-4.1") || id.contains("gpt-4-turbo")
            || id.startsWith("o1") || id.startsWith("o3")
            || id.startsWith("o4") || id.contains("/o1") || id.contains("/o3") || id.contains("/o4")) {
            return 128_000;
        }
        return fallback;
    }
}
