package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.providers.ProviderName;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BudgetPolicy}'s immutable-with- style: {@code none()} is the empty starting state,
 * each {@code withX} returns a copy (the original is untouched, embedded-entity semantics), a null
 * economy provider clears the economy model, and equality is by value across every field.
 */
class BudgetPolicyTest {

    @Test
    void noneIsTheNoCapNoRoutingStartingState() {
        BudgetPolicy p = BudgetPolicy.none();

        assertThat(p.getRunBudgetUsd()).isNull();
        assertThat(p.isCostRoutingEnabled()).isFalse();
        assertThat(p.getEconomyProviderName()).isNull();
        assertThat(p.getEconomyModel()).isNull();
        assertThat(p.getCostRoutingJevThreshold()).isNull();
    }

    @Test
    void withRunBudgetUsdReturnsACopyLeavingTheOriginalAlone() {
        BudgetPolicy base = BudgetPolicy.none();

        BudgetPolicy capped = base.withRunBudgetUsd(new BigDecimal("2.50"));

        assertThat(base.getRunBudgetUsd()).isNull();
        assertThat(capped.getRunBudgetUsd()).isEqualByComparingTo("2.50");
        assertThat(capped).isNotSameAs(base);
    }

    @Test
    void withCostRoutingCarriesProviderModelAndThreshold() {
        BudgetPolicy p =
                BudgetPolicy.none()
                        .withCostRouting(true, ProviderName.OPENAI, "gpt-4o-mini", 0.8);

        assertThat(p.isCostRoutingEnabled()).isTrue();
        assertThat(p.getEconomyProviderName()).isEqualTo(ProviderName.OPENAI);
        assertThat(p.getEconomyModel()).isEqualTo("gpt-4o-mini");
        assertThat(p.getCostRoutingJevThreshold()).isEqualTo(0.8);
    }

    @Test
    void aNullEconomyProviderClearsTheEconomyModel() {
        // Enabling routing with no provider is a no-op state - the model must not linger.
        BudgetPolicy p =
                BudgetPolicy.none()
                        .withCostRouting(true, ProviderName.GEMINI, "gemini-flash", null)
                        .withCostRouting(true, null, "leftover-model", null);

        assertThat(p.getEconomyProviderName()).isNull();
        assertThat(p.getEconomyModel()).isNull();
    }

    @Test
    void withersPreserveTheOtherFields() {
        BudgetPolicy p =
                BudgetPolicy.none()
                        .withRunBudgetUsd(new BigDecimal("1.00"))
                        .withCostRouting(true, ProviderName.ANTHROPIC, "claude-haiku", 0.6);

        assertThat(p.getRunBudgetUsd()).isEqualByComparingTo("1.00");
        assertThat(p.getEconomyProviderName()).isEqualTo(ProviderName.ANTHROPIC);
        assertThat(p.getEconomyModel()).isEqualTo("claude-haiku");
        assertThat(p.getCostRoutingJevThreshold()).isEqualTo(0.6);
    }

    @Test
    void equalityIsByValueAcrossEveryField() {
        BudgetPolicy a =
                BudgetPolicy.none()
                        .withRunBudgetUsd(new BigDecimal("1.00"))
                        .withCostRouting(true, ProviderName.GROK, "grok-mini", 0.4);
        BudgetPolicy same =
                BudgetPolicy.none()
                        .withRunBudgetUsd(new BigDecimal("1.00"))
                        .withCostRouting(true, ProviderName.GROK, "grok-mini", 0.4);
        BudgetPolicy differentThreshold =
                BudgetPolicy.none()
                        .withRunBudgetUsd(new BigDecimal("1.00"))
                        .withCostRouting(true, ProviderName.GROK, "grok-mini", 0.5);

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(differentThreshold);
        assertThat(a).isNotEqualTo(BudgetPolicy.none());
        assertThat(a).isNotEqualTo("not a policy");
    }
}
