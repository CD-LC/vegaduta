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
}
