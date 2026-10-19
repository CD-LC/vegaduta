package com.agenticai.core.guardrails.taint;

import com.agenticai.core.audit.AuditOutcome;

/** Where {@link TaintGate} reports decisions that deserve an audit trail. Must not throw. */
@FunctionalInterface
public interface TaintAuditSink {

    /**
     * @param action audit action type, e.g. {@code tool.taint.approval_required}
     * @param detail log-safe text: tool name and taint sources only, never arguments or output
     */
    void emit(String action, AuditOutcome outcome, String toolName, String detail);
}
