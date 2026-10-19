package io.vegaduta.agent.core.observe;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.api.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** Fans out to several observers. */
public final class CompositeToolCallObserver implements ToolCallObserver {

  private final List<ToolCallObserver> observers;

  public CompositeToolCallObserver(List<ToolCallObserver> observers) {
    this.observers = List.copyOf(observers);
  }

  public static ToolCallObserver of(List<ToolCallObserver> observers) {
    if (observers.isEmpty()) {
      return ToolCallObserver.none();
    }
    return observers.size() == 1 ? observers.get(0) : new CompositeToolCallObserver(observers);
  }

  @Override
  public Scope onStart(ToolCall call, RunContext context) {
    List<Scope> scopes = new ArrayList<>(observers.size());
    for (ToolCallObserver observer : observers) {
      scopes.add(observer.onStart(call, context));
    }
    return new Scope() {
      @Override
      public void onResult(ToolResult result) {
        scopes.forEach(s -> s.onResult(result));
      }

      @Override
      public void onError(Throwable error) {
        scopes.forEach(s -> s.onError(error));
      }

      @Override
      public void close() {
        for (int i = scopes.size() - 1; i >= 0; i--) {
          scopes.get(i).close();
        }
      }
    };
  }
}
