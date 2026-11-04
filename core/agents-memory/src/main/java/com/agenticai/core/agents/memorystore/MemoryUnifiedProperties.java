package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.unified.*}: the platform level of the unified-memory flags (ADR-0028 section
 * 11) and the prompt budget (section 9). Everything is OFF until an operator sets it; the tenant
 * and agent levels can only switch things off further (see {@link MemoryUnifiedGate}).
 */
@ConfigurationProperties(prefix = "app.memory.unified")
public class MemoryUnifiedProperties {

    /** Platform kill switch: off means no unified recall, no proposals. */
    private boolean enabled = false;

    /**
     * Non-semantic kinds the platform allows (episodic, procedural, self-model); empty means none.
     * SEMANTIC is the existing path and is not governed by this list.
     */
    private Set<MemoryKind> kinds = EnumSet.noneOf(MemoryKind.class);

    /** Token cap for all recalled memory text in one prompt. */
    private int totalTokens = 800;

    /** Token cap for one recalled item; longer text is cut at a sentence boundary or dropped. */
    private int perItemTokens = 120;

    /** Most items one prompt receives. */
    private int maxItems = 8;

    /**
     * Deepest generation a stored item may have (0 = human or raw event, 1 = a model summarised
     * events, 2 = a model summarised model output). A proposal or approval beyond it is refused.
     */
    private int maxGeneration = 2;

    /** Longest item text, in characters. */
    private int maxItemChars = 1200;

    /** Most PENDING plus ACTIVE items one agent holds; a proposal beyond it is refused. */
    private int maxItemsPerAgent = 2000;

    /** Most PENDING items one agent holds; a full queue refuses new proposals instead of dropping old ones. */
    private int maxPendingPerAgent = 100;

    /** Most proposals one agent may create in a rolling hour. */
    private int maxWritesPerHourPerAgent = 60;

    /** Days an EPISODIC item lives before it expires. */
    private int episodicTtlDays = 90;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<MemoryKind> getKinds() {
        return kinds;
    }

    public void setKinds(Set<MemoryKind> kinds) {
        this.kinds = kinds == null || kinds.isEmpty() ? EnumSet.noneOf(MemoryKind.class) : EnumSet.copyOf(kinds);
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = Math.max(0, totalTokens);
    }

    public int getPerItemTokens() {
        return perItemTokens;
    }

    public void setPerItemTokens(int perItemTokens) {
        this.perItemTokens = Math.max(1, perItemTokens);
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = Math.max(0, maxItems);
    }

    public int getMaxGeneration() {
        return maxGeneration;
    }

    public void setMaxGeneration(int maxGeneration) {
        this.maxGeneration = Math.max(0, Math.min(8, maxGeneration));
    }

    public int getMaxItemChars() {
        return maxItemChars;
    }

    public void setMaxItemChars(int maxItemChars) {
        this.maxItemChars = Math.max(1, maxItemChars);
    }

    public int getMaxItemsPerAgent() {
        return maxItemsPerAgent;
    }

    public void setMaxItemsPerAgent(int maxItemsPerAgent) {
        this.maxItemsPerAgent = Math.max(0, maxItemsPerAgent);
    }

    public int getMaxPendingPerAgent() {
        return maxPendingPerAgent;
    }

    public void setMaxPendingPerAgent(int maxPendingPerAgent) {
        this.maxPendingPerAgent = Math.max(0, maxPendingPerAgent);
    }

    public int getMaxWritesPerHourPerAgent() {
        return maxWritesPerHourPerAgent;
    }

    public void setMaxWritesPerHourPerAgent(int maxWritesPerHourPerAgent) {
        this.maxWritesPerHourPerAgent = Math.max(0, maxWritesPerHourPerAgent);
    }

    public int getEpisodicTtlDays() {
        return episodicTtlDays;
    }

    public void setEpisodicTtlDays(int episodicTtlDays) {
        this.episodicTtlDays = Math.max(1, episodicTtlDays);
    }
}
