package io.vegaduta.agent.testkit.trace;

import static io.vegaduta.agent.testkit.trace.TraceAssert.assertThat;
import static io.vegaduta.agent.testkit.trace.TraceRoundTripTest.entry;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceAssertTest {

  private static final Trace TRACE =
      Trace.of(
          List.of(
              entry(1, "lookup", "{\"order\":42}", ToolStatus.OK, "found"),
              entry(2, "refund", "{\"order\":42}", ToolStatus.DENIED, "no"),
              entry(3, "notify", "{\"to\":\"a\"}", ToolStatus.OK, "sent")));

  @Test
  void passingAssertionsChain() {
    assertThat(TRACE)
        .hasSize(3)
        .hasCall("lookup")
        .hasNoCall("pay")
        .hasCallCount("refund", 1)
        .callArgsContain("lookup", "\"order\":42")
        .hasStatus("refund", ToolStatus.DENIED)
        .hasNoStatus(ToolStatus.ERROR)
        .hasCallsInOrder("lookup", "notify");
    assertThat(Trace.empty()).isEmpty().noCallAfterStatus(ToolStatus.DENIED);
  }

  @Test
  void failingAssertionsExplainThemselves() {
    assertThatThrownBy(() -> assertThat(TRACE).noCallAfterStatus(ToolStatus.DENIED))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("went on with")
        .hasMessageContaining("notify");
    assertThatThrownBy(() -> assertThat(TRACE).hasCall("pay"))
        .hasMessageContaining("Expected a call to 'pay'");
    assertThatThrownBy(() -> assertThat(TRACE).hasCallCount("lookup", 2))
        .hasMessageContaining("Expected 2 call(s) to 'lookup' but found 1");
    assertThatThrownBy(() -> assertThat(TRACE).callArgsContain("lookup", "\"order\":1}"))
        .hasMessageContaining("arguments containing");
    assertThatThrownBy(() -> assertThat(TRACE).hasCallsInOrder("notify", "lookup"))
        .hasMessageContaining("in order");
    assertThatThrownBy(() -> assertThat(TRACE).hasNoStatus(ToolStatus.DENIED))
        .hasMessageContaining("refund");
  }
}
