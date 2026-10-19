package io.vegaduta.examples.refund;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.util.Map;

/**
 * The same four operations as the ungoverned agent, classified once with
 * {@link Governed}. The methods take the model's arguments as a flat map;
 * {@code WithSdk} adapts each annotated method into a governed {@code Tool}.
 */
public final class SupportTools {

  private final Shop shop;

  public SupportTools(Shop shop) {
    this.shop = shop;
  }

  @Governed(risk = RiskLabel.LOW, sideEffecting = false)
  public String lookupOrder(Map<String, String> args) {
    return shop.getOrder(args.get("order_id"));
  }

  @Governed(
      risk = RiskLabel.MEDIUM,
      sideEffecting = false,
      readsPrivateData = true,
      untrustedInput = true,
      risks = {AsiRisk.ASI01, AsiRisk.ASI06})
  public String readEmail(Map<String, String> args) {
    return shop.getEmail(args.get("message_id"));
  }

  @Governed(risk = RiskLabel.HIGH, requiresApproval = true, risks = {AsiRisk.ASI02, AsiRisk.ASI09})
  public String issueRefund(Map<String, String> args) {
    return shop.refund(
        args.get("order_id"), Long.parseLong(args.get("cents")), args.get("reason"));
  }

  @Governed(risk = RiskLabel.MEDIUM, idempotent = true, risks = {AsiRisk.ASI02})
  public String applyStoreCredit(Map<String, String> args) {
    return shop.storeCredit(args.get("order_id"), Long.parseLong(args.get("cents")));
  }
}
