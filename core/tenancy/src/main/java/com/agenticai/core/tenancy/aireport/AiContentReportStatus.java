package com.agenticai.core.tenancy.aireport;

/** Review lifecycle of an {@link AiContentReport}. New reports are always {@link #OPEN}. */
public enum AiContentReportStatus {
    OPEN,
    REVIEWED,
    ACTIONED,
    DISMISSED
}
