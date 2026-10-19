package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.api.RlTurnRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The optional, cross-module observers of a turn: platform issues, TTFT metrics, workflow events,
 * the RL subsystem and agent lifecycle hooks. Every member is an {@link ObjectProvider} resolved
 * lazily by {@link AgentChatService} after construction (these beans can themselves depend on the
 * chat service, so resolving them in a constructor would risk a cycle). Holds no logic.
 */
@Component
record ChatObservabilityFacade(
        ObjectProvider<PlatformIssueService> platformIssueService,
        ObjectProvider<MeterRegistry> meterRegistry,
        ObjectProvider<WorkflowEventPublisher> workflowEventPublisher,
        ObjectProvider<RlPolicyPort> rlPolicyPort,
        ObjectProvider<RlTurnRecorder> rlTurnRecorder,
        ObjectProvider<AgentHookRuntime> agentHooks) {}
