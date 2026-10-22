package com.agenticai.core.quality.eval;

import com.agenticai.core.json.JsonMappers;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Last-known-good token totals per {@link EvalCase#id()}, used by {@code EvalRunner} to flag a
 * token/cost regression even though the LLM-as-judge grader has no concept of cost.
 *
 * <p><b>Deliberately the simplest viable baseline, not a real datastore.</b> This module has no DB
 * and no write-back to a live service by design (see this module's eval-harness.md for the
 * standalone-harness rationale) - a single checked-in JSON file, {@code
 * src/main/resources/eval-token-baseline.json}, maps case id to the last-known-good combined token
 * count (generation + judge). It is loaded read-only from the classpath exactly like {@code
 * golden-dataset.json}. <b>Known limitation:</b> there is no automatic "update the baseline on a
 * clean run" step - deliberately, since a CI job silently rewriting its own pass/fail bar would
 * make a slow creeping regression invisible. Updating a case's baseline (after a deliberate
 * prompt/model change that legitimately shifts its cost) is a manual edit to this file, reviewed in
 * the same PR as the change that caused the shift. A case id absent from this file has no baseline
 * yet: {@code EvalRunner#checkTokenBudget} treats that as "nothing to compare against" (not a
 * failure) and callers are expected to add an entry once the first real run establishes one.
 */
final class TokenBaseline {

    private static final Logger log = LoggerFactory.getLogger(TokenBaseline.class);

    private static final String RESOURCE = "/eval-token-baseline.json";

    private final Map<String, Integer> baselineByCaseId;

    private TokenBaseline(Map<String, Integer> baselineByCaseId) {
        this.baselineByCaseId = baselineByCaseId;
    }

    /** Baseline total tokens for {@code caseId}, or {@code null} if none is on file yet. */
    Integer get(String caseId) {
        return baselineByCaseId.get(caseId);
    }

    /**
     * Loads the baseline file from the classpath. Missing file (not yet created, or a fresh
     * checkout before the first baseline was ever committed) is not an error - it just means every
     * case runs with no token-budget check until baselines are added, mirroring how a brand-new
     * golden case has no history to regress against either.
     */
    static TokenBaseline load() {
        ObjectMapper mapper = JsonMappers.plain();
        try (InputStream in = TokenBaseline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.info(
                        "[eval] no {} on classpath - token-budget check will run with zero baselines",
                        RESOURCE);
                return new TokenBaseline(Map.of());
            }
            Map<String, Integer> loaded =
                    mapper.readValue(in, new TypeReference<TreeMap<String, Integer>>() {});
            return new TokenBaseline(loaded);
        } catch (IOException e) {
            log.warn(
                    "[eval] could not read {} - token-budget check will run with zero baselines: {}",
                    RESOURCE,
                    e.getMessage());
            return new TokenBaseline(Map.of());
        }
    }
}
