package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What things cost. Immutable; build one per deployment and share it between the ledger, the
 * observer and the budget provider.
 *
 * <ul>
 *   <li>Per-tool USD estimate, with a default for tools not listed.
 *   <li>Per-model token pricing by model id, see {@link ModelPrice}.
 *   <li>Human review cost per approval decision (approved or rejected), default zero.
 * </ul>
 *
 * <pre>{@code
 * CostModel model = CostModel.builder()
 *     .toolCost("search_web", "0.005")
 *     .toolCost("send_email", "0.02")
 *     .defaultToolCost("0.001")
 *     .modelPrice("claude-sonnet", ModelPrice.perMillion("3.00", "15.00"))
 *     .humanReviewCost("2.50")
 *     .build();
 * }</pre>
 */
@Experimental
public final class CostModel {

  private final Map<String, BigDecimal> toolCosts;
  private final BigDecimal defaultToolCost;
  private final Map<String, ModelPrice> modelPrices;
  private final BigDecimal humanReviewCost;

  private CostModel(Builder b) {
    this.toolCosts = Collections.unmodifiableMap(new LinkedHashMap<>(b.toolCosts));
    this.defaultToolCost = b.defaultToolCost;
    this.modelPrices = Collections.unmodifiableMap(new LinkedHashMap<>(b.modelPrices));
    this.humanReviewCost = b.humanReviewCost;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A model where everything costs zero. Useful to count calls and tokens without pricing. */
  public static CostModel free() {
    return builder().build();
  }

  /** The estimated USD cost of one call of {@code toolName}, or the default when not listed. */
  public BigDecimal toolCost(String toolName) {
    BigDecimal cost = toolCosts.get(toolName);
    return cost == null ? defaultToolCost : cost;
  }

  /** True when {@code toolName} has an explicit price, false when the default applies. */
  public boolean hasToolCost(String toolName) {
    return toolCosts.containsKey(toolName);
  }

  public Map<String, BigDecimal> toolCosts() {
    return toolCosts;
  }

  public BigDecimal defaultToolCost() {
    return defaultToolCost;
  }

  /** The price list entry for {@code modelId}, if one is configured. */
  public Optional<ModelPrice> modelPrice(String modelId) {
    return Optional.ofNullable(modelPrices.get(modelId));
  }

  public Map<String, ModelPrice> modelPrices() {
    return modelPrices;
  }

  /**
   * The USD cost of one model call, six decimal places. An unknown model costs zero, so a missing
   * price never breaks a run; it only under-reports.
   */
  public BigDecimal modelCost(String modelId, long inputTokens, long outputTokens) {
    ModelPrice price = modelPrices.get(modelId);
    return price == null ? Usd.ZERO : price.costOf(inputTokens, outputTokens);
  }

  /** The USD cost of one human approval decision. */
  public BigDecimal humanReviewCost() {
    return humanReviewCost;
  }

  public static final class Builder {
    private final Map<String, BigDecimal> toolCosts = new LinkedHashMap<>();
    private BigDecimal defaultToolCost = Usd.ZERO;
    private final Map<String, ModelPrice> modelPrices = new LinkedHashMap<>();
    private BigDecimal humanReviewCost = Usd.ZERO;

    private Builder() {}

    public Builder toolCost(String toolName, BigDecimal usd) {
      Objects.requireNonNull(toolName, "toolName");
      toolCosts.put(toolName, Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd"));
      return this;
    }

    public Builder toolCost(String toolName, String usd) {
      return toolCost(toolName, new BigDecimal(usd));
    }

    public Builder toolCosts(Map<String, BigDecimal> values) {
      values.forEach(this::toolCost);
      return this;
    }

    public Builder defaultToolCost(BigDecimal usd) {
      this.defaultToolCost = Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd");
      return this;
    }

    public Builder defaultToolCost(String usd) {
      return defaultToolCost(new BigDecimal(usd));
    }

    public Builder modelPrice(String modelId, ModelPrice price) {
      modelPrices.put(
          Objects.requireNonNull(modelId, "modelId"), Objects.requireNonNull(price, "price"));
      return this;
    }

    public Builder modelPrices(Map<String, ModelPrice> values) {
      values.forEach(this::modelPrice);
      return this;
    }

    public Builder humanReviewCost(BigDecimal usd) {
      this.humanReviewCost = Usd.nonNegative(Objects.requireNonNull(usd, "usd"), "usd");
      return this;
    }

    public Builder humanReviewCost(String usd) {
      return humanReviewCost(new BigDecimal(usd));
    }

    public CostModel build() {
      return new CostModel(this);
    }
  }
}
