package io.vegaduta.agent.api.hook;

import java.util.Objects;

/** What a hook asks the pipeline to do next. */
public sealed interface HookDecision
    permits HookDecision.Continue, HookDecision.Block, HookDecision.Replace {

  /** Carry on unchanged. */
  record Continue() implements HookDecision {}

  /** Stop the call with the given reason. */
  record Block(String reason) implements HookDecision {
    public Block {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Carry on with a different payload (rewritten arguments or output). */
  record Replace(String payload) implements HookDecision {
    public Replace {
      Objects.requireNonNull(payload, "payload");
    }
  }

  HookDecision CONTINUE = new Continue();

  static HookDecision block(String reason) {
    return new Block(reason);
  }

  static HookDecision replace(String payload) {
    return new Replace(payload);
  }
}
