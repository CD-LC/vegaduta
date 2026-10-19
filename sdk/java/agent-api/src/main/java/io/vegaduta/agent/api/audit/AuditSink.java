package io.vegaduta.agent.api.audit;

/**
 * Receives audit events. Sinks must never throw into the pipeline; the SDK catches and logs
 * failures, but a sink that blocks will slow every tool call, so write asynchronously if the
 * destination is slow.
 */
@FunctionalInterface
public interface AuditSink {

  void emit(AuditEvent event);

  /** Drops everything. */
  static AuditSink discard() {
    return event -> {};
  }
}
