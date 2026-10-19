package com.agenticai.core.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * An admin-set override of an action type's default approval policy - see
 * {@link ApprovalPolicyService} for how this combines with the hardcoded
 * default-sensitive set.
 */
@Entity
@Table(
    name = "approval_policy_override",
    uniqueConstraints = @UniqueConstraint(columnNames = "action_type")
)
public class ApprovalPolicyOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "action_type", nullable = false, length = 128)
    private String actionType;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApprovalPolicyOverride() {
        // JPA
    }

    public ApprovalPolicyOverride(String actionType, boolean requiresApproval) {
        this.actionType = actionType;
        this.requiresApproval = requiresApproval;
        this.updatedAt = Instant.now();
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getActionType() {
        return actionType;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
