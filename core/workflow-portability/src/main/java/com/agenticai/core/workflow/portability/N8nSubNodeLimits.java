package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportText;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounds what the n8n importer copies onto an agent for the AI components (models, tools, memory)
 * attached to it. Each kept item carries a sub-node's parameters (up to about 30 KB), and the
 * number of links in an n8n file is attacker-chosen, so without a bound a small file could make the
 * preview response huge.
 *
 * <p>One item per (parent, component, connection type) - repeated links are the same fact - at most
 * {@link #PER_PARENT} per parent and {@link #TOTAL} in the whole workflow. The first link refused
 * by a limit yields a one-off warning for that limit.
 */
final class N8nSubNodeLimits {

    static final int PER_PARENT = 20;
    static final int TOTAL = 100;

    enum Verdict {
        KEEP,
        SKIP,
        WARN_PARENT_FULL,
        WARN_TOTAL_FULL
    }

    private final Set<String> seen = new HashSet<>();
    private final Map<String, Integer> perParent = new HashMap<>();
    private final Set<String> warnedParents = new HashSet<>();
    private boolean warnedTotal;
    private int totalCount;

    Verdict admit(String parent, String component, String connection) {
        if (!seen.add(parent + "\u0000" + component + "\u0000" + connection)) {
            return Verdict.SKIP;
        }
        if (totalCount >= TOTAL) {
            return warnedTotal ? Verdict.SKIP : warnTotal();
        }
        int count = perParent.getOrDefault(parent, 0);
        if (count >= PER_PARENT) {
            return warnedParents.add(parent) ? Verdict.WARN_PARENT_FULL : Verdict.SKIP;
        }
        perParent.put(parent, count + 1);
        totalCount++;
        return Verdict.KEEP;
    }

    private Verdict warnTotal() {
        warnedTotal = true;
        return Verdict.WARN_TOTAL_FULL;
    }

    /** The warning text for a {@code WARN_*} verdict. */
    static String warning(Verdict verdict, String parent) {
        return verdict == Verdict.WARN_TOTAL_FULL
                ? "This workflow attaches more than "
                        + TOTAL
                        + " AI components; the rest are not kept on their agents."
                : "More than "
                        + PER_PARENT
                        + " AI components are attached to '"
                        + AgentImportText.inline(parent, 60)
                        + "'; only the first "
                        + PER_PARENT
                        + " are kept on it.";
    }
}
