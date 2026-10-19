package io.vegaduta.agent.core.audit;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditSink;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps the last N events in memory. For tests, demos and a quick look in a debugger. */
public final class InMemoryAuditSink implements AuditSink {

  private final Deque<AuditEvent> events = new ArrayDeque<>();
  private final int capacity;

  public InMemoryAuditSink() {
    this(1_000);
  }

  public InMemoryAuditSink(int capacity) {
    this.capacity = capacity;
  }

  @Override
  public synchronized void emit(AuditEvent event) {
    if (events.size() >= capacity) {
      events.removeFirst();
    }
    events.addLast(event);
  }

  public synchronized List<AuditEvent> events() {
    return new ArrayList<>(events);
  }

  public synchronized void clear() {
    events.clear();
  }
}
