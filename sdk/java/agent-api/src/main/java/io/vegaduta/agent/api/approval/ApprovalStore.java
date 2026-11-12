package io.vegaduta.agent.api.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persistence for approval requests. The in-memory implementation is for tests and single-node use;
 * {@code vegaduta-agent-jdbc} provides a durable one.
 *
 * <p><b>Compare-and-set requirement.</b> Every decision (approve, reject, expire) is written with
 * {@link #saveIfStatus}, and a store shared by more than one gate instance or process MUST
 * implement it atomically: apply the write only if the stored status still equals {@code expected}
 * and report whether it did (for SQL, {@code UPDATE ... WHERE id = ? AND status = ?} and check the
 * affected row count; do not read then write). That is the only thing that stops two approvers from
 * both winning and the action executing twice. The interface default is a non-atomic
 * find-then-save, safe only for a single thread; it exists so simple test stores compile, not as a
 * multi-process implementation. {@code InMemoryApprovalStore} and {@code JdbcApprovalStore} are the
 * references.
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
   * non-atomic find-then-save and is NOT safe across threads, gates or processes: every durable or
   * shared store must override it with an atomic conditional update (see the class comment).
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
