package io.vegaduta.agent.testkit.trace;

import static io.vegaduta.agent.testkit.trace.TraceRoundTripTest.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.tool.ToolStatus;
import io.vegaduta.agent.testkit.trace.TraceDiff.Kind;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class TraceDiffTest {

  private static final String REFUND_ARGS = "{\"order\":42,\"amount\":10}";

  private static final Trace EXPECTED =
      Trace.of(
          List.of(
              entry(1, "lookup", "{\"order\":42}", ToolStatus.OK, "found"),
              entry(2, "refund", REFUND_ARGS, ToolStatus.PENDING_APPROVAL, "wait"),
              entry(3, "notify", "{\"to\":\"a\"}", ToolStatus.OK, "sent")));

  private static TraceEntry other(
      int seq, String tool, String args, ToolStatus status, String output, String reason) {
    return new TraceEntry(seq, tool, args, status, output, reason, Instant.EPOCH, "other", "x");
  }

  @Test
  void identicalTracesHaveNoDifferencesEvenWhenVolatileFieldsDiffer() {
    Trace actual =
        Trace.of(
            List.of(
                other(9, "lookup", "{ \"order\" : 42 }", ToolStatus.OK, "found", null),
                other(
                    10,
                    "refund",
                    "{\"amount\":10.0,\"order\":42}",
                    ToolStatus.PENDING_APPROVAL,
                    "wait",
                    "approval_required"),
                other(11, "notify", "{\"to\":\"a\"}", ToolStatus.OK, "sent", null)));
    TraceDiff diff = TraceDiff.compare(EXPECTED, actual);
    assertThat(diff.isEmpty()).as(diff.toMarkdown()).isTrue();
    assertThat(diff.toMarkdown()).isEqualTo("No differences.\n");
  }

  @Test
  void detectsAChangedStatus() {
    Trace actual =
        Trace.of(
            List.of(
                EXPECTED.entries().get(0),
                entry(2, "refund", "{\"order\":42,\"amount\":10}", ToolStatus.DENIED, "no"),
                EXPECTED.entries().get(2)));
    TraceDiff diff = TraceDiff.compare(EXPECTED, actual);
    assertThat(diff.differences()).hasSize(1);
    TraceDiff.Difference d = diff.differences().get(0);
    assertThat(d.kind()).isEqualTo(Kind.CHANGED_STATUS);
    assertThat(d.detail()).contains("PENDING_APPROVAL -> DENIED");
    assertThat(diff.toMarkdown()).contains("| 1 | CHANGED_STATUS |").contains("refund");
    assertThatThrownBy(() -> TraceAssert.assertThat(actual).matches(EXPECTED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("CHANGED_STATUS");
  }

  @Test
  void detectsAddedRemovedReorderedAndChangedArgumentsAndOutput() {
    Trace actual =
        Trace.of(
            List.of(
                entry(1, "lookup", "{\"order\":43}", ToolStatus.OK, "found"), // args changed
                entry(2, "notify", "{\"to\":\"a\"}", ToolStatus.OK, "sent"), // moved before refund
                entry(3, "refund", REFUND_ARGS, ToolStatus.PENDING_APPROVAL, "wait"),
                entry(4, "audit", "{}", ToolStatus.OK, "ok"))); // added
    TraceDiff diff = TraceDiff.compare(EXPECTED, actual);

    assertThat(diff.of(Kind.REORDERED)).hasSize(1);
    assertThat(diff.of(Kind.REORDERED).get(0).detail())
        .contains("notify")
        .contains("moved from position 3 to 2");
    assertThat(diff.of(Kind.CHANGED_ARGS)).hasSize(1);
    assertThat(diff.of(Kind.CHANGED_ARGS).get(0).detail()).contains("~ order: 42 -> 43");
    assertThat(diff.of(Kind.ADDED)).extracting(d -> d.actual().toolName()).containsExactly("audit");
    assertThat(diff.differences()).hasSize(3);
    assertThat(diff.toMarkdown()).contains("| REORDERED |").contains("| CHANGED_ARGS |");

    Trace shorter = Trace.of(List.of(EXPECTED.entries().get(0), EXPECTED.entries().get(2)));
    TraceDiff removed = TraceDiff.compare(EXPECTED, shorter);
    assertThat(removed.differences()).hasSize(1);
    assertThat(removed.of(Kind.REMOVED))
        .extracting(d -> d.expected().toolName())
        .containsExactly("refund");

    Trace outputChanged =
        Trace.of(
            List.of(
                EXPECTED.entries().get(0),
                EXPECTED.entries().get(1),
                entry(3, "notify", "{\"to\":\"a\"}", ToolStatus.OK, "bounced")));
    assertThat(TraceDiff.compare(EXPECTED, outputChanged).of(Kind.CHANGED_OUTPUT)).hasSize(1);
  }

  @Test
  void redactionHidesVolatileArgumentFieldsBeforeComparing() {
    Trace actual =
        Trace.of(
            List.of(
                entry(1, "lookup", "{\"order\":42,\"requestId\":\"abc\"}", ToolStatus.OK, "found"),
                EXPECTED.entries().get(1),
                EXPECTED.entries().get(2)));
    assertThat(TraceDiff.compare(EXPECTED, actual).of(Kind.CHANGED_ARGS)).hasSize(1);
    UnaryOperator<String> redact = args -> args.replaceAll(",\"requestId\":\"[^\"]*\"", "");
    TraceDiff redacted = TraceDiff.compare(EXPECTED, actual, redact);
    assertThat(redacted.isEmpty()).as(redacted.toMarkdown()).isTrue();
    TraceAssert.assertThat(actual).matches(EXPECTED, redact);
  }
}
