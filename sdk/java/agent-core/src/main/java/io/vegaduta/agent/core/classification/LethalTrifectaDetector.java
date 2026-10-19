package io.vegaduta.agent.core.classification;

import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Detects the "lethal trifecta": an agent that can read private data, consumes untrusted input, and
 * can send data out. Any one is fine; all three together let a prompt injection in the untrusted
 * input exfiltrate the private data. The usual remedy is to force human approval on the sinks while
 * the other two are present.
 */
public final class LethalTrifectaDetector {

  /**
   * @param holds all three capabilities are present
   * @param privateReaders tools that read private data
   * @param untrustedConsumers tools whose output is untrusted
   * @param sinks tools that can exfiltrate
   */
  public record Report(
      boolean holds,
      List<String> privateReaders,
      List<String> untrustedConsumers,
      List<String> sinks) {
    public Report {
      privateReaders = List.copyOf(privateReaders);
      untrustedConsumers = List.copyOf(untrustedConsumers);
      sinks = List.copyOf(sinks);
    }
  }

  public Report analyse(Collection<ToolClassification> offered) {
    List<String> readers = new ArrayList<>();
    List<String> untrusted = new ArrayList<>();
    List<String> sinks = new ArrayList<>();
    for (ToolClassification c : offered) {
      if (c.readsPrivateData()) {
        readers.add(c.toolName());
      }
      if (c.consumesUntrustedInput()) {
        untrusted.add(c.toolName());
      }
      if (c.exfiltrationSink()) {
        sinks.add(c.toolName());
      }
    }
    boolean holds = !readers.isEmpty() && !untrusted.isEmpty() && !sinks.isEmpty();
    return new Report(holds, readers, untrusted, sinks);
  }
}
