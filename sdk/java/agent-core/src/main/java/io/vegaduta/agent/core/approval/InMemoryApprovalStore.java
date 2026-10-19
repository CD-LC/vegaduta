package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.approval.ApprovalStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Keeps approval requests in a map. For tests, demos and single-node applications. */
public final class InMemoryApprovalStore implements ApprovalStore {

  private final Map<UUID, ApprovalRequest> byId = new ConcurrentHashMap<>();

  @Override
  public void save(ApprovalRequest request) {
    byId.put(request.id(), request);
  }

  @Override
  public Optional<ApprovalRequest> find(UUID id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public boolean saveIfStatus(ApprovalRequest request, ApprovalStatus expected) {
    boolean[] saved = new boolean[1];
    byId.computeIfPresent(
        request.id(),
        (id, current) -> {
          if (current.status() != expected) {
            return current;
          }
          saved[0] = true;
          return request;
        });
    return saved[0];
  }

  @Override
  public List<ApprovalRequest> findPending(@Nullable String tenantId, int limit) {
    return byId.values().stream()
        .filter(r -> r.status() == ApprovalStatus.PENDING)
        .filter(r -> tenantId == null || Objects.equals(tenantId, r.context().tenantId()))
        .sorted(Comparator.comparing(ApprovalRequest::createdAt).reversed())
        .limit(limit)
        .collect(Collectors.toList());
  }

  @Override
  public List<ApprovalRequest> findByRun(String runId) {
    List<ApprovalRequest> list =
        byId.values().stream()
            .filter(r -> runId.equals(r.context().runId()))
            .sorted(Comparator.comparing(ApprovalRequest::createdAt))
            .collect(Collectors.toCollection(ArrayList::new));
    return List.copyOf(list);
  }

  public int size() {
    return byId.size();
  }
}
