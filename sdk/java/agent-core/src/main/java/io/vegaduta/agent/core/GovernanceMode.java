package io.vegaduta.agent.core;

/** How the pipeline treats tools it knows nothing about. */
public enum GovernanceMode {
  /** Unclassified tools are denied; the lethal trifecta forces approval on exfiltration sinks. */
  STRICT,
  /**
   * Unclassified tools run with the policy's default classification and a {@code WARNING} audit
   * event; the lethal trifecta is reported, not enforced. The default, so adding the dependency
   * never breaks an existing application.
   */
  WARN,
  /** The pipeline is bypassed. Tools run as if the SDK were not there. */
  OFF
}
