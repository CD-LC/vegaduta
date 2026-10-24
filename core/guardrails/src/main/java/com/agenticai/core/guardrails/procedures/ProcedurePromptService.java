package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the procedures prompt block for one agent's chat turn. Kept as a
 * service (not a static call into {@link ProcedureService} from {@code
 * AgentToolProvider}) so the read-failure posture lives in exactly one
 * place: a broken procedures read degrades to an empty block, never to a
 * failed turn - same "enhancement, not a hard dependency" rule {@code
 * AgentToolProvider#skillsPromptBlock} documents.
 */
@Service
public class ProcedurePromptService {

    private static final Logger log = LoggerFactory.getLogger(ProcedurePromptService.class);

    private final ProcedureService procedureService;

    /** Where the block's wording comes from; the shipped wording unless the application wires the registry. */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    public ProcedurePromptService(ProcedureService procedureService) {
        this.procedureService = procedureService;
    }

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    /** The deterministic "## Operating procedures" block for {@code agentId}, or "" when none apply. Never throws. */
    public String promptBlockForAgent(UUID agentId) {
        try {
            return ProcedurePromptBlock.render(
                procedureService.activeRulesForAgent(agentId), prompts, PromptContext.forAgent(agentId));
        } catch (RuntimeException e) {
            log.warn("Could not build the procedures prompt block for agent {}: {}", agentId, e.getMessage());
            return "";
        }
    }
}
