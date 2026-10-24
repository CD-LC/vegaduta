package com.agenticai.core.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one tool-output cap: what is kept, what the marker says, and that cutting twice changes nothing. */
class ToolOutputPropertiesTest {

    private final ToolOutputProperties props = new ToolOutputProperties();

    @Test
    void theDefaultsComeFromTheModuleResource() {
        assertThat(props.getMaxChars()).isEqualTo(20_000);
        assertThat(props.getTruncationMarker()).contains("{total}").contains("{dropped}").contains("{limit}");
        assertThat(props.getPerTool()).isEmpty();
    }

    @Test
    void nullAndShortResultsAreReturnedAsTheyAre() {
        assertThat(props.limit("t", null)).isNull();
        assertThat(props.limit("t", "")).isEmpty();
        assertThat(props.limit("t", "short")).isEqualTo("short");
    }

    @Test
    void theMarkerStatesTheTotalWhatWasCutAndTheLimitAndTheHeadAddsUp() {
        String cut = props.limit("t", "z".repeat(50_000));

        assertThat(cut.length()).isLessThanOrEqualTo(20_000).isGreaterThan(19_990);
        int head = cut.indexOf("\n...[truncated");
        assertThat(head).isPositive();
        assertThat(cut).contains("of 50000 characters").contains("20000-character limit");
        // "N of 50000 characters were cut": N is exactly what the head left out.
        int dropped = Integer.parseInt(cut.replaceAll("(?s).*truncated: (\\d+) of.*", "$1"));
        assertThat(head + dropped).isEqualTo(50_000);
    }

    @Test
    void cuttingAnAlreadyCutResultChangesNothing() {
        String once = props.limit("t", "q".repeat(70_000));

        assertThat(props.limit("t", once)).isEqualTo(once);
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        props.setMaxChars(400);
        String emojis = "😀".repeat(500);

        String cut = props.limit("t", emojis);

        int headEnd = cut.indexOf("\n...[truncated");
        assertThat(Character.isHighSurrogate(cut.charAt(headEnd - 1))).isFalse();
        assertThat(cut.length()).isLessThanOrEqualTo(400);
    }

    @Test
    void aLimitSmallerThanTheMarkerStillNeverExceedsTheLimit() {
        props.setMaxChars(20);

        assertThat(props.limit("t", "x".repeat(500)).length()).isLessThanOrEqualTo(20);
    }

    @Test
    void perToolEntriesOverrideAndNonPositiveEntriesAreIgnored() {
        props.getPerTool().put("wide", 100_000);
        props.getPerTool().put("narrow", 500);
        props.getPerTool().put("broken", 0);

        assertThat(props.limitFor("wide")).isEqualTo(100_000);
        assertThat(props.limitFor("narrow")).isEqualTo(500);
        assertThat(props.limitFor("broken")).isEqualTo(20_000);
        assertThat(props.limitFor("unlisted")).isEqualTo(20_000);
        assertThat(props.limitFor(null)).isEqualTo(20_000);
    }
}
