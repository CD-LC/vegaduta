package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.loadtest.LoadTestTenantAllowlist;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.GuardrailViolationLogger;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantStatusGuard;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.FeatureFlagService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The rules a turn is admitted under: tenant limits and status, feature flags and experiments, the
 * load-test seam, server-side cancel, moderation and content guardrails, and cross-user resource
 * sharing. Constructor-injected as one unit; holds no logic.
 */
@Component
record ChatPolicyFacade(
        TenantLimits tenantLimits,
        TenantStatusGuard tenantStatusGuard,
        FeatureFlagService featureFlagService,
        LoadTestTenantAllowlist loadTestTenantAllowlist,
        ChatTurnCancelService chatTurnCancelService,
        AgentGuardrailService agentGuardrailService,
        ResourceAccessService resourceAccessService,
        ObjectProvider<ModerationService> moderationService,
        ObjectProvider<ContentGuardrailEngine> contentGuardrailEngine,
        ObjectProvider<GuardrailViolationLogger> guardrailViolationLogger) {}
