package com.agenticai.core.rl.export;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.rl.web.dto.RlPreferenceExport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NDJSON exports of the trace/training data - the "JSONL export of evals and
 * traces" first step of the self-improvement roadmap (offline optimization,
 * never online RL).
 *
 * <p>{@link #trajectoriesJsonl} emits one line per {@link Episode} with its
 * {@link TrajectoryStep}s inlined in {@code stepIndex} order - one line is one
 * full agent turn (prompt, tool calls, tool results, answer), the unit an
 * offline optimizer or the Python {@code rl-trainer} consumes.
 *
 * <p>{@link #preferencesJsonl} renders {@link RlExportService}'s DPO pairs /
 * KTO samples one-per-line so the same file can be piped into a preference
 * trainer without an extra reshape.
 *
 * <p>JSON columns ({@code context_json}, {@code reward_breakdown_json}) are
 * embedded parsed, never double-encoded; an unreadable blob is preserved
 * verbatim under a {@code *Raw} key rather than dropped (fidelity is the
 * export's whole job). Same posture as {@code EvalJsonlExport}.
 */
@Service
public class TrajectoryExportService {

    public static final String TRAJECTORY_SCHEMA = "vegaduta.rl-trajectory-jsonl/v1";
    public static final String PREFERENCE_SCHEMA = "vegaduta.rl-preference-jsonl/v1";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final EpisodeRepository episodeRepository;
    private final TrajectoryStepRepository stepRepository;
    private final RlExportService preferenceExport;

    public TrajectoryExportService(
        EpisodeRepository episodeRepository,
        TrajectoryStepRepository stepRepository,
        RlExportService preferenceExport
    ) {
        this.episodeRepository = episodeRepository;
        this.stepRepository = stepRepository;
        this.preferenceExport = preferenceExport;
    }

    /**
     * One line per episode, newest first, capped at {@code limit}. Each line
     * is self-contained (header fields + steps): NDJSON consumers read lines
     * independently, so there is no separate header line to strip.
     */
    @Transactional(readOnly = true)
    public String trajectoriesJsonl(UUID agentId, int limit) {
        StringBuilder out = new StringBuilder(64 * 1024);
        int emitted = 0;
        for (Episode e : episodeRepository.findByAgentIdOrderByStartedAtDesc(agentId)) {
            if (emitted >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "episode");
            row.put("schema", TRAJECTORY_SCHEMA);
            row.put("id", str(e.getId()));
            row.put("agentId", str(e.getAgentId()));
            row.put("sessionId", e.getSessionId());
            row.put("turnRef", e.getTurnRef());
            row.put("startedAt", str(e.getStartedAt()));
            row.put("endedAt", str(e.getEndedAt()));
            row.put("terminal", e.isTerminal());
            row.put("reward", e.getReward());
            putJson(row, "rewardBreakdown", e.getRewardBreakdownJson());
            row.put("policyArmId", str(e.getPolicyArmId()));
            putJson(row, "context", e.getContextJson());

            List<Map<String, Object>> steps = new ArrayList<>();
            for (TrajectoryStep s : stepRepository.findByEpisodeIdOrderByStepIndexAsc(e.getId())) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("index", s.getStepIndex());
                step.put("role", s.getRole());
                step.put("actionType", s.getActionType());
                step.put("content", s.getContent());
                step.put("toolName", s.getToolName());
                step.put("tokens", s.getTokens());
                step.put("latencyMs", s.getLatencyMs());
                step.put("at", str(s.getCreatedAt()));
                steps.add(step);
            }
            row.put("steps", steps);
            line(out, row);
            emitted++;
        }
        return out.toString();
    }

    /**
     * One line per preference training sample: {@code {"type":"dpo",
     * "prompt","chosen","rejected"}} or {@code {"type":"kto","prompt",
     * "completion","label","signal"}}.
     */
    @Transactional(readOnly = true)
    public String preferencesJsonl(UUID agentId, int limit) {
        RlPreferenceExport export = preferenceExport.export(agentId, limit);
        StringBuilder out = new StringBuilder(32 * 1024);
        for (RlPreferenceExport.DpoPair pair : export.dpo()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "dpo");
            row.put("schema", PREFERENCE_SCHEMA);
            row.put("prompt", pair.prompt());
            row.put("chosen", pair.chosen());
            row.put("rejected", pair.rejected());
            line(out, row);
        }
        for (RlPreferenceExport.KtoSample sample : export.kto()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "kto");
            row.put("schema", PREFERENCE_SCHEMA);
            row.put("prompt", sample.prompt());
            row.put("completion", sample.completion());
            row.put("label", sample.desirable());
            row.put("signal", sample.source());
            line(out, row);
        }
        return out.toString();
    }

    private static void putJson(Map<String, Object> row, String key, String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(storedJson);
            row.put(key, node.isNull() ? null : node);
        } catch (Exception e) {
            row.put(key + "Raw", storedJson);
        }
    }

    private static void line(StringBuilder out, Map<String, Object> doc) {
        try {
            out.append(MAPPER.writeValueAsString(doc)).append('\n');
        } catch (Exception e) {
            throw new IllegalStateException("JSONL export serialization failed", e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
