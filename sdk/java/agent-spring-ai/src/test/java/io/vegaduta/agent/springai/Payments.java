package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** A tool-bearing bean as a consumer would write it. */
public class Payments {

  final AtomicInteger refunds = new AtomicInteger();

  @Tool(description = "Refund an order")
  @Governed(
      risk = RiskLabel.HIGH,
      requiresApproval = true,
      idempotent = true,
      risks = AsiRisk.ASI02)
  public String refund(@ToolParam(description = "order id") String orderId, @ToolParam long cents) {
    refunds.incrementAndGet();
    return "refunded " + cents + " for " + orderId;
  }

  @Tool(name = "order_status", description = "Look up an order")
  @Governed(risk = RiskLabel.LOW, sideEffecting = false, readsPrivateData = true, idempotent = true)
  public String status(@ToolParam String orderId) {
    return "order " + orderId + " shipped";
  }

  @Tool(description = "Unclassified helper")
  public String helper() {
    return "help";
  }
}
