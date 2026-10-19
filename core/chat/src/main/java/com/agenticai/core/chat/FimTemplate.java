package com.agenticai.core.chat;

import java.util.Locale;

/**
 * Which fill-in-the-middle sentinel triple, if any, the model behind a
 * platform-hosted pool row was trained on - the "check what the resolved
 * model actually supports" half of {@link FimPrompt}.
 *
 * <p><b>This is a model-id heuristic, not a probe.</b> Nothing in this
 * codebase asks a provider what templates a model understands ({@code
 * ProviderModelCatalogService} returns ids and an LLM-written capability
 * SUMMARY, not a template contract), and the pool rows themselves carry no
 * capability column - see {@code PlatformHostedLlmProvider}'s fields
 * ({@code model}, {@code baseUrl}, {@code completionsPath}, priority,
 * enabled). So the only signal available at call time is the model id on the
 * row, and the honest default for an id this enum does not recognise is
 * {@link #NONE} - the instruct-style fallback prompt, which works on any
 * chat model.
 *
 * <p><b>Only families whose sentinel triple is unambiguous are listed.</b>
 * DeepSeek-Coder and Codestral are deliberately absent: their sentinels are
 * not the plain ASCII forms below (DeepSeek's use non-ASCII bar characters
 * that have to be copied out of the tokenizer rather than typed from
 * memory), and a WRONG sentinel is worse than no sentinel - it is emitted
 * verbatim into the prompt as ordinary text and silently degrades every
 * completion. Adding a family here should mean someone checked that
 * family's tokenizer, not that they recognised the name.
 *
 * <p><b>Delivery caveat.</b> The hosted pool speaks chat-completions
 * ({@code OpenAiApi}'s {@code /v1/chat/completions}, or Google GenAI - see
 * {@link HostedLlmChatClientFactory}); there is no raw-completion/infill
 * path wired anywhere in core. A sentinel-templated prompt therefore goes
 * out as a chat USER message rather than as a raw completion prefix. For a
 * FIM-trained instruct checkpoint that is the normal way it is served, but
 * it is not the same thing as hitting a dedicated {@code /v1/fim/completions}
 * endpoint, and it is why {@link FimResponseCleaner} still has to strip
 * chat-shaped noise (fences, echoed context) from the reply.
 */
enum FimTemplate {

    /** No known sentinel triple - use the instruct-style fallback prompt. */
    NONE(null, null, null),

    /**
     * {@code <fim_prefix> / <fim_suffix> / <fim_middle>} - the StarCoder-family
     * spelling (StarCoder, StarCoder2, CodeGemma).
     */
    ANGLE("<fim_prefix>", "<fim_suffix>", "<fim_middle>"),

    /**
     * {@code <|fim_prefix|> / <|fim_suffix|> / <|fim_middle|>} - the pipe-wrapped
     * spelling used by the Qwen coder checkpoints (CodeQwen, Qwen2.5-Coder).
     */
    PIPE("<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>"),

    /** {@code <PRE> / <SUF> / <MID>} - the Code Llama infilling spelling. */
    CODE_LLAMA("<PRE>", "<SUF>", "<MID>");

    private final String prefixToken;
    private final String suffixToken;
    private final String middleToken;

    FimTemplate(String prefixToken, String suffixToken, String middleToken) {
        this.prefixToken = prefixToken;
        this.suffixToken = suffixToken;
        this.middleToken = middleToken;
    }

    boolean supported() {
        return this != NONE;
    }

    /**
     * The prefix-suffix-middle rendering: context before the cursor, context
     * after it, then the token that hands the turn to the model. All three
     * listed families are trained in this order.
     */
    String render(String prefix, String suffix) {
        return prefixToken + prefix + suffixToken + suffix + middleToken;
    }

    /**
     * The triple this model id was trained on, or {@link #NONE} for anything
     * unrecognised (including {@code null}). Matched on substrings of the
     * lower-cased id because pool rows carry vendor-namespaced ids - Groq
     * serves {@code "qwen-2.5-coder-32b"}, OpenRouter the same checkpoint as
     * {@code "qwen/qwen-2.5-coder-32b-instruct"} - so an exact-id table would
     * miss the same model under a different vendor's naming.
     */
    static FimTemplate forModel(String model) {
        if (model == null || model.isBlank()) {
            return NONE;
        }
        String id = model.toLowerCase(Locale.ROOT);
        // Ordered most-specific first: "codeqwen"/"qwen*coder" before any
        // generic match, and codellama before a bare "llama" would ever apply
        // (it does not today - plain Llama chat models are not FIM-trained and
        // must fall through to NONE).
        if (id.contains("codeqwen") || (id.contains("qwen") && id.contains("coder"))) {
            return PIPE;
        }
        if (id.contains("codellama") || id.contains("code-llama")) {
            return CODE_LLAMA;
        }
        if (id.contains("starcoder") || id.contains("codegemma")) {
            return ANGLE;
        }
        return NONE;
    }
}
