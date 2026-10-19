package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Persistence for {@link ApprovalReceipt}s. Receipts are evidence: a store should be append-only in
 * production and outlive the approval store it was derived from.
 */
@Experimental
public interface ReceiptStore {

  /**
   * Stores the receipt. Receipts are append-only evidence: a second receipt for the same approval
   * id is ignored when it is identical and must be rejected with {@link IllegalStateException} when
   * it differs, never silently replaced.
   */
  void save(ApprovalReceipt receipt);

  Optional<ApprovalReceipt> find(UUID approvalId);

  /**
   * Receipts in decision order, oldest first.
   *
   * @param tenantId tenant to filter by, or {@code null} for all
   */
  List<ApprovalReceipt> findByTenant(@Nullable String tenantId);
}
