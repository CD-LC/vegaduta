package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.core.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What changed between a pinned {@link ToolManifest} and the one currently offered.
 *
 * <p>A tool appears in at most one of {@code added} and {@code removed}, but may appear in several
 * of the {@code changed*} lists when more than one part of it changed. {@code
 * suspiciousDescriptions} maps a tool name to the guardrail rule codes its description matched; it
 * is filled from the current manifest regardless of whether the description changed, because a pin
 * taken on first use may already contain a poisoned description.
 *
 * @param pinnedDigest digest of the pinned manifest
 * @param currentDigest digest of the current manifest
 * @param added tools present now but not in the pin
 * @param removed tools in the pin but absent now
 * @param changedDescription tools whose description digest differs
 * @param changedSchema tools whose input-schema digest differs
 * @param changedMetadata tools whose metadata digest differs
 * @param suspiciousDescriptions tools whose description matched an injection rule, with the codes
 */
@Experimental
public record DriftReport(
    String pinnedDigest,
    String currentDigest,
    List<String> added,
    List<String> removed,
    List<String> changedDescription,
    List<String> changedSchema,
    List<String> changedMetadata,
    Map<String, List<String>> suspiciousDescriptions) {

  public DriftReport {
    Objects.requireNonNull(pinnedDigest, "pinnedDigest");
    Objects.requireNonNull(currentDigest, "currentDigest");
    added = sortedCopy(added);
    removed = sortedCopy(removed);
    changedDescription = sortedCopy(changedDescription);
    changedSchema = sortedCopy(changedSchema);
    changedMetadata = sortedCopy(changedMetadata);
    Map<String, List<String>> suspicious = new LinkedHashMap<>();
    for (String tool : new TreeSet<>(suspiciousDescriptions.keySet())) {
      suspicious.put(tool, List.copyOf(suspiciousDescriptions.get(tool)));
    }
    suspiciousDescriptions = Collections.unmodifiableMap(suspicious);
  }

  /** A report for two identical manifests. */
  public static DriftReport none(String digest) {
    return new DriftReport(
        digest, digest, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
  }

  /** True when anything differs or a description looks suspicious. */
  public boolean hasDrift() {
    return !added.isEmpty()
        || !removed.isEmpty()
        || !changedDescription.isEmpty()
        || !changedSchema.isEmpty()
        || !changedMetadata.isEmpty()
        || !suspiciousDescriptions.isEmpty();
  }

  /**
   * True when a call to {@code toolName} should be treated as running unapproved code or text: the
   * tool was added, or any part of it changed, or its description is suspicious. Removed tools are
   * not affected; they cannot be called.
   */
  public boolean affects(String toolName) {
    return added.contains(toolName)
        || changedDescription.contains(toolName)
        || changedSchema.contains(toolName)
        || changedMetadata.contains(toolName)
        || suspiciousDescriptions.containsKey(toolName);
  }

  /** The tools {@link #affects(String)} is true for, sorted. */
  public Set<String> affectedTools() {
    TreeSet<String> tools = new TreeSet<>(added);
    tools.addAll(changedDescription);
    tools.addAll(changedSchema);
    tools.addAll(changedMetadata);
    tools.addAll(suspiciousDescriptions.keySet());
    return Collections.unmodifiableSet(tools);
  }

  /** The drift categories {@code toolName} falls into, for audit attributes. */
  public List<String> categoriesFor(String toolName) {
    List<String> out = new ArrayList<>(4);
    if (added.contains(toolName)) {
      out.add("added");
    }
    if (changedSchema.contains(toolName)) {
      out.add("schema");
    }
    if (changedDescription.contains(toolName)) {
      out.add("description");
    }
    if (changedMetadata.contains(toolName)) {
      out.add("metadata");
    }
    if (suspiciousDescriptions.containsKey(toolName)) {
      out.add("suspicious_description");
    }
    return out;
  }

  /** The highest severity across the whole report; see {@link DriftSeverity}. */
  public DriftSeverity severity() {
    if (!added.isEmpty() || !changedSchema.isEmpty() || !suspiciousDescriptions.isEmpty()) {
      return DriftSeverity.HIGH;
    }
    if (!changedDescription.isEmpty() || !changedMetadata.isEmpty()) {
      return DriftSeverity.MEDIUM;
    }
    if (!removed.isEmpty()) {
      return DriftSeverity.LOW;
    }
    return DriftSeverity.NONE;
  }

  /** The severity of the drift affecting one tool. */
  public DriftSeverity severityFor(String toolName) {
    if (added.contains(toolName)
        || changedSchema.contains(toolName)
        || suspiciousDescriptions.containsKey(toolName)) {
      return DriftSeverity.HIGH;
    }
    if (changedDescription.contains(toolName) || changedMetadata.contains(toolName)) {
      return DriftSeverity.MEDIUM;
    }
    if (removed.contains(toolName)) {
      return DriftSeverity.LOW;
    }
    return DriftSeverity.NONE;
  }

  /** A short one-line summary for logs and audit details. */
  public String summary() {
    if (!hasDrift()) {
      return "no drift (" + currentDigest + ")";
    }
    return "severity="
        + severity()
        + " added="
        + added.size()
        + " removed="
        + removed.size()
        + " changedSchema="
        + changedSchema.size()
        + " changedDescription="
        + changedDescription.size()
        + " changedMetadata="
        + changedMetadata.size()
        + " suspicious="
        + suspiciousDescriptions.size();
  }

  /** The report as Markdown, for a pull-request comment or an approval request. */
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("## Tool manifest drift: ").append(severity()).append('\n').append('\n');
    sb.append("- pinned: `").append(pinnedDigest).append("`\n");
    sb.append("- current: `").append(currentDigest).append("`\n");
    if (!hasDrift()) {
      sb.append('\n').append("No differences.\n");
      return sb.toString();
    }
    section(sb, "Added tools (HIGH)", added);
    section(sb, "Schema changed (HIGH)", changedSchema);
    section(sb, "Description changed (MEDIUM)", changedDescription);
    section(sb, "Metadata changed (MEDIUM)", changedMetadata);
    section(sb, "Removed tools (LOW)", removed);
    if (!suspiciousDescriptions.isEmpty()) {
      sb.append('\n').append("### Suspicious descriptions (HIGH)\n");
      for (Map.Entry<String, List<String>> e : suspiciousDescriptions.entrySet()) {
        sb.append("- ")
            .append(Text.code(e.getKey()))
            .append(": ")
            .append(Text.bounded(String.join(", ", e.getValue()), Text.CELL))
            .append('\n');
      }
    }
    return sb.toString();
  }

  private static void section(StringBuilder sb, String title, List<String> tools) {
    if (tools.isEmpty()) {
      return;
    }
    sb.append('\n').append("### ").append(title).append('\n');
    for (String t : tools) {
      sb.append("- ").append(Text.code(t)).append('\n');
    }
  }

  private static List<String> sortedCopy(List<String> values) {
    return List.copyOf(new TreeSet<>(values));
  }
}
