package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.EnumSet;
import java.util.Set;

/**
 * Static knowledge about the OWASP Agentic Top 10: which SDK control mitigates each item, and which
 * items a classification implies exposure to even when the tool declares no {@code risks}.
 */
final class AsiControls {

  private AsiControls() {}

  /** The SDK control that addresses the item, as printed in the report. */
  static String describe(AsiRisk risk) {
    return switch (risk) {
      case ASI01 ->
          "Guardrails: built-in instruction-override and role-reassignment rules on"
              + " TOOL_OUTPUT; BEFORE_MODEL_CALL hooks";
      case ASI02 -> "Classification + dispatch gate + human approval on side-effecting tools";
      case ASI03 -> "RunContext scoping of every call; four-eyes approval rule";
      case ASI04 ->
          "Classify third-party and MCP tools as untrusted input; STRICT mode denies"
              + " unclassified tools";
      case ASI05 -> "Approval gate on HIGH/CRITICAL side-effecting tools; deny list";
      case ASI06 -> "TOOL_OUTPUT guardrails redact injected instructions before they reach memory";
      case ASI07 -> "Signed webhooks for policy hooks; audit of every inter-agent call";
      case ASI08 -> "Per-run budgets (calls, tokens, dollars) and effect journal idempotency";
      case ASI09 -> "Approval requests carry the raw arguments and run context for the reviewer";
      case ASI10 -> "Budgets cap runaway runs; audit trail and lethal-trifecta detection";
    };
  }

  /** Items the flags of a classification imply, independent of its declared {@code risks}. */
  static Set<AsiRisk> implied(ToolClassification c) {
    Set<AsiRisk> implied = EnumSet.noneOf(AsiRisk.class);
    if (c.consumesUntrustedInput()) {
      implied.add(AsiRisk.ASI01);
      implied.add(AsiRisk.ASI06);
    }
    if (c.sideEffecting()) {
      implied.add(AsiRisk.ASI02);
    }
    if (c.readsPrivateData()) {
      implied.add(AsiRisk.ASI03);
    }
    if (c.exfiltrationSink()) {
      implied.add(AsiRisk.ASI02);
    }
    return implied;
  }
}
