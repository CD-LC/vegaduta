package io.vegaduta.agent.core.memory;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One memory record with its provenance.
 *
 * @param key the memory key
 * @param value the stored value (already screened and redacted when written through a {@link
 *     GovernedMemoryStore})
 * @param provenance where the value came from
 * @param writerAgentId the agent that wrote it, or {@code null} when unknown
 * @param runId the run that wrote it
 * @param writtenAt when it was written
 * @param quarantined true while the entry is stored but hidden from ordinary reads
 */
@Experimental
public record MemoryEntry(
    String key,
    String value,
    Provenance provenance,
    @Nullable String writerAgentId,
    String runId,
    Instant writtenAt,
    boolean quarantined) {

  public MemoryEntry {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(writtenAt, "writtenAt");
  }

  /** The same entry released from quarantine. */
  public MemoryEntry promoted() {
    return new MemoryEntry(key, value, provenance, writerAgentId, runId, writtenAt, false);
  }
}
