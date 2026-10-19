package io.vegaduta.agent.core.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostModelTest {

  @Test
  void modelPricingIsExactToSixDecimalPlaces() {
    ModelPrice price = ModelPrice.perMillion("3.00", "15.00");

    // 1234 input tokens at $3/M = 0.003702; 567 output tokens at $15/M = 0.008505
    assertThat(price.costOf(1_234, 567)).isEqualByComparingTo("0.012207");
    assertThat(price.costOf(1_234, 567).scale()).isEqualTo(6);
    assertThat(price.costOf(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    // 1 token at $0.25/M rounds half-up from 0.00000025 to 0.000000 (below six places)
    assertThat(ModelPrice.perMillion("0.25", "0").costOf(1, 0)).isEqualByComparingTo("0.000000");
    // 3 tokens at $0.50/M = 0.0000015 rounds half-up to 0.000002
    assertThat(ModelPrice.perMillion("0.50", "0").costOf(3, 0)).isEqualByComparingTo("0.000002");
  }

  @Test
  void costModelPricesToolsModelsAndReview() {
    CostModel model =
        CostModel.builder()
            .toolCost("search_web", "0.005")
            .defaultToolCost("0.001")
            .modelPrice("claude-sonnet", ModelPrice.perMillion("3.00", "15.00"))
            .humanReviewCost("2.50")
            .build();

    assertThat(model.toolCost("search_web")).isEqualByComparingTo("0.005");
    assertThat(model.toolCost("unknown")).isEqualByComparingTo("0.001");
    assertThat(model.hasToolCost("search_web")).isTrue();
    assertThat(model.hasToolCost("unknown")).isFalse();
    assertThat(model.modelCost("claude-sonnet", 1_000_000, 1_000_000)).isEqualByComparingTo("18");
    assertThat(model.modelCost("not-priced", 1_000_000, 0)).isEqualByComparingTo("0");
    assertThat(model.modelPrice("not-priced")).isEmpty();
    assertThat(model.humanReviewCost()).isEqualByComparingTo("2.50");
  }

  @Test
  void freeModelCostsNothingAndRejectsNegatives() {
    CostModel free = CostModel.free();
    assertThat(free.toolCost("anything")).isEqualByComparingTo("0");
    assertThat(free.humanReviewCost()).isEqualByComparingTo("0");

    assertThatThrownBy(() -> CostModel.builder().toolCost("x", "-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ModelPrice.perMillion("-3", "0"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
