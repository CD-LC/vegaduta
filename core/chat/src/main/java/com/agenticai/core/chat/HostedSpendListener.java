package com.agenticai.core.chat;

import java.math.BigDecimal;

/**
 * Told about every recorded PLATFORM_HOSTED call cost - the platform's own outlay - right after
 * {@link LlmCallLogService} persists its row. BYOK calls never reach a listener. Implementations
 * must be cheap and must not throw (a failure is logged and swallowed by the caller; the LLM call
 * already happened). The per-tenant monthly hosted spend cap (core/agents {@code SpendCapService})
 * is the consumer.
 */
public interface HostedSpendListener {

    /**
     * @param tenantSchema the tenant schema bound when the call was recorded (never null here)
     * @param costUsd the call's estimated cost, always positive here
     */
    void onHostedSpend(String tenantSchema, BigDecimal costUsd);
}
