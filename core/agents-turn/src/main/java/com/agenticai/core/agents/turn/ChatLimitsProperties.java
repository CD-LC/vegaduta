package com.agenticai.core.agents.turn;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.limits.*}: limits of the turn classes that used to be constants - how short a reply must be
 * for the escalation router to read "I don't know" as low confidence, the size of a chat attachment, how long
 * the prompt-directive settings of a tenant are cached, how long a shared-agent visitor's turn may take. The
 * defaults are the old numbers, so nothing changes until an operator sets one.
 *
 * <p>The defaults are written here once; {@code application.yml} repeats them and {@code
 * ChatLimitsDefaultsTest} pins the two together. Registered by {@link ChatLimitsConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.limits")
public class ChatLimitsProperties {

    private final Escalation escalation = new Escalation();
    private final Attachments attachments = new Attachments();
    private final DirectiveCache directiveCache = new DirectiveCache();

    /** How long a visitor of a shared agent waits for the reply of one turn. */
    private Duration shareTurnTimeout = Duration.ofSeconds(45);

    /**
     * The economy model's auxiliary calls (such as the swarm goal verifier) get the agent's {@code maxTokens}
     * clamped to at least this ...
     */
    private int economyAuxMinTokens = 256;

    /** ... and at most this. */
    private int economyAuxMaxTokens = 1024;

    /** Characters of the answer carried by the "agent message completed" workflow event. */
    private int completedEventExcerptChars = 500;

    public Escalation getEscalation() {
        return escalation;
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public DirectiveCache getDirectiveCache() {
        return directiveCache;
    }

    public Duration getShareTurnTimeout() {
        return shareTurnTimeout;
    }

    public void setShareTurnTimeout(Duration shareTurnTimeout) {
        this.shareTurnTimeout = positive(shareTurnTimeout, Duration.ofSeconds(45));
    }

    public int getEconomyAuxMinTokens() {
        return economyAuxMinTokens;
    }

    public void setEconomyAuxMinTokens(int economyAuxMinTokens) {
        this.economyAuxMinTokens = Math.max(1, economyAuxMinTokens);
    }

    public int getEconomyAuxMaxTokens() {
        return economyAuxMaxTokens;
    }

    public void setEconomyAuxMaxTokens(int economyAuxMaxTokens) {
        this.economyAuxMaxTokens = Math.max(1, economyAuxMaxTokens);
    }

    public int getCompletedEventExcerptChars() {
        return completedEventExcerptChars;
    }

    public void setCompletedEventExcerptChars(int completedEventExcerptChars) {
        this.completedEventExcerptChars = Math.max(0, completedEventExcerptChars);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    /** {@code app.chat.limits.escalation.*}: the low-confidence reading and the cheap-client registration. */
    public static class Escalation {

        /** A "low confidence" marker counts only in a reply shorter than this many characters. */
        private int lowConfidenceMaxChars = 400;

        /** ... and only within the first this-many characters of it. */
        private int lowConfidenceLeadingChars = 240;

        /** How long a cheap client stays registered as escalatable. */
        private Duration registrationTtl = Duration.ofMinutes(10);

        public int getLowConfidenceMaxChars() {
            return lowConfidenceMaxChars;
        }

        public void setLowConfidenceMaxChars(int lowConfidenceMaxChars) {
            this.lowConfidenceMaxChars = Math.max(1, lowConfidenceMaxChars);
        }

        public int getLowConfidenceLeadingChars() {
            return lowConfidenceLeadingChars;
        }

        public void setLowConfidenceLeadingChars(int lowConfidenceLeadingChars) {
            this.lowConfidenceLeadingChars = Math.max(1, lowConfidenceLeadingChars);
        }

        public Duration getRegistrationTtl() {
            return registrationTtl;
        }

        public void setRegistrationTtl(Duration registrationTtl) {
            this.registrationTtl = positive(registrationTtl, Duration.ofMinutes(10));
        }
    }

    /** {@code app.chat.limits.attachments.*}: the largest chat attachment of each kind, in bytes. */
    public static class Attachments {

        private long maxImageBytes = 8L * 1024 * 1024;
        private long maxAudioBytes = 15L * 1024 * 1024;
        private long maxVideoBytes = 25L * 1024 * 1024;

        /** Documents and spreadsheets; matches the inline path's total-attachment ceiling. */
        private long maxDocumentBytes = 10L * 1024 * 1024;

        public long getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(long maxImageBytes) {
            this.maxImageBytes = Math.max(1, maxImageBytes);
        }

        public long getMaxAudioBytes() {
            return maxAudioBytes;
        }

        public void setMaxAudioBytes(long maxAudioBytes) {
            this.maxAudioBytes = Math.max(1, maxAudioBytes);
        }

        public long getMaxVideoBytes() {
            return maxVideoBytes;
        }

        public void setMaxVideoBytes(long maxVideoBytes) {
            this.maxVideoBytes = Math.max(1, maxVideoBytes);
        }

        public long getMaxDocumentBytes() {
            return maxDocumentBytes;
        }

        public void setMaxDocumentBytes(long maxDocumentBytes) {
            this.maxDocumentBytes = Math.max(1, maxDocumentBytes);
        }
    }

    /** {@code app.chat.limits.directive-cache.*}: the in-process cache of a tenant's prompt-directive settings. */
    public static class DirectiveCache {

        /** How long a tenant's loaded settings are served without reading the database. */
        private Duration ttl = Duration.ofSeconds(30);

        /** How long the built-in defaults stand in after a failed load. */
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
            this.maxTenants = Math.max(1, maxTenants);
        }
    }
}
