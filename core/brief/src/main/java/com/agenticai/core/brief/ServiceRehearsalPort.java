package com.agenticai.core.brief;

import com.agenticai.core.quality.simulation.SimulationPersonaService;
import com.agenticai.core.quality.simulation.SimulationPersonaService.ResolvedPersona;
import com.agenticai.core.quality.simulation.SimulationRun;
import com.agenticai.core.quality.simulation.SimulationRunService;
import com.agenticai.core.quality.simulation.SimulationSession;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link RehearsalPort} over the real simulation services (the same ones {@code SimulationController} calls). */
@Service
public class ServiceRehearsalPort implements RehearsalPort {

    private final SimulationPersonaService personaService;
    private final SimulationRunService runService;

    public ServiceRehearsalPort(SimulationPersonaService personaService, SimulationRunService runService) {
        this.personaService = personaService;
        this.runService = runService;
    }

    @Override
    public void ensurePersona(String key, String name, String description, String objective) {
        for (ResolvedPersona existing : personaService.listAll()) {
            if (existing.key().equals(key)) {
                return;
            }
        }
        try {
            personaService.createCustom(key, name, description, objective);
        } catch (IllegalArgumentException e) {
            // Two rehearsals of the same brief racing to create the same persona: the loser sees "already exists".
            boolean exists = personaService.listAll().stream().anyMatch(p -> p.key().equals(key));
            if (!exists) {
                throw e;
            }
        }
    }

    @Override
    public String createSession(String agentId, List<String> personaKeys, int maxTurns, String createdBy) {
        SimulationSession session = runService.createSession(UUID.fromString(agentId), personaKeys, maxTurns, createdBy);
        return session.getId().toString();
    }

    @Override
    public List<SessionInfo> sessions(String agentId) {
        return runService.listSessions(UUID.fromString(agentId)).stream()
            .map(s -> new SessionInfo(s.getId().toString(), s.getCreatedBy(), s.getStatus().name(), s.getCreatedAt())).toList();
    }

    @Override
    public List<RunInfo> runs(String agentId, String sessionId) {
        List<SimulationRun> runs = runService.listRuns(UUID.fromString(agentId), UUID.fromString(sessionId));
        return runs.stream().map(r -> new RunInfo(r.getId().toString(), r.getPersonaName(), r.getStatus().name(),
            r.getVerdict() == null ? null : r.getVerdict().name(), r.getVerdictReason(), r.getTurnCount())).toList();
    }
}
