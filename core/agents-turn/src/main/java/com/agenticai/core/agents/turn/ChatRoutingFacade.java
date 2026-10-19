package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.crew.SpecialistHandoffService;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.version.AgentVersionResolutionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Which agent / version / model actually serves a turn: A/B experiments, version pinning, crew
 * handoff, the hot-path trimmer and the verifier-escalation router. All optional, all lazy
 * providers. Holds no logic.
 */
@Component
record ChatRoutingFacade(
        ObjectProvider<AgentExperimentService> agentExperimentService,
        ObjectProvider<AgentVersionResolutionService> agentVersionResolutionService,
        ObjectProvider<SpecialistHandoffService> specialistHandoffService,
        ObjectProvider<ChatHotpath> chatHotpath,
        ObjectProvider<VerifierEscalationRouter> verifierEscalationRouter) {}
