package com.agenticai.core.agents.tools;

import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.procedures.ProcedurePromptService;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Assembles the REAL tool-resolution stack ({@link AgentToolProvider}, {@link ToolResolver}, {@link
 * ToolRelevanceSelector} with production defaults, and {@link AgentToolGuard}, which builds the
 * production wrapper chain around every tool: guard, logging, tenant propagation) over a
 * caller-supplied set of RAW tools. Lives in this package only because those classes are
 * package-private; the end-to-end harness reaches it through this class.
 *
 * <p>Only one tool source is registered (the raw tools the test supplies). The production sources
 * (MCP, knowledge, A2A, ...) each wrap their tools through the very same {@code guardAndLog} call,
 * so the wrapper chain a test tool gets here is the chain a production tool gets.
 */
public final class ToolHarnessBridge {

    private ToolHarnessBridge() {}

    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                message -> {});
    }

    /**
     * As above; {@code resolutionMessages} receives the message every tool resolution was asked to
     * select tools for (what relevance selection and the RIGOROUS verifier judge against).
     */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                resolutionMessages,
                new com.agenticai.core.guardrails.ToolStepLimitProperties(),
                new com.agenticai.core.guardrails.ToolOutputProperties());
    }

    /**
     * As above, with the operator's {@code app.guardrails.step-limit.*} and {@code app.tools.output.*}
     * settings, wired into the guard the way {@code ToolResolverConfiguration} does.
     */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages,
            com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits,
            com.agenticai.core.guardrails.ToolOutputProperties outputLimits) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                resolutionMessages,
                stepLimits,
                outputLimits,
                new com.agenticai.core.agents.turn.ToolLedgerProperties());
    }

    /** As above, with the operator's {@code app.chat.tool-ledger.*} settings. */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages,
            com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits,
            com.agenticai.core.guardrails.ToolOutputProperties outputLimits,
            com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                resolutionMessages,
                stepLimits,
                outputLimits,
                toolLedger,
                null);
    }

    /**
     * As above, with the resolver of the per-tenant / per-agent runtime limits ({@code null} = every agent
     * runs under the guard's constructor settings).
     */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages,
            com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits,
            com.agenticai.core.guardrails.ToolOutputProperties outputLimits,
            com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger,
            com.agenticai.core.agents.runtime.AgentRuntime runtime) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                resolutionMessages,
                stepLimits,
                outputLimits,
                toolLedger,
                runtime,
                () -> null);
    }

    /**
     * As above, with the settings catalog the guard reads a tenant's {@code chat.toolLedger.*} from (asked lazily: the
     * harness builds the catalog after the tools; null from the supplier = no catalog).
     */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages,
            com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits,
            com.agenticai.core.guardrails.ToolOutputProperties outputLimits,
            com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger,
            com.agenticai.core.agents.runtime.AgentRuntime runtime,
            java.util.function.Supplier<com.agenticai.core.settings.SettingsResolver> settings) {
        return provider(
                agentService,
                guardrails,
                approvalPolicy,
                gate,
                toolCallLogs,
                maxToolCallsPerTurn,
                rawTools,
                resolutionMessages,
                stepLimits,
                outputLimits,
                toolLedger,
                runtime,
                settings,
                null);
    }

    /**
     * As above, over a caller-built {@link AgentToolGovernance} (a real classification registry, trifecta service,
     * guardrail tuning and audit log, wired the way the application does); {@code null} = the bare governance every
     * other harness test runs over (no registry: no classification, so no read-only verdict).
     */
    public static AgentToolProvider provider(
            AgentService agentService,
            GuardrailService guardrails,
            ApprovalPolicyService approvalPolicy,
            HumanApprovalGateService gate,
            ToolCallLogRepository toolCallLogs,
            int maxToolCallsPerTurn,
            List<ToolCallback> rawTools,
            java.util.function.Consumer<String> resolutionMessages,
            com.agenticai.core.guardrails.ToolStepLimitProperties stepLimits,
            com.agenticai.core.guardrails.ToolOutputProperties outputLimits,
            com.agenticai.core.agents.turn.ToolLedgerProperties toolLedger,
            com.agenticai.core.agents.runtime.AgentRuntime runtime,
            java.util.function.Supplier<com.agenticai.core.settings.SettingsResolver> settings,
            AgentToolGovernance suppliedGovernance) {
        AgentToolGovernance governance =
                suppliedGovernance != null
                        ? suppliedGovernance
                        : new AgentToolGovernance(empty(), empty(), empty(), true, "");
        AgentToolGuard guard =
                new AgentToolGuard(
                        toolCallLogs,
                        agentService,
                        guardrails,
                        approvalPolicy,
                        gate,
                        maxToolCallsPerTurn,
                        empty(),
                        empty(),
                        empty(),
                        empty(),
                        mock(ToolCircuitBreakerService.class),
                        governance);
        StaticListableBeanFactory limits = new StaticListableBeanFactory();
        limits.addBean("stepLimits", stepLimits);
        limits.addBean("outputLimits", outputLimits);
        limits.addBean("toolLedger", toolLedger);
        guard.setStepLimitProperties(
                limits.getBeanProvider(com.agenticai.core.guardrails.ToolStepLimitProperties.class));
        guard.setOutputProperties(
                limits.getBeanProvider(com.agenticai.core.guardrails.ToolOutputProperties.class));
        guard.setToolLedger(
                limits.getBeanProvider(com.agenticai.core.agents.turn.ToolLedgerProperties.class));
        guard.setSettings(
                new ObjectProvider<com.agenticai.core.settings.SettingsResolver>() {
                    @Override
                    public com.agenticai.core.settings.SettingsResolver getObject(Object... args) {
                        return settings.get();
                    }

                    @Override
                    public com.agenticai.core.settings.SettingsResolver getObject() {
                        return settings.get();
                    }

                    @Override
                    public com.agenticai.core.settings.SettingsResolver getIfAvailable() {
                        return settings.get();
                    }

                    @Override
                    public com.agenticai.core.settings.SettingsResolver getIfUnique() {
                        return settings.get();
                    }
                });
        if (runtime != null) {
            limits.addBean("runtime", runtime);
            guard.setRuntimeResolver(
                    limits.getBeanProvider(
                            com.agenticai.core.agents.runtime.AgentRuntime.class));
        }
        ToolSource source =
                ToolSource.of(
                        "harness-tools",
                        ToolSource.Kind.BUILT_IN,
                        ctx -> {
                            resolutionMessages.accept(ctx.userMessage());
                            return rawTools.stream()
                                        .map(
                                                raw ->
                                                        guard.guardAndLog(
                                                                raw,
                                                                ctx.agentId(),
                                                                ctx.sessionId(),
                                                                ctx.stepCounter(),
                                                                ctx.userMessage(),
                                                                ctx.chatClient(),
                                                                ctx.rigorous()))
                                        .toList();
                        });
        ToolRelevanceSelector selector =
                new ToolRelevanceSelector(
                        true,
                        12,
                        3000,
                        4,
                        ToolHarnessBridge.<com.agenticai.core.knowledge.SidecarClient>empty(),
                        true,
                        true);
        ToolResolver resolver =
                new ToolResolver(
                        List.of(source),
                        agentService,
                        selector,
                        guard,
                        mock(AgentIntegrationToolResolver.class),
                        ToolHarnessBridge.<PlatformIssueService>empty(),
                        new ToolSearchDeferral(),
                        40);
        return new AgentToolProvider(
                agentService,
                resolver,
                mock(TurnRunBudgets.class),
                mock(AgentMcpToolResolver.class),
                mock(AgentCrewBrowserToolResolver.class),
                mock(AgentToolDiscovery.class),
                mock(SkillService.class),
                ToolHarnessBridge.<ProcedurePromptService>empty());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        return (ObjectProvider<T>) new StaticListableBeanFactory().getBeanProvider(Object.class);
    }
}
