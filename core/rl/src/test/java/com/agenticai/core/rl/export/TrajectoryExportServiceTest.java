package com.agenticai.core.rl.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.domain.Episode;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TrajectoryStep;
import com.agenticai.core.rl.domain.TurnFeedback;
import com.agenticai.core.rl.repo.EpisodeRepository;
import com.agenticai.core.rl.repo.TrajectoryStepRepository;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the NDJSON trace export: every line parses standalone, an episode line inlines its steps
 * in order with the reward attached, the limit caps emitted episodes, and the preference JSONL
 * mirrors {@link RlExportService}'s DPO/KTO mapping one sample per line.
 */
@ExtendWith(MockitoExtension.class)
class TrajectoryExportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private EpisodeRepository episodeRepository;
    @Mock private TrajectoryStepRepository stepRepository;
    @Mock private TurnFeedbackRepository feedbackRepository;

    private TrajectoryExportService service;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new TrajectoryExportService(
                        episodeRepository,
                        stepRepository,
                        new RlExportService(feedbackRepository, episodeRepository, stepRepository));
    }

    private Episode episode(String sessionId, String turnRef, String reward) {
        Episode e = new Episode(agentId, sessionId, turnRef, Instant.now(), "{\"k\":1}", null);
        // Ids are generated on persist; an unpersisted entity has a null id,
        // which would make every step stub collide on findByEpisodeId(null).
        org.springframework.test.util.ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setEndedAt(Instant.now());
        e.setTerminal(true);
        if (reward != null) {
            e.setReward(new BigDecimal(reward));
            e.setRewardBreakdownJson("{\"HUMAN\":0.9}");
        }
        return e;
    }

    private void stubSteps(Episode e) {
        List<TrajectoryStep> steps = new ArrayList<>();
        steps.add(
                new TrajectoryStep(
                        e.getId(),
                        0,
                        "user",
                        "message",
                        "the question",
                        null,
                        null,
                        null,
                        Instant.now()));
        steps.add(
                new TrajectoryStep(
                        e.getId(),
                        1,
                        "assistant",
                        "tool_call",
                        null,
                        "search",
                        12L,
                        34L,
                        Instant.now()));
        steps.add(
                new TrajectoryStep(
                        e.getId(),
                        2,
                        "assistant",
                        "answer",
                        "the answer",
                        null,
                        56L,
                        78L,
                        Instant.now()));
        lenient()
                .when(stepRepository.findByEpisodeIdOrderByStepIndexAsc(e.getId()))
                .thenReturn(steps);
    }

    private static List<JsonNode> lines(String body) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    @Test
    void oneLinePerEpisodeWithStepsInlined() throws Exception {
        Episode e = episode("s1", "t1", "0.9000");
        when(episodeRepository.findByAgentIdOrderByStartedAtDesc(agentId)).thenReturn(List.of(e));
        stubSteps(e);

        List<JsonNode> parsed = lines(service.trajectoriesJsonl(agentId, 100));

        assertThat(parsed).hasSize(1);
        JsonNode row = parsed.get(0);
        assertThat(row.get("type").asText()).isEqualTo("episode");
        assertThat(row.get("schema").asText()).isEqualTo(TrajectoryExportService.TRAJECTORY_SCHEMA);
        assertThat(row.get("sessionId").asText()).isEqualTo("s1");
        assertThat(row.get("reward").asDouble()).isEqualTo(0.9);
        // JSON columns embedded parsed, not double-encoded.
        assertThat(row.get("context").get("k").asInt()).isEqualTo(1);
        assertThat(row.get("rewardBreakdown").get("HUMAN").asDouble()).isEqualTo(0.9);
        assertThat(row.get("steps")).hasSize(3);
        assertThat(row.get("steps").get(1).get("toolName").asText()).isEqualTo("search");
        assertThat(row.get("steps").get(1).get("tokens").asLong()).isEqualTo(12L);
        assertThat(row.get("steps").get(2).get("content").asText()).isEqualTo("the answer");
    }

    @Test
    void limitCapsEmittedEpisodes() throws Exception {
        Episode e1 = episode("s1", "t1", null);
        Episode e2 = episode("s2", "t2", null);
        when(episodeRepository.findByAgentIdOrderByStartedAtDesc(agentId))
                .thenReturn(List.of(e1, e2));
        stubSteps(e1);
        stubSteps(e2);

        List<JsonNode> parsed = lines(service.trajectoriesJsonl(agentId, 1));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).get("sessionId").asText()).isEqualTo("s1");
    }

    @Test
    void preferencesJsonlIsOneSamplePerLine() throws Exception {
        when(feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId))
                .thenReturn(
                        List.of(
                                new TurnFeedback(
                                        agentId,
                                        "s-t1",
                                        "t1",
                                        FeedbackSignal.EDIT,
                                        null,
                                        null,
                                        "corrected",
                                        "u",
                                        Instant.now()),
                                new TurnFeedback(
                                        agentId,
                                        "s-t2",
                                        "t2",
                                        FeedbackSignal.THUMBS_UP,
                                        null,
                                        null,
                                        null,
                                        "u",
                                        Instant.now())));
        Episode e1 = episode("s-t1", "t1", null);
        Episode e2 = episode("s-t2", "t2", null);
        // Feedback only becomes a sample when it came from whoever had the turn.
        e1.setSubject("u");
        e2.setSubject("u");
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        eq(agentId), eq("s-t1"), eq("t1")))
                .thenReturn(Optional.of(e1));
        when(episodeRepository.findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc(
                        eq(agentId), eq("s-t2"), eq("t2")))
                .thenReturn(Optional.of(e2));
        for (Episode e : List.of(e1, e2)) {
            List<TrajectoryStep> steps =
                    List.of(
                            new TrajectoryStep(
                                    e.getId(),
                                    0,
                                    "user",
                                    "message",
                                    "p-" + e.getTurnRef(),
                                    null,
                                    null,
                                    null,
                                    Instant.now()),
                            new TrajectoryStep(
                                    e.getId(),
                                    1,
                                    "assistant",
                                    "answer",
                                    "a-" + e.getTurnRef(),
                                    null,
                                    null,
                                    null,
                                    Instant.now()));
            lenient()
                    .when(stepRepository.findByEpisodeIdOrderByStepIndexAsc(e.getId()))
                    .thenReturn(steps);
        }

        List<JsonNode> parsed = lines(service.preferencesJsonl(agentId, 500));

        assertThat(parsed).hasSize(2);
        JsonNode dpo = parsed.get(0);
        assertThat(dpo.get("type").asText()).isEqualTo("dpo");
        assertThat(dpo.get("prompt").asText()).isEqualTo("p-t1");
        assertThat(dpo.get("chosen").asText()).isEqualTo("corrected");
        assertThat(dpo.get("rejected").asText()).isEqualTo("a-t1");
        JsonNode kto = parsed.get(1);
        assertThat(kto.get("type").asText()).isEqualTo("kto");
        assertThat(kto.get("label").asBoolean()).isTrue();
        assertThat(kto.get("signal").asText()).isEqualTo("THUMBS_UP");
    }
}
