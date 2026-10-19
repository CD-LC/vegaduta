package io.vegaduta.agent.core.audit;

import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditSink;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one {@code key=value} line per event to the logger {@code io.vegaduta.agent.audit} at
 * INFO. Point a log appender at that logger to ship audit events anywhere.
 */
public final class LoggingAuditSink implements AuditSink {

  public static final String LOGGER_NAME = "io.vegaduta.agent.audit";

  private final Logger log;

  public LoggingAuditSink() {
    this(LoggerFactory.getLogger(LOGGER_NAME));
  }

  LoggingAuditSink(Logger log) {
    this.log = log;
  }

  @Override
  public void emit(AuditEvent event) {
    if (!log.isInfoEnabled()) {
      return;
    }
    StringBuilder sb = new StringBuilder(160);
    for (Map.Entry<String, String> e : event.toFlatMap().entrySet()) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(e.getKey()).append('=').append(quote(e.getValue()));
    }
    log.info("{}", sb);
  }

  private static String quote(String value) {
    if (value.indexOf(' ') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
  }
}
