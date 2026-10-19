package io.vegaduta.agent.api.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persistence for approval requests. The in-memory implementation is for tests and single-node use;
 * {@code vegaduta-agent-jdbc} provides a durable one.
 */
public interface ApprovalStore {

  /** Inserts or replaces by id. */
  void save(ApprovalRequest request);

  Optional<ApprovalRequest> find(UUID id);

  /**
   * Pending requests, newest first, optionally limited to one tenant.
   *
   * @param tenantId tenant to filter by, or {@code null} for all
   * @param limit maximum number of rows
   */
  List<ApprovalRequest> findPending(@Nullable String tenantId, int limit);

  /** Requests of the given run, in creation order. */
  List<ApprovalRequest> findByRun(String runId);

  /**
   * Saves {@code request} only when the stored request with the same id currently has status {@code
   * expected}, so two concurrent decisions on one request cannot both win. The default is a
   * non-atomic find-then-save; durable stores should override it with a conditional update.
   *
   * @return true when the request was saved
   */
  default boolean saveIfStatus(ApprovalRequest request, ApprovalStatus expected) {
    Optional<ApprovalRequest> current = find(request.id());
    if (current.isEmpty() || current.get().status() != expected) {
      return false;
    }
    save(request);
    return true;
  }
}
