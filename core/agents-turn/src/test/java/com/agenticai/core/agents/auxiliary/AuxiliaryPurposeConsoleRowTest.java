package com.agenticai.core.agents.auxiliary;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.tenancy.AiModelFeature;
import org.junit.jupiter.api.Test;

/**
 * The background model of each purpose (compaction, session summary, assist, judge) is chosen in the console on the "AI Model
 * Control" page, which has one row per {@link AiModelFeature}. {@link AuxiliaryModelResolver} reads a tenant's row by
 * {@link AuxiliaryPurpose#featureKey()}, so a purpose whose key is not a feature would have no row to set and its first choice
 * could never be made from the console.
 */
class AuxiliaryPurposeConsoleRowTest {

    @Test
    void everyBackgroundPurposeHasARowOnTheAiModelControlPage() {
        for (AuxiliaryPurpose purpose : AuxiliaryPurpose.values()) {
            assertThat(AiModelFeature.fromKey(purpose.featureKey()))
                    .as("AI Model Control row for the %s purpose (feature key '%s')", purpose, purpose.featureKey())
                    .isNotNull();
        }
    }
}
