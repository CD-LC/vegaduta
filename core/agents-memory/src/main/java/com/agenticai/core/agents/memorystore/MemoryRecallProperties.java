package com.agenticai.core.agents.memorystore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.recall.*}: how relevant a recalled long-term memory has to be to be put into a prompt, and how many
 * of them one turn gets. Before this, recall injected the top-k lines of each store whatever their similarity (about 3.8
 * items per turn on the real-pipeline memory eval, one in nine relevant).
 *
 * <p>Both apply to the <b>automatic recall</b> of facts and episodes only (the legacy private and shared facts in
 * {@code ChatMemoryRecall}, and the unified-memory composition). The always-in-context memory blocks (persona, profile,
 * project notes) and the self-model line are not affected, and neither are the explicit memory tools.
 *
 * <p>Off values restore the previous behaviour exactly: {@code minSimilarity <= 0} keeps every line the stores return and
 * {@code maxItems <= 0} leaves the per-store top-k and the unified item cap as they are.
 */
@ConfigurationProperties(prefix = "app.memory.recall")
public class MemoryRecallProperties {

    /** Shipped floor, see the field. */
    public static final double DEFAULT_MIN_SIMILARITY = 0.20;

    /** Shipped cap, see the field. */
    public static final int DEFAULT_MAX_ITEMS = 3;

    /**
     * Cosine similarity between the user's message and a recalled line below which the line is not injected (0 = no floor).
     * 0.20 is where the eval's reader starts to find a line usable; on the eval data it keeps 88% of the lines that the
     * probe needs and drops 92% of the unrelated ones (docs/architecture/phase1/04-measurement-scorecard.md, Addendum F).
     * Sentence-embedding models score related text well above it, so it is a floor against noise, not a ranking knob.
     */
    private double minSimilarity = DEFAULT_MIN_SIMILARITY;

    /** Most recalled facts and episodes one turn injects, private and shared and unified together (0 = the stores' own top-k). */
    private int maxItems = DEFAULT_MAX_ITEMS;

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = Math.max(0.0, Math.min(1.0, minSimilarity));
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = Math.max(0, maxItems);
    }

    /** Whether a line with this similarity to the message passes the floor. */
    public boolean passes(double similarity) {
        return minSimilarity <= 0.0 || similarity >= minSimilarity;
    }

    /** No floor, no cap: exactly the pre-floor behaviour (what a hand-built consumer without the bean uses). */
    public static MemoryRecallProperties off() {
        MemoryRecallProperties p = new MemoryRecallProperties();
        p.minSimilarity = 0.0;
        p.maxItems = 0;
        return p;
    }
}
