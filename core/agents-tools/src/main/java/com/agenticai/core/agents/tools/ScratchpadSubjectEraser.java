package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.SubjectDataEraser;
import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import org.springframework.stereotype.Component;

/**
 * Plugs the agent scratchpad into data-subject erasure ({@code DELETE /api/memory}, contact erasure). A
 * separate adapter rather than {@code AgentScratchpadService} implementing the port: the scratchpad package
 * must not depend on {@code agents.memory} (package-cycle baseline).
 */
@Component
public class ScratchpadSubjectEraser implements SubjectDataEraser {

    private final AgentScratchpadService scratchpad;

    public ScratchpadSubjectEraser(AgentScratchpadService scratchpad) {
        this.scratchpad = scratchpad;
    }

    @Override
    public String store() {
        return "scratchpad";
    }

    @Override
    public int eraseSubject(String subjectKey) {
        return scratchpad.eraseSubject(subjectKey);
    }
}
