package com.agenticai.core.brief.dto;

import com.agenticai.core.brief.model.Brief;
import java.util.List;

/**
 * What {@code BriefPlannerService#plan} returns: the proposed brief (or, when it is asking, a
 * brief carrying only {@code clarifications[]}) and human-readable warnings - things the planner
 * dropped instead of inventing, and validation problems the user can fix before saving.
 */
public record PlanResult(Brief brief, List<String> warnings) {
    public PlanResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
