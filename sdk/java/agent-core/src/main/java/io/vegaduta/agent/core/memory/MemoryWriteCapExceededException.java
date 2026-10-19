package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.GovernanceException;

/** A run tried to write more memory entries than its cap allows. */
@Experimental
public final class MemoryWriteCapExceededException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final transient String runId;
  private final int cap;

  public MemoryWriteCapExceededException(String runId, int cap) {
    super("Run '" + runId + "' exceeded its memory write cap of " + cap);
    this.runId = runId;
    this.cap = cap;
  }

  public String runId() {
    return runId;
  }

  public int cap() {
    return cap;
  }
}
