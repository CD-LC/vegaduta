package com.agenticai.core.quality.dataset;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a dataset from the bundled research-task starter set ({@code
 * /eval/research-tasks-seed.json}): multi-hop, BrowseComp/GAIA-style questions whose answers are
 * checked by deterministic code graders. Sibling of {@link EvalDatasetService#seedGoldenDataset}:
 * same one-per-agent idempotency (409 on a repeat) and the same "the console shows what you got"
 * posture. Triggered only by an explicit request - nothing seeds on its own.
 */
@Service
public class ResearchSeedService {

    static final String RESOURCE = "/eval/research-tasks-seed.json";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final EvalDatasetService datasetService;

    public ResearchSeedService(EvalDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    /** Parsed seed file, exposed for tests so the resource's shape is pinned without a database. */
    public static JsonNode loadSeed() {
        try (InputStream in = ResearchSeedService.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Research seed resource missing: " + RESOURCE);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read " + RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /**
     * @throws EvalRunConflictException (409) when this agent already has the research dataset
     */
    @Transactional
    public EvalDataset seed(UUID agentId) {
        JsonNode seed = loadSeed();
        String name = seed.path("name").asText();
        List<EvalDataset> existing = datasetService.listDatasets(agentId);
        if (existing.stream().anyMatch(d -> name.equals(d.getName()))) {
            throw new EvalRunConflictException(
                    "This agent already has the research starter dataset ('" + name + "').");
        }
        EvalDataset dataset =
                datasetService.createDataset(
                        agentId,
                        name,
                        seed.path("description").asText(null),
                        seed.path("passThreshold").asDouble(0.7),
                        seed.path("toolExecutionEnabled").asBoolean(true));
        int position = 0;
        for (JsonNode task : seed.path("tasks")) {
            EvalDatasetCase created =
                    datasetService.addCase(
                            agentId,
                            dataset.getId(),
                            task.path("question").asText(),
                            task.path("answer").asText(null),
                            null,
                            position++);
            datasetService.setCaseGrader(
                    agentId, dataset.getId(), created.getId(), task.path("grader").toString());
        }
        return datasetService.requireDataset(agentId, dataset.getId());
    }
}
