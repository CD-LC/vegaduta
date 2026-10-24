package com.agenticai.core.agents.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.chat.small-model.*}: how small-model mode (a weak tool caller: a small or text-graded hosted
 * row, or a local model) chooses the few tools it offers. These were constants of {@link
 * AgentToolSelection} (6 tools, 4 for a browsing request, 50 for an intent match, 1000 for a tool named in
 * the message); the values here are those numbers.
 *
 * <p>{@code application.yml} repeats the defaults and {@code SmallModelDefaultsTest} pins the two together.
 * Registered by {@link SmallModelConfiguration}.
 */
@ConfigurationProperties(prefix = "app.chat.small-model")
public class SmallModelProperties {

    /** Tools offered on one small-model turn; six is what 3B-8B models route between reliably. */
    private int toolCap = 6;

    /** Tools a browsing request keeps (navigate, read, click, type work only as a set). */
    private int browserFamilyQuota = 4;

    /** Score of a tool serving a signalled intent; beats any lexical overlap, loses to a named tool. */
    private int intentBoost = 50;

    /** Score of a tool the message names outright; nothing outranks it by default. */
    private int namedToolScore = 1_000;

    public int getToolCap() {
        return toolCap;
    }

    public void setToolCap(int toolCap) {
        this.toolCap = Math.max(1, toolCap);
    }

    public int getBrowserFamilyQuota() {
        return browserFamilyQuota;
    }

    public void setBrowserFamilyQuota(int browserFamilyQuota) {
        this.browserFamilyQuota = Math.max(1, browserFamilyQuota);
    }

    public int getIntentBoost() {
        return intentBoost;
    }

    public void setIntentBoost(int intentBoost) {
        this.intentBoost = Math.max(0, intentBoost);
    }

    public int getNamedToolScore() {
        return namedToolScore;
    }

    public void setNamedToolScore(int namedToolScore) {
        this.namedToolScore = Math.max(1, namedToolScore);
    }
}
