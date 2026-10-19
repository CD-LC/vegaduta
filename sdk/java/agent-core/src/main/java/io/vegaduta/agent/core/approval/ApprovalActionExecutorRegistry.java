package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.approval.ApprovalActionExecutor;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Executors by action type. */
public final class ApprovalActionExecutorRegistry {

  private final Map<String, ApprovalActionExecutor> byType = new ConcurrentHashMap<>();

  public void register(ApprovalActionExecutor executor) {
    byType.put(executor.actionType(), executor);
  }

  public void unregister(String actionType) {
    byType.remove(actionType);
  }

  public Optional<ApprovalActionExecutor> find(String actionType) {
    return Optional.ofNullable(byType.get(actionType));
  }
}
