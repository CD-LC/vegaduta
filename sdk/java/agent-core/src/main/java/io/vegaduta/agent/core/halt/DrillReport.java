package io.vegaduta.agent.core.halt;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.tool.ToolResult;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The evidence one {@link HaltDrill} produced. {@link #passed()} is true only when the tool was
 * denied by the halt while it was engaged <em>and</em> was no longer denied by it after release.
 *
 * @param id the drill id (also the {@code targetId} of its {@code halt.drill} audit event)
 * @param toolName the tool that was driven
 * @param scope the scope that was halted
 * @param haltId the id of the halt engaged for the drill
 * @param by who ran the drill
 * @param startedAt when the drill started
 * @param millis how long engage, two invocations and release took, by the drill's clock
 * @param deniedWhileHalted whether the halted invocation was denied by the halt
 * @param resumedAfterRelease whether the post-release invocation was no longer denied by the halt
 * @param haltedResult what the governed tool returned while halted, or {@code null} if it threw
 * @param resumedResult what it returned after release, or {@code null} if it threw
 * @param error the exception message when an invocation threw, else {@code null}
 */
@Experimental
public record DrillReport(
    String id,
    String toolName,
    HaltScope scope,
    String haltId,
    String by,
    Instant startedAt,
    long millis,
    boolean deniedWhileHalted,
    boolean resumedAfterRelease,
    @Nullable ToolResult haltedResult,
    @Nullable ToolResult resumedResult,
    @Nullable String error) {

  public DrillReport {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(haltId, "haltId");
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(startedAt, "startedAt");
  }

  /** Whether the kill switch was demonstrated to work end to end. */
  public boolean passed() {
    return deniedWhileHalted && resumedAfterRelease && error == null;
  }

  /** A short report for a compliance file or a chat message. */
  public String toMarkdown() {
    StringBuilder sb = new StringBuilder(512);
    sb.append("## Halt drill ").append(passed() ? "PASSED" : "FAILED").append('\n').append('\n');
    sb.append("| Field | Value |\n|---|---|\n");
    row(sb, "Drill id", id);
    row(sb, "Tool", toolName);
    row(sb, "Scope", scope.toString());
    row(sb, "Halt id", haltId);
    row(sb, "Run by", by);
    row(sb, "Started at", startedAt.toString());
    row(sb, "Duration", millis + " ms");
    row(sb, "Denied while halted", check(deniedWhileHalted));
    row(sb, "Resumed after release", check(resumedAfterRelease));
    row(sb, "Halted invocation", describe(haltedResult));
    row(sb, "Post-release invocation", describe(resumedResult));
    if (error != null) {
      row(sb, "Error", error);
    }
    sb.append('\n');
    if (passed()) {
      sb.append(
          "The tool was refused by the halt gate while the halt was engaged and ran again"
              + " once it was released. No tool code executed while halted.\n");
    } else if (!deniedWhileHalted) {
      sb.append(
          "The tool was NOT stopped by the halt. Check that a `HaltGate` backed by this"
              + " `HaltSwitch` is the first gate in the governance chain.\n");
    } else {
      sb.append("The tool stayed blocked by the halt after release; check for duplicate halts.\n");
    }
    return sb.toString();
  }

  private static void row(StringBuilder sb, String field, String value) {
    sb.append("| ")
        .append(field)
        .append(" | ")
        .append(value.replace("|", "\\|").replace("\n", " "))
        .append(" |\n");
  }

  private static String check(boolean value) {
    return value ? "yes" : "no";
  }

  private static String describe(@Nullable ToolResult result) {
    if (result == null) {
      return "threw";
    }
    return result.reason() == null
        ? result.status().name()
        : result.status().name() + " (" + result.reason() + ")";
  }
}
