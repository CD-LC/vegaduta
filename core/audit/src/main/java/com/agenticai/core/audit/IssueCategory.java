package com.agenticai.core.audit;

/**
 * #974 - "give a link for ticket creation and categories on which issue
 * falls under" - which bucket a tenant-submitted support ticket falls into,
 * chosen by the tenant admin at submission time on the Billing & usage page.
 * The five human-facing values above are chosen by a tenant admin at
 * ticket-submission time; the four below are set asynchronously by {@code
 * AiDiagnosticAnalysisService} (core/app) on system-detected rows it has
 * analyzed - an un-analyzed system row (analysis skipped, failed, or not yet
 * run) still has {@code category=null}, same as today.
 */
public enum IssueCategory {
    BILLING,
    TECHNICAL,
    ACCOUNT,
    FEATURE_REQUEST,
    OTHER,          // unchanged - tenant-self-report only, per existing javadoc

    TOOL_FAILURE,
    SAFETY,
    SYSTEM,
    WORKFLOW        // NEW - system-detected only
}
