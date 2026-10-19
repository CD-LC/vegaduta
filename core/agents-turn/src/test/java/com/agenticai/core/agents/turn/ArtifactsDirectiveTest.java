package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactsDirectiveTest {

    private static final UUID AGENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("99999999-2222-3333-4444-555555555555");

    @Test
    void defaultPostureAddsNothing() {
        // enabled=false is the shipped default: the prompt must be byte-identical to today's.
        assertThat(ArtifactsDirective.forAgent(false, false, "", AGENT)).isNull();
        assertThat(ArtifactsDirective.forAgent(false, true, AGENT.toString(), AGENT)).isNull();
        assertThat(ChatPromptDirectives.artifactsDirective(false, false, "", AGENT)).isNull();
    }

    @Test
    void enabledButNotOptedInAddsNothing() {
        assertThat(ArtifactsDirective.forAgent(true, false, "", AGENT)).isNull();
        assertThat(ArtifactsDirective.forAgent(true, false, null, AGENT)).isNull();
        assertThat(ArtifactsDirective.forAgent(true, false, OTHER.toString(), AGENT)).isNull();
    }

    @Test
    void allAgentsOptsEveryAgentIn() {
        assertThat(ArtifactsDirective.forAgent(true, true, "", AGENT)).isEqualTo(ArtifactsDirective.TEXT);
        assertThat(ArtifactsDirective.forAgent(true, true, "", null)).isEqualTo(ArtifactsDirective.TEXT);
    }

    @Test
    void listedAgentIsOptedIn_caseAndWhitespaceTolerant() {
        String csv = " " + OTHER + " ,  " + AGENT.toString().toUpperCase() + " ";
        assertThat(ArtifactsDirective.forAgent(true, false, csv, AGENT)).isEqualTo(ArtifactsDirective.TEXT);
        assertThat(ChatPromptDirectives.artifactsDirective(true, false, csv, AGENT)).isEqualTo(ArtifactsDirective.TEXT);
    }

    @Test
    void nullAgentIsNeverListed() {
        assertThat(ArtifactsDirective.isListed(AGENT.toString(), null)).isFalse();
        assertThat(ArtifactsDirective.isListed("not-a-uuid,,", AGENT)).isFalse();
    }

    @Test
    void textTeachesTheConventionTheWebParserReads() {
        String text = ArtifactsDirective.TEXT;
        assertThat(text)
            .contains("artifact:<type>:<title>")
            .contains("html, svg, markdown, code:<lang>, mermaid, csv")
            .contains("exact same title")
            .contains("four backticks");
    }

    @Test
    void textIsAByteStableConstantAndStaysSmall() {
        String text = ArtifactsDirective.TEXT;
        // No per-turn interpolation slots: the directive must stay inside the cacheable prefix.
        assertThat(text).doesNotContain("%s").doesNotContain("{}").doesNotContain("${");
        // Prompt-token guard: this is opt-in, but it should not creep into a manual.
        assertThat(text.length()).isLessThan(1500);
    }
}
