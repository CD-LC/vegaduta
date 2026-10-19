package com.agenticai.core.brief;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@link ProofData} over settable lists: what the platform measured is exactly what a test puts here. */
class FakeProofData implements ProofData {

    volatile List<EvalRunPoint> runs = new ArrayList<>();
    volatile Double floor;
    volatile RuntimeException floorFailure;
    volatile int realRuns;
    volatile Thumbs thumbs = new Thumbs(0, 0);
    volatile List<ExperimentRow> experiments = new ArrayList<>();
    volatile String startedRunId = "run-started";
    final List<Instant> strikeTimes = new CopyOnWriteArrayList<>();
    final List<String> strikeDetails = new CopyOnWriteArrayList<>();
    final List<String> startedFor = new CopyOnWriteArrayList<>();
    volatile Instant lastSince;

    @Override
    public List<EvalRunPoint> evalRuns(String datasetId) {
        return runs;
    }

    @Override
    public Double noiseFloor(String agentId, String datasetId) {
        if (floorFailure != null) {
            throw floorFailure;
        }
        return floor;
    }

    @Override
    public int realRuns(List<String> agentIds, Instant since) {
        return realRuns;
    }

    @Override
    public Thumbs thumbs(List<String> agentIds, Instant since) {
        lastSince = since;
        return thumbs;
    }

    @Override
    public List<ExperimentRow> experiments(String agentId) {
        return experiments;
    }

    @Override
    public int strikes(UUID briefId, Instant since) {
        return (int) strikeTimes.stream().filter(t -> !t.isBefore(since)).count();
    }

    @Override
    public void recordStrike(UUID briefId, String agentId, String detail) {
        strikeTimes.add(Instant.now());
        strikeDetails.add(detail);
    }

    @Override
    public String startEvalRun(String agentId, String datasetId) {
        startedFor.add(agentId + "@" + datasetId);
        return startedRunId;
    }

    static EvalRunPoint run(String id, Instant at, double score, String verdict) {
        return new EvalRunPoint(id, at, score, 0.7, "PASSED".equals(verdict), verdict);
    }
}
