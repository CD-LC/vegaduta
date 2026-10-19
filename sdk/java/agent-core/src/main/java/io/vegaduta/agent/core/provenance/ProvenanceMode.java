package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;

/** What the {@link ProvenanceGate} does when a called tool drifted from its pin. */
@Experimental
public enum ProvenanceMode {
  /** Deny the call ({@code DENY}, reason {@link ProvenanceGate#REASON_TOOL_DRIFT}). */
  ENFORCE,
  /** Allow the call and emit a {@code tool.drift} audit event with outcome {@code WARNING}. */
  WARN
}
