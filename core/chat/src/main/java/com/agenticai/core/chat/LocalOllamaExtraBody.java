package com.agenticai.core.chat;

import java.util.Map;

/** Builds the Ollama-only request fields of a local pool row (split out of the factory). */
final class LocalOllamaExtraBody {

    private LocalOllamaExtraBody() {}

    /**
     * The Ollama-only fields of a local row's request: the context window, the per-request {@code
     * num_batch} (row override, else {@code app.llm.ollama.num-batch}) and how long the model stays
     * loaded (row keep-alive, else {@code app.local-models.keep-alive}).
     */
    static Map<String, Object> of(
            int numCtx,
            String rowKeepAlive,
            String defaultKeepAlive,
            Integer rowNumBatch,
            int defaultNumBatch) {
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        int batch = rowNumBatch != null ? rowNumBatch : defaultNumBatch;
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        if (numCtx > 0) {
            options.put("num_ctx", numCtx);
        }
        if (batch > 0) {
            options.put("num_batch", batch);
        }
        if (!options.isEmpty()) {
            extra.put("options", java.util.Collections.unmodifiableMap(options));
        }
        Object keepAlive =
                com.agenticai.core.providers.localmodels.OllamaKeepAlive.jsonValue(
                        rowKeepAlive != null && !rowKeepAlive.isBlank()
                                ? rowKeepAlive
                                : defaultKeepAlive);
        if (keepAlive != null) {
            extra.put("keep_alive", keepAlive);
        }
        return extra;
    }
}
