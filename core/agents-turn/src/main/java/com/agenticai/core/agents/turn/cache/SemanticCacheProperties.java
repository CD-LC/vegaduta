package com.agenticai.core.agents.turn.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.semantic-cache.*}: the opt-in similarity tier of the response cache ({@link
 * SemanticResponseCacheService}). These were {@code @Value} reads of the service with the same defaults; they are a
 * properties object so the settings catalog ({@code chat.semanticCache.*}) can read the platform value from the same
 * place the service does. {@code application.yml} repeats the defaults.
 */
@ConfigurationProperties(prefix = "app.chat.semantic-cache")
public class SemanticCacheProperties {

    /** Cosine similarity a stored question needs to be served for a new one: only near-identical phrasings match. */
    private double similarityThreshold = 0.95;

    /** Hours a stored answer is served. */
    private long ttlHours = 6;

    /** Stored answers kept per agent; the oldest are dropped past it. */
    private int maxEntriesPerAgent = 200;

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public long getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(long ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getMaxEntriesPerAgent() {
        return maxEntriesPerAgent;
    }

    public void setMaxEntriesPerAgent(int maxEntriesPerAgent) {
        this.maxEntriesPerAgent = maxEntriesPerAgent;
    }
}
