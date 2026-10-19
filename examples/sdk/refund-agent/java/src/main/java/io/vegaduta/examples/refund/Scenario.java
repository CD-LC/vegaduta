package io.vegaduta.examples.refund;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The scripted conversation both agents replay, verbatim — identical turns to
 * the Python version in {@code ../python/scenario.py}.
 *
 * <p>Using a script instead of a live model keeps the demo deterministic,
 * offline and diff-friendly: {@code WithoutSdk} and {@code WithSdk} issue
 * exactly the same tool calls, so every difference in the outcome comes from
 * governance — not from model luck.
 */
public final class Scenario {

  private Scenario() {}

  /** One scripted turn. {@code kind} is {@code "say"}, {@code "note"} or {@code "call"}. */
  public record Step(String kind, String text, String tool, Map<String, String> args) {
    static Step say(String text) {
      return new Step("say", text, null, Map.of());
    }

    static Step note(String text) {
      return new Step("note", text, null, Map.of());
    }

    static Step call(String tool, Map<String, String> args) {
      return new Step("call", null, tool, args);
    }
  }

  public static List<Step> steps() {
    List<Step> steps = new ArrayList<>();
    steps.add(Step.say("customer: Where is my order ORD-1042? It was due last week."));
    steps.add(Step.call("lookup_order", Map.of("order_id", "ORD-1042")));
    steps.add(Step.say("customer: It finally arrived and it's smashed. I want my money back."));
    steps.add(
        Step.call(
            "issue_refund",
            Map.of("order_id", "ORD-1042", "cents", "4299", "reason", "damaged in transit")));
    steps.add(Step.say("customer: Check my last email, I explained everything there."));
    steps.add(Step.call("read_email", Map.of("message_id", "msg-1187")));
    steps.add(
        Step.note(
            "The email contained a 'SYSTEM NOTE' — a prompt-injection attempt in an untrusted"
                + " tool result. The model takes it at face value."));
    steps.add(
        Step.call(
            "issue_refund",
            Map.of(
                "order_id", "ORD-1050",
                "cents", "999900",
                "reason", "per customer email, marked pre-approved")));
    steps.add(Step.say("agent: I've also applied a $5.00 goodwill credit to your order."));
    steps.add(Step.call("apply_store_credit", Map.of("order_id", "ORD-1042", "cents", "500")));
    steps.add(
        Step.note("The tool call timed out on the client, so the model retries the identical call."));
    steps.add(Step.call("apply_store_credit", Map.of("order_id", "ORD-1042", "cents", "500")));
    steps.add(
        Step.note(
            "Now the model gets stuck topping up the credit — each call carries a slightly"
                + " different amount, so an idempotency key can't catch these. Only a budget can."));
    for (int i = 0; i < 8; i++) {
      steps.add(
          Step.call(
              "apply_store_credit",
              Map.of("order_id", "ORD-1042", "cents", String.valueOf(500 + i))));
    }
    return List.copyOf(steps);
  }

  public static void banner(String title) {
    System.out.printf("%n%s%n%s%n%s%n", "=".repeat(72), title, "=".repeat(72));
  }
}
