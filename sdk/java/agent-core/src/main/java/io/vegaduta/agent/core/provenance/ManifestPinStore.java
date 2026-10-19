package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import java.util.Optional;

/**
 * Keeps the {@link ToolManifest} an agent was approved against, by key. The key is whatever scope
 * the host pins at: an agent id, an MCP server id, or a single well-known name for the whole
 * deployment.
 *
 * <p>Implementations must be safe to call from several threads.
 */
@Experimental
public interface ManifestPinStore {

  /** The pinned manifest for {@code key}, or empty when nothing has been pinned yet. */
  Optional<ToolManifest> load(String key);

  /** Pins {@code manifest} for {@code key}, replacing any previous pin. */
  void save(String key, ToolManifest manifest);

  /**
   * Removes the pin for {@code key}.
   *
   * @return true when a pin existed
   */
  boolean remove(String key);

  /**
   * A cheap token that changes whenever the pin for {@code key} changes (a file's modification
   * time, a row version), so {@link ProvenanceGate} can notice a pin written by an operator while
   * the live tool list is unchanged. The default returns empty, in which case the gate re-reads the
   * pin on a bounded interval while it is denying.
   */
  default Optional<String> version(String key) {
    return Optional.empty();
  }
}
