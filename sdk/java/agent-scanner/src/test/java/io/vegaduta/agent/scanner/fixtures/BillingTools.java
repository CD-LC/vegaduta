package io.vegaduta.agent.scanner.fixtures;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;

/** Fully governed tools: one approval-gated refund, one private-data lookup. */
public class BillingTools {

  @Tool(name = "refund_order", description = "Refund an order")
  @Governed(
      risk = RiskLabel.HIGH,
      requiresApproval = true,
      idempotent = true,
      risks = {AsiRisk.ASI02, AsiRisk.ASI05})
  public String refund(String orderId, long cents) {
    return "ok";
  }

  @Tool(description = "Look up an order")
  @Governed(
      risk = RiskLabel.LOW,
      sideEffecting = false,
      readsPrivateData = true,
      risks = {AsiRisk.ASI03})
  public String lookupOrder(String orderId) {
    return "{}";
  }

  /** Not a tool: no annotation at all. */
  public String helper() {
    return "";
  }

  /** Not a tool: static methods are skipped. */
  @Tool
  public static String staticNotATool() {
    return "";
  }
}
