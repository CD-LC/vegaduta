package com.agenticai.core.prompts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptDiffTest {

    @Test
    void marksRemovedAddedAndKeptLines() {
        assertThat(PromptDiff.unified("a\nb\nc", "a\nB\nc\nd")).isEqualTo("  a\n- b\n+ B\n  c\n+ d");
    }

    @Test
    void anEmptySideIsAllAddedOrAllRemoved() {
        assertThat(PromptDiff.unified("", "x\ny")).isEqualTo("+ x\n+ y");
        assertThat(PromptDiff.unified("x", null)).isEqualTo("- x");
        assertThat(PromptDiff.unified("same", "same")).isEqualTo("  same");
    }
}
