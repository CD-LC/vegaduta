package com.agenticai.core.audit.dto;

import com.agenticai.core.audit.IssueCategory;

/** #974 - request body for a tenant admin self-filing a support ticket from the Billing & usage page. */
public record CreateIssueTicketRequest(IssueCategory category, String title, String message) {
}
