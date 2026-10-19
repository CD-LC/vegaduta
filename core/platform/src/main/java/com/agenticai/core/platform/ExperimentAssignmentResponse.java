package com.agenticai.core.platform;

import com.agenticai.core.tenancy.ExperimentAssignment;
import java.time.Instant;

/** Master Roadmap 2026 item #13 - one row of the "which tenant got which variant" admin view. See {@link ExperimentAssignment}'s javadoc. */
public record ExperimentAssignmentResponse(String tenantId, String variant, Instant assignedAt) {
    public static ExperimentAssignmentResponse from(ExperimentAssignment assignment) {
        return new ExperimentAssignmentResponse(assignment.getTenantId(), assignment.getVariant(), assignment.getAssignedAt());
    }
}
