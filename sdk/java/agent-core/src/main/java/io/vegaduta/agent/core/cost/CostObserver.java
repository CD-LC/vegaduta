package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import io.vegaduta.agent.api.tool.ToolStatus;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the {@link CostLedger} from the governed-tool pipeline. Every call records exactly one
 * {@link CostLedger.ToolCallCost}: priced when the tool ran, zero when it was denied, replayed,
 * parked for approval or refused by the budget. A pipeline that throws is recorded as {@link
 * ToolStatus#ERROR} and priced, since the tool may have run.
 *
 * <p>Register it with {@code Governance.builder().observer(new CostObserver(ledger))}.
 */
@Experimental
public final class CostObserver implements ToolCallObserver {

  private static final Logger log = LoggerFactory.getLogger(CostObserver.class);

  private final CostLedger ledger;

  public CostObserver(CostLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  public CostLedger ledger() {
    return ledger;
  }

  @Override
  public Scope onStart(ToolCall call, RunContext context) {
    return new Scope() {
      private boolean recorded;

      @Override
      public void onResult(ToolResult result) {
        record(result.status());
      }

      @Override
      public void onError(Throwable error) {
        record(ToolStatus.ERROR);
      }

      @Override
      public void close() {
        // A scope closed without a result or error saw neither branch; nothing to charge.
      }

      private void record(ToolStatus status) {
        if (recorded) {
          return;
        }
        recorded = true;
        try {
          ledger.recordToolCall(context, call.toolName(), status);
        } catch (RuntimeException e) {
          log.warn("Cost ledger rejected tool call '{}': {}", call.toolName(), e.toString());
        }
      }
    };
  }
}
