package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Keeps receipts in a map. For tests, demos and single-node applications. */
@Experimental
public final class InMemoryReceiptStore implements ReceiptStore {

  private final Map<UUID, ApprovalReceipt> byApproval = new ConcurrentHashMap<>();

  @Override
  public void save(ApprovalReceipt receipt) {
    ApprovalReceipt existing = byApproval.putIfAbsent(receipt.approvalId(), receipt);
    if (existing != null && !existing.equals(receipt)) {
      throw new IllegalStateException(
          "a different receipt already exists for approval " + receipt.approvalId());
    }
  }

  @Override
  public Optional<ApprovalReceipt> find(UUID approvalId) {
    return Optional.ofNullable(byApproval.get(approvalId));
  }

  @Override
  public List<ApprovalReceipt> findByTenant(@Nullable String tenantId) {
    return byApproval.values().stream()
        .filter(r -> tenantId == null || Objects.equals(tenantId, r.tenantId()))
        .sorted(
            Comparator.comparing(ApprovalReceipt::decidedAt)
                .thenComparing(r -> r.approvalId().toString()))
        .collect(Collectors.toUnmodifiableList());
  }

  public int size() {
    return byApproval.size();
  }
}
