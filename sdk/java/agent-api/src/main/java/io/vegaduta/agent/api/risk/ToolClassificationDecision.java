package io.vegaduta.agent.api.risk;

import java.util.Objects;

/**
 * The result of looking a tool up: either a classification or a reason there is none usable. A
 * {@link Blocked} decision in strict mode stops the call; in warn mode the default classification
 * from the policy applies.
 */
public sealed interface ToolClassificationDecision
    permits ToolClassificationDecision.Classified, ToolClassificationDecision.Blocked {

  String toolName();

  /** Why a tool has no usable classification. */
  enum Reason {
    /** No source knows the tool. */
    UNCLASSIFIED,
    /** A source knows the tool but has marked it as not to be offered. */
    QUARANTINED,
    /** The classification row exists but is invalid. */
    INVALID
  }

  record Classified(ToolClassification classification) implements ToolClassificationDecision {
    public Classified {
      Objects.requireNonNull(classification, "classification");
    }

    @Override
    public String toolName() {
      return classification.toolName();
    }
  }

  record Blocked(String toolName, Reason reason, String detail)
      implements ToolClassificationDecision {
    public Blocked {
      Objects.requireNonNull(toolName, "toolName");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
    }

    public static Blocked unclassified(String toolName) {
      return new Blocked(
          toolName, Reason.UNCLASSIFIED, "Tool '" + toolName + "' has no classification.");
    }
  }
}
