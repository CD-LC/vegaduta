package io.vegaduta.agent.testkit.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.tool.ToolStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceRoundTripTest {

  private static final Instant AT = Instant.parse("2026-10-14T10:15:30Z");

  static TraceEntry entry(int seq, String tool, String args, ToolStatus status, String output) {
    return new TraceEntry(seq, tool, args, status, output, null, AT, "run-1", "agent-a");
  }

  @Test
  void jsonlRoundTripPreservesUnicodeQuotesNewlinesAndNulls(@TempDir Path dir) {
    TraceEntry weird =
        new TraceEntry(
            1,
            "search",
            "{\"q\":\"\\\"quoted\\\" 日本語 😀\",\"n\":1}",
            ToolStatus.OK,
            "line1\nline2 \"q\" \\ tab\t émoji 😀",
            null,
            AT,
            "run-1",
            null);
    TraceEntry denied =
        new TraceEntry(
            2, "pay", "{}", ToolStatus.DENIED, "pay was refused", "unclassified", AT, "run-1", "a");
    Trace trace = Trace.of(List.of(weird, denied), Map.of("model", "fake-1", "note", "a \"b\""));

    Path file = dir.resolve("nested/golden.jsonl");
    trace.save(file);
    Trace loaded = Trace.load(file);

    assertThat(loaded).isEqualTo(trace);
    assertThat(loaded.entries()).containsExactly(weird, denied);
    assertThat(loaded.metadata()).containsEntry("note", "a \"b\"");
    assertThat(loaded.canonicalForm()).isEqualTo(trace.canonicalForm());
  }

  @Test
  void fileIsOneJsonObjectPerLineWithAHeader(@TempDir Path dir) throws Exception {
    Trace trace = Trace.of(List.of(entry(1, "t", "{\"a\":1}", ToolStatus.OK, "o")));
    Path file = dir.resolve("t.jsonl");
    trace.save(file);
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0)).contains("\"format\":\"vegaduta-trace\"").contains("\"version\":1");
    assertThat(lines.get(1)).startsWith("{\"seq\":1,\"tool\":\"t\",\"args\":\"{\\\"a\\\":1}\"");
    assertThat(lines.get(1)).contains("\"at\":\"2026-10-14T10:15:30Z\"");
  }

  @Test
  void canonicalFormOmitsVolatileFieldsAndCanonicalisesArguments() {
    Trace a = Trace.of(List.of(entry(1, "t", "{\"b\":2, \"a\":1}", ToolStatus.OK, "o")));
    Trace b =
        Trace.of(
            List.of(
                new TraceEntry(
                    7,
                    "t",
                    "{\"a\":1,\"b\":2}",
                    ToolStatus.OK,
                    "o",
                    null,
                    Instant.EPOCH,
                    "x",
                    null)));
    assertThat(a.canonicalForm())
        .isEqualTo(b.canonicalForm())
        .isEqualTo(
            "{\"seq\":1,\"tool\":\"t\",\"args\":\"{\\\"a\\\":1,\\\"b\\\":2}\",\"status\":\"OK\","
                + "\"output\":\"o\",\"reason\":null}\n");
  }

  @Test
  void malformedLinesAreReportedWithTheirLineNumber() {
    String jsonl = "{\"format\":\"vegaduta-trace\",\"version\":1,\"metadata\":{}}\n{\"seq\":1}\n";
    assertThatThrownBy(() -> Trace.fromJsonl(jsonl))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2");
  }
}
