package com.agenticai.core.brief;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** {@link BriefApplyLedger} over plain collections, using the real step entity so status transitions are the real ones. */
class InMemoryLedger implements BriefApplyLedger {

    final List<BriefApplyStepEntity> rows = new CopyOnWriteArrayList<>();
    final Map<String, Map<String, Binding>> bindings = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void startJob(UUID briefId, int version, UUID jobId) {
        rows.add(new BriefApplyStepEntity(briefId, version, jobId, 0, JOB_KIND, null));
    }

    @Override
    public void finishJob(UUID briefId, UUID jobId, boolean succeeded, String message) {
        row(briefId, jobId).ifPresent(r -> {
            if (succeeded) {
                r.markDone(null, message);
            } else {
                r.markFailed(message);
            }
        });
    }

    @Override
    public UUID begin(UUID briefId, int version, UUID jobId, int seq, String kind, String ref) {
        BriefApplyStepEntity e = new BriefApplyStepEntity(briefId, version, jobId, seq, kind, ref);
        rows.add(e);
        return e.getId();
    }

    private void with(UUID stepId, Consumer<BriefApplyStepEntity> c) {
        rows.stream().filter(r -> r.getId().equals(stepId)).findFirst().ifPresent(c);
    }

    @Override
    public void created(UUID stepId, String entityId) {
        with(stepId, s -> s.recordEntity(entityId));
    }

    @Override
    public void done(UUID stepId, String entityId, String note) {
        with(stepId, s -> s.markDone(entityId != null ? entityId : s.getEntityId(), note));
    }

    @Override
    public void failed(UUID stepId, String error) {
        with(stepId, s -> s.markFailed(error));
    }

    @Override
    public void compensated(UUID stepId) {
        with(stepId, BriefApplyStepEntity::markCompensated);
    }

    @Override
    public void compensationFailed(UUID stepId, String error) {
        with(stepId, s -> s.noteCompensationFailure(error));
    }

    @Override
    public Map<String, Binding> bindings(UUID briefId) {
        return new LinkedHashMap<>(bindings.getOrDefault(briefId.toString(), Map.of()));
    }

    @Override
    public void bind(UUID briefId, String ref, String kind, String entityId) {
        bindings.computeIfAbsent(briefId.toString(), k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(ref, new Binding(kind, entityId));
    }

    @Override
    public void unbind(UUID briefId, String ref) {
        bindings.getOrDefault(briefId.toString(), new java.util.concurrent.ConcurrentHashMap<>()).remove(ref);
    }

    @Override
    public List<StepView> steps(UUID briefId, UUID jobId) {
        List<StepView> out = new ArrayList<>();
        rows.stream().filter(r -> r.getBriefId().equals(briefId) && r.getJobId().equals(jobId) && !JOB_KIND.equals(r.getKind()))
            .sorted(Comparator.comparingInt(BriefApplyStepEntity::getSeq))
            .forEach(r -> out.add(new StepView(r.getSeq(), r.getKind(), r.getRef(), r.getEntityId(), r.getStatus(), r.getError())));
        return out;
    }

    private Optional<BriefApplyStepEntity> row(UUID briefId, UUID jobId) {
        return rows.stream().filter(r -> r.getBriefId().equals(briefId) && r.getJobId().equals(jobId)
            && JOB_KIND.equals(r.getKind())).findFirst();
    }

    @Override
    public Optional<JobView> job(UUID briefId, UUID jobId) {
        return row(briefId, jobId).map(InMemoryLedger::view);
    }

    @Override
    public Optional<JobView> latestSuccessfulJob(UUID briefId) {
        return rows.stream().filter(r -> r.getBriefId().equals(briefId) && JOB_KIND.equals(r.getKind())
                && BriefApplyStepEntity.DONE.equals(r.getStatus()))
            .max(Comparator.comparing(BriefApplyStepEntity::getCreatedAt)).map(InMemoryLedger::view);
    }

    @Override
    public Optional<JobView> runningJob(UUID briefId, Instant notBefore) {
        return rows.stream().filter(r -> r.getBriefId().equals(briefId) && JOB_KIND.equals(r.getKind())
                && BriefApplyStepEntity.PENDING.equals(r.getStatus()) && r.getCreatedAt().isAfter(notBefore))
            .findFirst().map(InMemoryLedger::view);
    }

    private static JobView view(BriefApplyStepEntity r) {
        return new JobView(r.getJobId(), r.getVersion(), r.getStatus(), r.getError(), r.getCreatedAt());
    }
}
