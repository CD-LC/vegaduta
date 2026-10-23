package com.agenticai.core.omnichannel.whatsapp.policy;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaching an agent to a WhatsApp number requires a declared task, and an agent that looks like an
 * open-ended assistant triggers a warning the tenant can override. The override is stored on the
 * binding and written to the audit log. See docs/channels/whatsapp-policy.md.
 */
@Service
public class WhatsAppBindingPolicy {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppBindingPolicy.class);

    public enum Status {
        BOUND,
        TASK_REQUIRED,
        OPEN_ENDED_WARNING,
        AGENT_NOT_FOUND
    }

    public record Outcome(Status status, List<String> reasons) {}

    private final AgentService agents;
    private final ChannelSessionService sessions;
    private final AuditLogService audit;

    public WhatsAppBindingPolicy(
            AgentService agents, ChannelSessionService sessions, AuditLogService audit) {
        this.agents = agents;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** {@code PUT /api/channels/whatsapp/default-agent}. */
    @Transactional
    public Outcome bindDefault(UUID agentId, String task, boolean acknowledgeOpenEnded) {
        return bind(
                agentId,
                task,
                acknowledgeOpenEnded,
                () -> sessions.setDefaultAgent(ChannelType.WHATSAPP, agentId));
    }

    private Outcome bind(UUID agentId, String taskRaw, boolean acknowledge, Runnable save) {
        Optional<WhatsAppTask> task = WhatsAppTask.parse(taskRaw);
        if (task.isEmpty()) {
            return new Outcome(Status.TASK_REQUIRED, List.of());
        }
        AgentResponse agent;
        try {
            agent = agents.getGrantedAgent(agentId);
        } catch (AgentNotFoundException e) {
            return new Outcome(Status.AGENT_NOT_FOUND, List.of());
        }
        OpenEndedAssistantCheck.Assessment assessment =
                OpenEndedAssistantCheck.assess(agent.systemPrompt(), agent.allowedToolNames());
        if (assessment.openEnded() && !acknowledge) {
            return new Outcome(Status.OPEN_ENDED_WARNING, assessment.reasons());
        }
        save.run();
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        boolean override = assessment.openEnded();
        sessions.routingFor(ChannelType.WHATSAPP)
                .ifPresent(row -> row.declareTask(task.get().name(), actor, override));
        audit.record(
                actor,
                override ? "WHATSAPP_OPEN_ENDED_OVERRIDE" : "WHATSAPP_AGENT_BOUND",
                "agent",
                agentId.toString(),
                AuditOutcome.SUCCESS,
                "task="
                        + task.get()
                        + (override
                                ? "; override of warning: " + String.join(" ", assessment.reasons())
                                : ""));
        if (override) {
            log.warn(
                    "WhatsApp open-ended agent override: agent={} task={} by={}",
                    LogSafe.clean(agentId),
                    LogSafe.clean(task.get()),
                    LogSafe.clean(actor));
        }
        return new Outcome(Status.BOUND, assessment.reasons());
    }
}
