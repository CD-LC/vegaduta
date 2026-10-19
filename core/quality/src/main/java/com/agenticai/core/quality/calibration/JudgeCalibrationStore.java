package com.agenticai.core.quality.calibration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where judge-calibration sets, their items and the calibration result history live. Deliberately NOT a SQL
 * repository: this repo carries no Flyway migration for the feature (the schema is maintained in another
 * codebase), so the production implementation is {@link RedisJudgeCalibrationStore}. Every method is scoped to
 * the CURRENT tenant (from {@code TenantContext}); an id from another tenant simply is not found.
 */
public interface JudgeCalibrationStore {

    JudgeCalibrationSet saveSet(JudgeCalibrationSet set);

    Optional<JudgeCalibrationSet> findSet(UUID setId);

    /** Newest first. */
    List<JudgeCalibrationSet> listSets(UUID agentId);

    /** Deletes the set together with its items and its results. */
    void deleteSet(UUID setId);

    List<JudgeCalibrationItem> saveItems(List<JudgeCalibrationItem> items);

    /** By position, then creation time. */
    List<JudgeCalibrationItem> listItems(UUID setId);

    long countItems(UUID setId);

    Optional<JudgeCalibrationItem> findItem(UUID setId, UUID itemId);

    void deleteItem(UUID setId, UUID itemId);

    JudgeCalibrationResult saveResult(JudgeCalibrationResult result);

    /** Newest first, at most {@code limit}. */
    List<JudgeCalibrationResult> resultsForAgent(UUID agentId, int limit);

    /** Newest first, at most {@code limit}. */
    List<JudgeCalibrationResult> resultsForSet(UUID setId, int limit);

    /** The most recent calibration of this exact judge model id, across every set of the tenant. */
    Optional<JudgeCalibrationResult> latestForJudgeModel(String judgeModel);
}
