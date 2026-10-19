package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;

/**
 * Where a memory value came from. Provenance decides whether an entry is trusted on arrival or
 * quarantined until someone promotes it.
 */
@Experimental
public enum Provenance {
  /** Typed or confirmed by the user. */
  USER(true),
  /** Written by the host application or an operator. */
  SYSTEM(true),
  /** Produced by the model itself (a summary, a plan, a "fact" it inferred). */
  MODEL(true),
  /** Returned by a tool the deployment trusts (own database, internal API). */
  TOOL_OUTPUT_TRUSTED(true),
  /** Returned by a tool that reads untrusted content (web page, email, shared document). */
  TOOL_OUTPUT_UNTRUSTED(false),
  /** Sent by another agent; its own context may already be poisoned. */
  AGENT_PEER(false);

  private final boolean trusted;

  Provenance(boolean trusted) {
    this.trusted = trusted;
  }

  /** True when an entry with this provenance is visible immediately; false when quarantined. */
  public boolean trusted() {
    return trusted;
  }
}
