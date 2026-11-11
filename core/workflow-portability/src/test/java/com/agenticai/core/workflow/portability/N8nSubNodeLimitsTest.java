package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The caps on AI components (models, tools, memory) copied onto an agent from n8n links: one item
 * per (parent, component, connection type), at most {@code PER_PARENT} per agent and {@code TOTAL}
 * per file, with each limit warning exactly once.
 */
class N8nSubNodeLimitsTest {

    @Test
    void aNewTripleIsKeptAndARepeatIsSkipped() {
        N8nSubNodeLimits limits = new N8nSubNodeLimits();
        assertThat(limits.admit("agent", "model1", "ai_languageModel"))
                .isEqualTo(N8nSubNodeLimits.Verdict.KEEP);
        assertThat(limits.admit("agent", "model1", "ai_languageModel"))
                .isEqualTo(N8nSubNodeLimits.Verdict.SKIP);
        // A different connection type is a different fact.
        assertThat(limits.admit("agent", "model1", "ai_memory"))
                .isEqualTo(N8nSubNodeLimits.Verdict.KEEP);
    }

    @Test
    void thePerParentCapWarnsOnceThenSkips() {
        N8nSubNodeLimits limits = new N8nSubNodeLimits();
        for (int i = 0; i < N8nSubNodeLimits.PER_PARENT; i++) {
            assertThat(limits.admit("agent", "comp" + i, "ai_tool"))
                    .isEqualTo(N8nSubNodeLimits.Verdict.KEEP);
        }
        assertThat(limits.admit("agent", "over", "ai_tool"))
                .isEqualTo(N8nSubNodeLimits.Verdict.WARN_PARENT_FULL);
        assertThat(limits.admit("agent", "over2", "ai_tool"))
                .isEqualTo(N8nSubNodeLimits.Verdict.SKIP);
        // Other parents are unaffected.
        assertThat(limits.admit("other", "comp", "ai_tool"))
                .isEqualTo(N8nSubNodeLimits.Verdict.KEEP);
    }

    @Test
    void theTotalCapWarnsOnceThenSkips() {
        N8nSubNodeLimits limits = new N8nSubNodeLimits();
        for (int i = 0; i < N8nSubNodeLimits.TOTAL; i++) {
            // Spread across parents so the per-parent cap does not fire first.
            assertThat(limits.admit("p" + i, "comp", "ai_tool"))
                    .isEqualTo(N8nSubNodeLimits.Verdict.KEEP);
        }
        assertThat(limits.admit("extra", "comp", "ai_tool"))
                .isEqualTo(N8nSubNodeLimits.Verdict.WARN_TOTAL_FULL);
        assertThat(limits.admit("extra2", "comp", "ai_tool"))
                .isEqualTo(N8nSubNodeLimits.Verdict.SKIP);
    }

    @Test
    void warningTextNamesTheLimitAndTheParent() {
        assertThat(N8nSubNodeLimits.warning(N8nSubNodeLimits.Verdict.WARN_TOTAL_FULL, "agent"))
                .contains(String.valueOf(N8nSubNodeLimits.TOTAL));
        String parentWarning =
                N8nSubNodeLimits.warning(N8nSubNodeLimits.Verdict.WARN_PARENT_FULL, "my agent");
        assertThat(parentWarning)
                .contains("my agent")
                .contains(String.valueOf(N8nSubNodeLimits.PER_PARENT));
        // KEEP / SKIP share the per-parent wording (the caller only asks for WARN_*).
        assertThat(N8nSubNodeLimits.warning(N8nSubNodeLimits.Verdict.SKIP, "x"))
                .contains(String.valueOf(N8nSubNodeLimits.PER_PARENT));
    }
}
