package com.agenticai.core.prompts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.prompts.*}: how the prompt registry behaves. The defaults are written here once;
 * {@code application.yml} repeats them and {@code docs/configuration.md} documents them.
 */
@ConfigurationProperties(prefix = "app.prompts")
public class PromptProperties {

    /** Locale of the default texts and of a context that names none. */
    private String defaultLocale = "en";

    /** Longest override of a key whose resource file declares no {@code maxLength}. */
    private int defaultMaxLength = 8000;

    /**
     * Characters per token for the estimate shown next to every block (a rule of thumb, not a
     * tokenizer; the preview says so).
     */
    private double charsPerToken = 4.0;

    /**
     * Token budget of the standing system text; the preview flags a prompt over it. 0 = no budget.
     */
    private int systemPromptTokenBudget = 0;

    /** Rows of history returned when the caller names no limit. */
    private int historyDefaultLimit = 50;

    /** Most rows of history one call returns. */
    private int historyMaxLimit = 500;

    /** Most rows one {@code GET /api/prompts} call returns when the caller names a {@code limit}. */
    private int listMaxLimit = 500;

    private final Cache cache = new Cache();

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale == null || defaultLocale.isBlank() ? "en" : defaultLocale.strip();
    }

    public int getDefaultMaxLength() {
        return defaultMaxLength;
    }

    public void setDefaultMaxLength(int defaultMaxLength) {
        this.defaultMaxLength = defaultMaxLength > 0 ? defaultMaxLength : 8000;
    }

    public double getCharsPerToken() {
        return charsPerToken;
    }

    public void setCharsPerToken(double charsPerToken) {
        this.charsPerToken = charsPerToken > 0 ? charsPerToken : 4.0;
    }

    public int getSystemPromptTokenBudget() {
        return systemPromptTokenBudget;
    }

    public void setSystemPromptTokenBudget(int systemPromptTokenBudget) {
        this.systemPromptTokenBudget = Math.max(0, systemPromptTokenBudget);
    }

    public int getHistoryDefaultLimit() {
        return historyDefaultLimit;
    }

    public void setHistoryDefaultLimit(int historyDefaultLimit) {
        this.historyDefaultLimit = historyDefaultLimit > 0 ? historyDefaultLimit : 50;
    }

    public int getHistoryMaxLimit() {
        return historyMaxLimit;
    }

    public void setHistoryMaxLimit(int historyMaxLimit) {
        this.historyMaxLimit = historyMaxLimit > 0 ? historyMaxLimit : 500;
    }

    public int getListMaxLimit() {
        return listMaxLimit;
    }

    public void setListMaxLimit(int listMaxLimit) {
        this.listMaxLimit = listMaxLimit > 0 ? listMaxLimit : 500;
    }

    public Cache getCache() {
        return cache;
    }

    /**
     * {@code app.prompts.cache.*}: the in-process cache of stored overrides. Multi-replica safety is
     * by expiry: a write evicts the writing replica at once, every other replica serves its cached
     * copy for at most {@code ttl}.
     */
    public static class Cache {

        /** How long a tenant's (or the platform's) overrides are served from memory. */
        private Duration ttl = Duration.ofSeconds(30);

        /** How long the defaults stand in after a failed load, before the next attempt. */
        private Duration failureTtl = Duration.ofSeconds(5);

        /** Tenants cached before the cache is cleared. */
        private int maxTenants = 10_000;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = positive(ttl, Duration.ofSeconds(30));
        }

        public Duration getFailureTtl() {
            return failureTtl;
        }

        public void setFailureTtl(Duration failureTtl) {
            this.failureTtl = positive(failureTtl, Duration.ofSeconds(5));
        }

        public int getMaxTenants() {
            return maxTenants;
        }

        public void setMaxTenants(int maxTenants) {
            this.maxTenants = maxTenants > 0 ? maxTenants : 10_000;
        }

        private static Duration positive(Duration value, Duration fallback) {
            return value == null || value.isNegative() || value.isZero() ? fallback : value;
        }
    }
}
