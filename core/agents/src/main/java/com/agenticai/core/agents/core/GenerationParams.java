package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An agent's generation and sampling controls (max tokens, temperature, top-p and the optional
 * extra sampling controls). Embedded in {@link Agent}; the columns keep their names, so there is no
 * migration. Immutable by convention: every {@code with...} returns a modified copy and {@link Agent}
 * swaps its reference.
 */
@Embeddable
public class GenerationParams {

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens;

    @Column(nullable = false)
    private double temperature;

    // Nucleus sampling cutoff (Anthropic/most providers: 0-1, 1.0 = no
    // narrowing, the provider's own default) - added alongside temperature
    // and maxTokens getting surfaced in the console for the first time
    // (direct feedback: "customer should be able to select the temperature,
    // p and all other details"). Migration V40 backfills every pre-existing
    // agent to 1.0 (a no-op), matching "not set" behavior before this column
    // existed.
    @Column(name = "top_p", nullable = false)
    private double topP;

    // Extra sampling controls (migration V331) - all optional; null / empty
    // means "not set, let the provider apply its own default" and is exactly
    // the pre-V331 behavior. Only providers whose API supports a given field
    // receive it (see core/chat SamplingOptions); the rest silently omit it.
    @Column(name = "top_k")
    private Integer topK;

    @jakarta.persistence.Convert(converter = StringListJsonConverter.class)
    @Column(name = "stop_sequences", columnDefinition = "text", nullable = false)
    private List<String> stopSequences = new ArrayList<>();

    @Column(name = "frequency_penalty")
    private Double frequencyPenalty;

    @Column(name = "presence_penalty")
    private Double presencePenalty;

    @Column(name = "seed")
    private Integer seed;

    /** JPA. */
    protected GenerationParams() {}

    private GenerationParams(GenerationParams other) {
        this.maxTokens = other.maxTokens;
        this.temperature = other.temperature;
        this.topP = other.topP;
        this.topK = other.topK;
        this.stopSequences = other.stopSequences == null ? new ArrayList<>() : new ArrayList<>(other.stopSequences);
        this.frequencyPenalty = other.frequencyPenalty;
        this.presencePenalty = other.presencePenalty;
        this.seed = other.seed;
    }

    public static GenerationParams of(int maxTokens, double temperature, double topP) {
        GenerationParams p = new GenerationParams();
        p.maxTokens = maxTokens;
        p.temperature = temperature;
        p.topP = topP;
        return p;
    }

    public GenerationParams withCore(int maxTokens, double temperature, double topP) {
        GenerationParams p = new GenerationParams(this);
        p.maxTokens = maxTokens;
        p.temperature = temperature;
        p.topP = topP;
        return p;
    }

    public GenerationParams withSampling(
            Integer topK, List<String> stopSequences, Double frequencyPenalty, Double presencePenalty, Integer seed) {
        GenerationParams p = new GenerationParams(this);
        p.topK = topK;
        p.stopSequences = stopSequences == null ? new ArrayList<>() : new ArrayList<>(stopSequences);
        p.frequencyPenalty = frequencyPenalty;
        p.presencePenalty = presencePenalty;
        p.seed = seed;
        return p;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public double getTopP() {
        return topP;
    }

    public Integer getTopK() {
        return topK;
    }

    /** A defensive copy; empty (never null) when no stop sequences are set. */
    public List<String> getStopSequences() {
        return stopSequences == null ? List.of() : List.copyOf(stopSequences);
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public Integer getSeed() {
        return seed;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GenerationParams g
                && maxTokens == g.maxTokens
                && Double.compare(temperature, g.temperature) == 0
                && Double.compare(topP, g.topP) == 0
                && Objects.equals(topK, g.topK)
                && Objects.equals(getStopSequences(), g.getStopSequences())
                && Objects.equals(frequencyPenalty, g.frequencyPenalty)
                && Objects.equals(presencePenalty, g.presencePenalty)
                && Objects.equals(seed, g.seed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxTokens, temperature, topP, topK, getStopSequences(), frequencyPenalty, presencePenalty, seed);
    }
}
