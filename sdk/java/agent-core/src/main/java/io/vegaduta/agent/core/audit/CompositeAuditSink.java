package io.vegaduta.agent.core.audit;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditSink;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fans out to several sinks; a sink that throws is logged and skipped, never propagated. */
public final class CompositeAuditSink implements AuditSink {

  private static final Logger log = LoggerFactory.getLogger(CompositeAuditSink.class);

  private final List<AuditSink> sinks;

  public CompositeAuditSink(List<AuditSink> sinks) {
    this.sinks = List.copyOf(sinks);
  }

  public static AuditSink of(AuditSink... sinks) {
    return sinks.length == 1 ? safe(sinks[0]) : new CompositeAuditSink(List.of(sinks));
  }

  /** Wraps one sink so that its failures never reach the pipeline. */
  public static AuditSink safe(AuditSink sink) {
    return new CompositeAuditSink(List.of(sink));
  }

  @Override
  public void emit(AuditEvent event) {
    for (AuditSink sink : sinks) {
      try {
        sink.emit(event);
      } catch (RuntimeException e) {
        log.warn("Audit sink {} failed for event {}: {}", sink, event.id(), e.toString());
      }
    }
  }
}
