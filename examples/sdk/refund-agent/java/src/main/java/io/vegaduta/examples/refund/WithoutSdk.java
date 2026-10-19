package io.vegaduta.examples.refund;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The support agent the way most tutorials build it: model + bare functions.
 *
 * <p>Nothing stands between the model's intent and the shop backend — no
 * approval, no idempotency, no spending cap, no record of what ran. Run it,
 * then run {@code WithSdk} and compare the money ledger and the audit trail.
 */
public final class WithoutSdk {

  private WithoutSdk() {}

  public static void main(String[] args) {
    Shop shop = new Shop();
    Map<String, Function<Map<String, String>, String>> tools = new LinkedHashMap<>();
    tools.put("lookup_order", a -> shop.getOrder(a.get("order_id")));
    tools.put("read_email", a -> shop.getEmail(a.get("message_id")));
    tools.put(
        "issue_refund",
        a -> shop.refund(a.get("order_id"), Long.parseLong(a.get("cents")), a.get("reason")));
    tools.put(
        "apply_store_credit",
        a -> shop.storeCredit(a.get("order_id"), Long.parseLong(a.get("cents"))));

    Scenario.banner("SUPPORT AGENT — NO SDK (model calls tools directly)");
    for (Scenario.Step step : Scenario.steps()) {
      if (!step.kind().equals("call")) {
        System.out.println(step.text());
        continue;
      }
      System.out.printf("  -> %s %s%n", step.tool(), step.args());
      System.out.printf("  <- %s%n", tools.get(step.tool()).apply(step.args()));
    }

    Scenario.banner("MONEY MOVED (who approved it? nobody can say)");
    System.out.print(shop.moneyMoved());
    System.out.println(
        """
        What just happened:
          * $9,999.00 was refunded on the strength of text inside a customer email.
          * The goodwill credit posted twice — the model retried, the effect ran again.
          * Then the stuck loop posted eight more credits with drifting amounts.
          * A Slack bot token pasted into the email is now in the model's context.
          * There is no record of any of it. Ledger entries are all you have.""");
  }
}
