package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernedTool;
import io.vegaduta.agent.core.classification.LethalTrifectaDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Wraps Spring AI tool callbacks. Wrapping is idempotent and cached per delegate instance, so the
 * same callback governed twice yields the same wrapper and the same approval executor.
 */
public final class ToolGovernor {

  private final Governance governance;
  private final RunContextResolver resolver;
  private final Map<ToolCallback, GovernedToolCallback> cache = new WeakHashMap<>();

  public ToolGovernor(Governance governance) {
    this(governance, RunContextResolver.defaults());
  }

  public ToolGovernor(Governance governance, RunContextResolver resolver) {
    this.governance = governance;
    this.resolver = resolver;
  }

  public Governance governance() {
    return governance;
  }

  public GovernedToolCallback govern(ToolCallback callback) {
    if (callback instanceof GovernedToolCallback already) {
      return already;
    }
    synchronized (cache) {
      return cache.computeIfAbsent(
          callback, c -> new GovernedToolCallback(c, governance, resolver));
    }
  }

  /** Wraps every callback and applies the lethal-trifecta rule to the set. */
  public List<ToolCallback> governAll(
      Collection<? extends ToolCallback> callbacks, RunContext context) {
    List<ToolCallback> result = new ArrayList<>(callbacks.size());
    List<GovernedTool> governed = new ArrayList<>(callbacks.size());
    for (ToolCallback callback : callbacks) {
      GovernedToolCallback wrapped = govern(callback);
      result.add(wrapped);
      governed.add(wrapped.governed());
    }
    governance.governAll(governed.stream().map(GovernedTool::delegate).toList(), context);
    return result;
  }

  public List<ToolCallback> governAll(Collection<? extends ToolCallback> callbacks) {
    return governAll(callbacks, RunContext.anonymous());
  }

  public ToolCallbackProvider govern(ToolCallbackProvider provider) {
    if (provider instanceof GovernedToolCallbackProvider already) {
      return already;
    }
    return new GovernedToolCallbackProvider(provider, this);
  }

  /** The trifecta report for a set of callbacks, without wrapping them. */
  public LethalTrifectaDetector.Report analyse(Collection<? extends ToolCallback> callbacks) {
    List<ToolClassification> classifications =
        callbacks.stream()
            .map(c -> governance.resolver().effective(c.getToolDefinition().name()))
            .toList();
    return governance.trifecta().analyse(classifications);
  }

  /** A provider whose callbacks are governed on every {@code getToolCallbacks()}. */
  public static final class GovernedToolCallbackProvider implements ToolCallbackProvider {
    private final ToolCallbackProvider delegate;
    private final ToolGovernor governor;

    GovernedToolCallbackProvider(ToolCallbackProvider delegate, ToolGovernor governor) {
      this.delegate = delegate;
      this.governor = governor;
    }

    public ToolCallbackProvider delegate() {
      return delegate;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
      ToolCallback[] raw = delegate.getToolCallbacks();
      ToolCallback[] governed = new ToolCallback[raw.length];
      for (int i = 0; i < raw.length; i++) {
        governed[i] = governor.govern(raw[i]);
      }
      return governed;
    }
  }
}
