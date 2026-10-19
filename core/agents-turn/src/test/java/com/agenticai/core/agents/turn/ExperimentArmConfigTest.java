package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExperimentArmConfigTest {

    @Test
    void accessorsMatchTheStaticReaders() {
        String model = "{\"model\":\"gpt-4o\"}";
        String tools = "{\"toolNames\":[\"a\",\"b\"]}";
        String gen = "{\"temperature\":0.2,\"topP\":0.9}";

        assertThat(ExperimentArmConfig.of(model).model()).isEqualTo("gpt-4o");
        assertThat(ExperimentArmConfig.of(tools).toolNames())
                .isEqualTo(ChatArmConfig.toolNamesFromArmConfig(tools));
        assertThat(ExperimentArmConfig.of(gen).genParams())
                .isEqualTo(ChatArmConfig.genParamsFromArmConfig(gen));
    }

    @Test
    void absentOrMalformedConfigDegradesToNull() {
        assertThat(ExperimentArmConfig.of(null).model()).isNull();
        assertThat(ExperimentArmConfig.of("").systemPrompt()).isNull();
        assertThat(ExperimentArmConfig.of("{not json").reasoningDepth()).isNull();
        assertThat(ExperimentArmConfig.of("{\"toolNames\": ").toolNames()).isNull();
    }
}
