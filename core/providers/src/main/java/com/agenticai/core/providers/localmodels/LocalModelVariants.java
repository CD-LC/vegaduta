package com.agenticai.core.providers.localmodels;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Console-made local-model variants: {@code base-ctxNk} and {@code base-ctxNk-parM} tags, where a
 * variant carries the context window Ollama's per-request {@code num_ctx} cannot always reach
 * and/or the runner-level {@code num_parallel} (never a per-request option). A variant is derived
 * through {@link LocalModelOllamaGateway#createVariant} (weights reused, no download) and the
 * serving row is re-pointed - and re-measured - through the pool service, like any model change.
 * Split out of {@link LocalModelsService} under the file-size ratchet.
 */
final class LocalModelVariants {

    private LocalModelVariants() {}

    /** Context windows the console offers; 0 = the server default. */
    static final java.util.Set<Integer> CONTEXTS = java.util.Set.of(0, 4096, 8192, 16384, 32768);

    /** num_parallel values the console offers; 0 = the server default. */
    static final java.util.Set<Integer> PARALLELS = java.util.Set.of(0, 1, 2, 4);

    private static final Pattern CTX_SUFFIX =
            Pattern.compile("^(.+)-ctx([0-9]+)k(?:-par([0-9]+))?$");

    private static final Pattern PAR_SUFFIX = Pattern.compile("^(.+)-par([0-9]+)$");

    /**
     * The base tag of a console-made variant ({@code qwen3.5:4b-ctx16k}, {@code
     * qwen3.5:4b-ctx16k-par2} or {@code qwen3.5:4b-par2} -> {@code qwen3.5:4b}); any other tag as
     * it is.
     */
    static String baseTag(String model) {
        java.util.regex.Matcher m = model == null ? null : CTX_SUFFIX.matcher(model);
        if (m != null && m.matches()) {
            return m.group(1);
        }
        m = model == null ? null : PAR_SUFFIX.matcher(model);
        return m != null && m.matches() ? m.group(1) : model;
    }

    /**
     * The context a console-made variant carries ({@code -ctx16k} -> 16384, with or without a
     * {@code -parM} trailer), or null for a plain tag.
     */
    static Integer contextOf(String model) {
        java.util.regex.Matcher m = model == null ? null : CTX_SUFFIX.matcher(model);
        return m != null && m.matches() ? Integer.valueOf(m.group(2)) * 1024 : null;
    }

    /**
     * The runner parallelism a console-made variant carries ({@code -par2} -> 2, with or without a
     * leading {@code -ctxNk}), or null for a plain tag.
     */
    static Integer parallelOf(String model) {
        java.util.regex.Matcher m = model == null ? null : CTX_SUFFIX.matcher(model);
        if (m != null && m.matches()) {
            return m.group(3) == null ? null : Integer.valueOf(m.group(3));
        }
        m = model == null ? null : PAR_SUFFIX.matcher(model);
        return m != null && m.matches() ? Integer.valueOf(m.group(2)) : null;
    }

    /** The tag a variant of {@code base} with this context and parallelism gets. */
    private static String variantTag(String base, Integer context, Integer parallel) {
        String tag = base;
        if (context != null && context > 0) {
            tag += "-ctx" + (context / 1024) + "k";
        }
        if (parallel != null && parallel > 0) {
            tag += "-par" + parallel;
        }
        return tag;
    }

    /**
     * Points the serving row at a {@code -ctxNk}/{@code -parM} variant of the row's base, or the
     * base tag itself when both knobs are the server default. {@code context} null keeps the
     * variant's current window, 0 returns it to the server default; {@code parallel} likewise.
     */
    static void apply(
            LocalModelOllamaGateway ollama,
            PlatformHostedLlmProviderService hostedPool,
            PlatformHostedLlmProvider row,
            Integer context,
            Integer parallel) {
        if (context != null && !CONTEXTS.contains(context)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Context must be 4096, 8192, 16384, 32768 or 0 for the server default.");
        }
        if (parallel != null && !PARALLELS.contains(parallel)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Parallel requests must be 1, 2, 4 or 0 for the server default.");
        }
        Integer ctx = context == null ? contextOf(row.getModel()) : context;
        Integer par = parallel == null ? parallelOf(row.getModel()) : parallel;
        String base = baseTag(row.getModel());
        String target = variantTag(base, ctx, par);
        if (target.equals(row.getModel())) {
            return;
        }
        LocalModelOllamaGateway.Tags tags = ollama.tags();
        if (!tags.reachable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The model server is not reachable.");
        }
        if (!tags.has(target) && !ollama.createVariant(base, target, ctx, par)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The model server could not make that variant of " + base + ".");
        }
        hostedPool.updateModel(row.getId(), target);
    }
}
