package com.agenticai.core.agents.modality;

import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Derives a {@link ModelModalities} profile ("can this model accept an image
 * / audio clip / video / PDF on a chat turn?") for a provider+model pair.
 * Three sources, in priority order:
 *
 * <ol>
 *   <li><b>Config override</b> - {@code app.chat.modalities.overrides} in
 *       application.yml, a map of model-name glob patterns to a
 *       comma-separated modality list (subset of {@code
 *       image,audio,video,document}, or {@code none} for text-only). Lets an
 *       operator correct or extend the table below without a code change,
 *       e.g. {@code "[my-finetune*]": image,document}. First matching
 *       pattern wins and REPLACES every other source for that model.</li>
 *   <li><b>Provider model catalog</b> - {@link ProviderModelCatalogService}
 *       already tracks per-model image/audio/video input flags (live +
 *       authoritative for OpenRouter via its {@code architecture.
 *       input_modalities}, hand-curated seed data for the direct providers).
 *       When the catalog knows the model, its three flags win over the
 *       heuristics; {@code document} still comes from the heuristic table
 *       since the catalog doesn't track it.</li>
 *   <li><b>Name-pattern heuristics</b> - {@link #DEFAULT_RULES}, a
 *       conservative glob table over well-known multimodal model families.
 *       All matching rules' grants are unioned (so {@code gpt-4o-audio-*}
 *       gets image from {@code gpt-4o*} plus audio from {@code *audio*}).</li>
 * </ol>
 *
 * <p><b>Honestly heuristic.</b> Anything no source recognizes resolves to
 * {@link ModelModalities#NONE} (text-only) - the failure mode is "attach
 * button withheld / attachment rejected with a clear 415", never "payload
 * sent to a model that can't read it". The table is deliberately
 * conservative (e.g. {@code document} only for Gemini, whose chat API's PDF
 * support is unambiguous) and WILL lag new model launches - that's what the
 * config override is for. A BYOK tenant naming an exotic model the catalog
 * has never heard of gets whatever these name patterns say, nothing more.
 *
 * <p>Also honest: for a FREE/platform-LLM tenant, chat turns actually run on
 * the hosted pool's model, not the agent's own provider/model pair - this
 * profile (like the existing Stage-4 upload gate in {@code
 * ChatAttachmentService}) is still resolved from the agent's pair, so it can
 * overstate/understate what the pooled model really accepts. Same posture as
 * Stage 4, documented rather than solved here.
 */
@Service
public class ModelModalitiesService {

    private static final Logger log = LoggerFactory.getLogger(ModelModalitiesService.class);

    static final String OVERRIDES_PROPERTY = "app.chat.modalities.overrides";

    /**
     * The one data-driven rule table (ops: extend via the config override
     * rather than editing this). Globs are matched case-insensitively
     * against both the full model id and its last {@code /}-segment (so
     * OpenRouter-style {@code openai/gpt-4o} ids match too); all matching
     * rows are unioned.
     */
    private static final List<Rule> DEFAULT_RULES = List.of(
        // OpenAI vision families
        Rule.of("gpt-4o*", "image"),
        Rule.of("chatgpt-4o*", "image"),
        Rule.of("gpt-4.1*", "image"),
        Rule.of("gpt-5*", "image"),
        Rule.of("o3*", "image"),
        Rule.of("o4*", "image"),
        // Anthropic - every Claude 3+ model is vision-capable
        Rule.of("claude-3*", "image"),
        Rule.of("claude-4*", "image"),
        Rule.of("claude-sonnet*", "image"),
        Rule.of("claude-opus*", "image"),
        Rule.of("claude-haiku*", "image"),
        // Gemini - the one mainstream family with native audio/video/PDF chat input
        Rule.of("gemini-*", "image", "audio", "video", "document"),
        // Meta Muse Spark (api.meta.ai, also meta/muse-spark-* via OpenRouter)
        // - text, image, video, PDF/file and audio input, 1M context (per
        // dev.meta.ai 2026-09-17; audio is degraded on 1.3 but accepted).
        // The catalog rows carry image/audio/video; "document" is only ever
        // granted here, same as gemini-*.
        Rule.of("muse-spark*", "image", "audio", "video", "document"),
        // Open-weight vision families
        Rule.of("llava*", "image"),
        Rule.of("pixtral*", "image"),
        Rule.of("qwen*vl*", "image"),
        // Anything explicitly audio-branded (e.g. gpt-4o-audio-preview)
        Rule.of("*audio*", "audio")
    );

    private final ProviderModelCatalogService providerModelCatalogService;
    private final List<Rule> overrideRules;

    public ModelModalitiesService(ProviderModelCatalogService providerModelCatalogService, Environment environment) {
        this.providerModelCatalogService = providerModelCatalogService;
        this.overrideRules = bindOverrides(environment);
    }

    /**
     * Never throws: a catalog hiccup (DB down mid-request) degrades to the
     * pure name heuristics, and a null/blank model is text-only.
     */
    public ModelModalities resolve(ProviderName provider, String model) {
        if (model == null || model.isBlank()) {
            return ModelModalities.NONE;
        }
        String normalized = model.toLowerCase(Locale.ROOT).trim();
        String lastSegment = normalized.contains("/")
            ? normalized.substring(normalized.lastIndexOf('/') + 1)
            : normalized;

        // 1. Operator override - first matching pattern wins outright.
        for (Rule rule : overrideRules) {
            if (rule.matches(normalized, lastSegment)) {
                return rule.grants();
            }
        }

        // 3 computed first so 2 can borrow its document flag.
        ModelModalities heuristic = fromRules(normalized, lastSegment);

        // 2. Catalog entry (image/audio/video authoritative when present).
        if (provider != null) {
            Optional<ProviderModelResponse> catalogEntry = safeCatalogLookup(provider, model);
            if (catalogEntry.isPresent()) {
                ProviderModelResponse entry = catalogEntry.get();
                return new ModelModalities(
                    entry.supportsImageInput(),
                    entry.supportsAudioInput(),
                    entry.supportsVideoInput(),
                    heuristic.document()
                );
            }
        }

        return heuristic;
    }

    private static ModelModalities fromRules(String normalized, String lastSegment) {
        boolean image = false;
        boolean audio = false;
        boolean video = false;
        boolean document = false;
        for (Rule rule : DEFAULT_RULES) {
            if (rule.matches(normalized, lastSegment)) {
                ModelModalities g = rule.grants();
                image |= g.image();
                audio |= g.audio();
                video |= g.video();
                document |= g.document();
            }
        }
        return new ModelModalities(image, audio, video, document);
    }

    private Optional<ProviderModelResponse> safeCatalogLookup(ProviderName provider, String model) {
        try {
            return providerModelCatalogService.findModel(provider, model);
        } catch (RuntimeException e) {
            log.debug("Model catalog lookup failed for {}/{} - falling back to name heuristics", provider, model, e);
            return Optional.empty();
        }
    }

    private static List<Rule> bindOverrides(Environment environment) {
        Map<String, String> raw;
        try {
            raw = Binder.get(environment)
                .bind(OVERRIDES_PROPERTY, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Could not bind {} - ignoring modality overrides", OVERRIDES_PROPERTY, e);
            return List.of();
        }
        List<Rule> rules = new ArrayList<>(raw.size());
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            boolean image = false;
            boolean audio = false;
            boolean video = false;
            boolean document = false;
            for (String token : entry.getValue().split(",")) {
                switch (token.trim().toLowerCase(Locale.ROOT)) {
                    case "image" -> image = true;
                    case "audio" -> audio = true;
                    case "video" -> video = true;
                    case "document", "pdf" -> document = true;
                    case "none", "text", "" -> { /* explicit text-only */ }
                    default -> log.warn(
                        "Unknown modality token '{}' in {}[{}] - ignored", token, OVERRIDES_PROPERTY, entry.getKey());
                }
            }
            rules.add(new Rule(globToPattern(entry.getKey()), new ModelModalities(image, audio, video, document)));
        }
        return List.copyOf(rules);
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toLowerCase(Locale.ROOT).trim().toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    private record Rule(Pattern pattern, ModelModalities grants) {

        static Rule of(String glob, String... modalities) {
            boolean image = false;
            boolean audio = false;
            boolean video = false;
            boolean document = false;
            for (String m : modalities) {
                switch (m) {
                    case "image" -> image = true;
                    case "audio" -> audio = true;
                    case "video" -> video = true;
                    case "document" -> document = true;
                    default -> throw new IllegalArgumentException("Unknown modality in rule table: " + m);
                }
            }
            return new Rule(globToPattern(glob), new ModelModalities(image, audio, video, document));
        }

        boolean matches(String normalized, String lastSegment) {
            return pattern.matcher(normalized).matches() || pattern.matcher(lastSegment).matches();
        }
    }
}
