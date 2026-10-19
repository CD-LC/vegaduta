package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.providers.ProviderName;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AgentSamplingParamsTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static AgentGenerationParamsUpdateRequest req(
        Integer topK, List<String> stops, Double freq, Double pres, Integer seed, Boolean clear
    ) {
        return new AgentGenerationParamsUpdateRequest(1024, 0.7, 1.0, topK, stops, freq, pres, seed, clear);
    }

    private static String violations(AgentGenerationParamsUpdateRequest r) {
        return VALIDATOR.validate(r).stream().map(v -> v.getMessage()).sorted().collect(Collectors.joining("|"));
    }

    private static Agent agent() {
        return new Agent("A", "prompt", ProviderName.ANTHROPIC, "claude-sonnet-5", 1024, 0.7, 1.0, false,
            null, ReasoningDepth.FAST, TuningMode.MANUAL);
    }

    @Test
    void validBoundsPass() {
        assertThat(violations(req(1000, List.of("a".repeat(64)), 2.0, -2.0, 7, null))).isEmpty();
        assertThat(violations(req(null, null, null, null, null, null))).isEmpty();
        assertThat(violations(new AgentGenerationParamsUpdateRequest(1024, 0.7, 1.0))).isEmpty();
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThat(violations(req(0, null, null, null, null, null))).contains("topK must be between 1 and 1000");
        assertThat(violations(req(1001, null, null, null, null, null))).contains("topK must be between 1 and 1000");
        assertThat(violations(req(null, null, 2.1, null, null, null))).contains("frequencyPenalty must be between -2 and 2");
        assertThat(violations(req(null, null, null, -2.1, null, null))).contains("presencePenalty must be between -2 and 2");
    }

    @Test
    void stopSequenceLimitsAreEnforced() {
        assertThat(violations(req(null, List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), null, null, null, null)))
            .contains("stopSequences may hold at most 8 entries");
        assertThat(violations(req(null, List.of("a".repeat(65)), null, null, null, null)))
            .contains("each stop sequence must be at most 64 characters");
    }

    @Test
    void updateNullMeansUnchangedEmptyStopClearsAndClearFlagResets() {
        Agent a = agent();
        AgentService.applySamplingUpdate(a, req(40, List.of("END"), 0.5, 0.25, 9, null));
        assertThat(a.getTopK()).isEqualTo(40);
        assertThat(a.getStopSequences()).containsExactly("END");
        assertThat(a.getSeed()).isEqualTo(9);

        AgentService.applySamplingUpdate(a, req(null, null, null, null, null, null));
        assertThat(a.getTopK()).isEqualTo(40);
        assertThat(a.getFrequencyPenalty()).isEqualTo(0.5);

        AgentService.applySamplingUpdate(a, req(null, List.of(), null, null, null, null));
        assertThat(a.getStopSequences()).isEmpty();
        assertThat(a.getTopK()).isEqualTo(40);

        AgentService.applySamplingUpdate(a, req(null, null, null, null, null, true));
        assertThat(a.getTopK()).isNull();
        assertThat(a.getFrequencyPenalty()).isNull();
        assertThat(a.getSeed()).isNull();
    }
}
