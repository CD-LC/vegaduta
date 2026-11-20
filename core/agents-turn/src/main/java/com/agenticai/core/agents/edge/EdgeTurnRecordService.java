package com.agenticai.core.agents.edge;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Persistence facade for {@link EdgeTurn} rows, so {@link EdgeTurnTelemetryController} never
 * touches the repository or the entity directly. Bounded at write time (truncation, never
 * rejection) - see {@link EdgeTurn}'s javadoc.
 */
@Service
public class EdgeTurnRecordService {

    private final EdgeTurnRepository edgeTurnRepository;

    public EdgeTurnRecordService(EdgeTurnRepository edgeTurnRepository) {
        this.edgeTurnRepository = edgeTurnRepository;
    }

    public void record(
            UUID agentId,
            String sessionKey,
            Integer tier,
            String modelId,
            String outcome,
            String reason,
            Long latencyMs) {
        edgeTurnRepository.save(
                new EdgeTurn(
                        agentId,
                        truncate(sessionKey, 255),
                        tier == null ? null : tier.shortValue(),
                        truncate(modelId, 128),
                        truncate(outcome, 16),
                        truncate(reason, 512),
                        latencyMs));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
