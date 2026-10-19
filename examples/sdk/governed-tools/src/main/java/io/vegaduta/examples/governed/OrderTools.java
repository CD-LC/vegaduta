package io.vegaduta.examples.governed;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** The tools. The {@code @Governed} annotation is the only SDK-specific line. */
@Component
public class OrderTools {

  private final Map<String, String> orders =
      new ConcurrentHashMap<>(Map.of("42", "shipped", "43", "processing"));
  private final Map<String, Long> refunded = new ConcurrentHashMap<>();

  @Tool(description = "Look up the status of an order by id")
  @Governed(risk = RiskLabel.LOW, sideEffecting = false, readsPrivateData = true, idempotent = true)
  public String orderStatus(@ToolParam(description = "the order id") String orderId) {
    String status = orders.get(orderId);
    return status == null ? "No order " + orderId : "Order " + orderId + " is " + status;
  }

  @Tool(description = "Refund an order. Amount in cents.")
  @Governed(
      risk = RiskLabel.HIGH,
      requiresApproval = true,
      idempotent = true,
      risks = {AsiRisk.ASI02, AsiRisk.ASI09})
  public String refund(
      @ToolParam(description = "the order id") String orderId,
      @ToolParam(description = "amount in cents") long cents) {
    refunded.merge(orderId, cents, Long::sum);
    return "Refunded " + cents + " cents for order " + orderId;
  }

  public Map<String, Long> refunded() {
    return Map.copyOf(refunded);
  }
}
