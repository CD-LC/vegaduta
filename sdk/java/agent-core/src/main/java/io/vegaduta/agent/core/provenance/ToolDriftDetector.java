package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares a pinned {@link ToolManifest} to the one currently offered and classifies the drift.
 *
 * <p>Categories, with severity in {@link DriftSeverity}:
 *
 * <ul>
 *   <li>added: a tool the pin never saw (HIGH)
 *   <li>schema changed: the arguments object changed shape (HIGH)
 *   <li>description changed: the model's instructions for the tool changed (MEDIUM). Description
 *       text is read by the model with the trust of a system prompt, so a changed description can
 *       carry injected instructions even when nothing executable changed. The description guard
 *       additionally flags descriptions that match an injection rule (HIGH).
 *   <li>metadata changed: origin, server, version (MEDIUM)
 *   <li>removed: capability the agent lost (LOW)
 * </ul>
 */
@Experimental
public final class ToolDriftDetector {

  private final Optional<ToolDescriptionGuard> guard;

  /** Compares digests and scans current descriptions with the built-in injection rules. */
  public ToolDriftDetector() {
    this(new ToolDescriptionGuard());
  }

  /** Compares digests and scans current descriptions with {@code guard}. */
  public ToolDriftDetector(ToolDescriptionGuard guard) {
    this.guard = Optional.of(Objects.requireNonNull(guard, "guard"));
  }

  private ToolDriftDetector(Optional<ToolDescriptionGuard> guard) {
    this.guard = guard;
  }

  /** Compares digests only; no description scan. */
  public static ToolDriftDetector digestsOnly() {
    return new ToolDriftDetector(Optional.empty());
  }

  public DriftReport compare(ToolManifest pinned, ToolManifest current) {
    Objects.requireNonNull(pinned, "pinned");
    Objects.requireNonNull(current, "current");
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> changedDescription = new ArrayList<>();
    List<String> changedSchema = new ArrayList<>();
    List<String> changedMetadata = new ArrayList<>();

    for (ToolManifest.Entry now : current.entries()) {
      Optional<ToolManifest.Entry> before = pinned.entry(now.name());
      if (before.isEmpty()) {
        added.add(now.name());
        continue;
      }
      ToolManifest.Entry was = before.get();
      if (was.digest().equals(now.digest())) {
        continue;
      }
      if (!was.descriptionDigest().equals(now.descriptionDigest())) {
        changedDescription.add(now.name());
      }
      if (!was.schemaDigest().equals(now.schemaDigest())) {
        changedSchema.add(now.name());
      }
      if (!was.metadataDigest().equals(now.metadataDigest())) {
        changedMetadata.add(now.name());
      }
    }
    for (ToolManifest.Entry was : pinned.entries()) {
      if (!current.contains(was.name())) {
        removed.add(was.name());
      }
    }
    Map<String, List<String>> suspicious = guard.map(g -> g.scan(current)).orElse(Map.of());
    return new DriftReport(
        pinned.digest(),
        current.digest(),
        added,
        removed,
        changedDescription,
        changedSchema,
        changedMetadata,
        suspicious);
  }
}
