package com.agenticai.core.brief;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link BriefApplyLedger} over {@code brief_apply_step} and {@code brief_binding}. */
@Service
public class JpaBriefApplyLedger implements BriefApplyLedger {

    private final BriefApplyStepRepository stepRepository;
    private final BriefBindingRepository bindingRepository;

    public JpaBriefApplyLedger(BriefApplyStepRepository stepRepository, BriefBindingRepository bindingRepository) {
        this.stepRepository = stepRepository;
        this.bindingRepository = bindingRepository;
    }

    @Override
    @Transactional
    public void startJob(UUID briefId, int version, UUID jobId) {
        stepRepository.save(new BriefApplyStepEntity(briefId, version, jobId, 0, JOB_KIND, null));
    }

    @Override
    @Transactional
    public void finishJob(UUID briefId, UUID jobId, boolean succeeded, String message) {
        jobRow(briefId, jobId).ifPresent(row -> {
            if (succeeded) {
                row.markDone(null, message);
            } else {
                row.markFailed(message);
            }
            stepRepository.save(row);
        });
    }

    @Override
    @Transactional
    public UUID begin(UUID briefId, int version, UUID jobId, int seq, String kind, String ref) {
        return stepRepository.save(new BriefApplyStepEntity(briefId, version, jobId, seq, kind, ref)).getId();
    }

    @Override
    @Transactional
    public void created(UUID stepId, String entityId) {
        update(stepId, s -> s.recordEntity(entityId));
    }

    @Override
    @Transactional
    public void done(UUID stepId, String entityId, String note) {
        update(stepId, s -> s.markDone(entityId != null ? entityId : s.getEntityId(), note));
    }

    @Override
    @Transactional
    public void failed(UUID stepId, String error) {
        update(stepId, s -> s.markFailed(error));
    }

    @Override
    @Transactional
    public void compensated(UUID stepId) {
        update(stepId, BriefApplyStepEntity::markCompensated);
    }

    @Override
    @Transactional
    public void compensationFailed(UUID stepId, String error) {
        update(stepId, s -> s.noteCompensationFailure(error));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Binding> bindings(UUID briefId) {
        Map<String, Binding> out = new LinkedHashMap<>();
        bindingRepository.findByBriefId(briefId)
            .forEach(b -> out.put(b.getRef(), new Binding(b.getKind(), b.getEntityId())));
        return out;
    }

    @Override
    @Transactional
    public void bind(UUID briefId, String ref, String kind, String entityId) {
        bindingRepository.save(new BriefBindingEntity(briefId, ref, kind, entityId));
    }

    @Override
    @Transactional
    public void unbind(UUID briefId, String ref) {
        bindingRepository.deleteById(new BriefBindingEntity.Key(briefId, ref));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StepView> steps(UUID briefId, UUID jobId) {
        return stepRepository.findByBriefIdAndJobIdOrderBySeqAsc(briefId, jobId).stream()
            .filter(s -> !JOB_KIND.equals(s.getKind()))
            .map(s -> new StepView(s.getSeq(), s.getKind(), s.getRef(), s.getEntityId(), s.getStatus(), s.getError()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobView> job(UUID briefId, UUID jobId) {
        return jobRow(briefId, jobId).map(JpaBriefApplyLedger::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobView> latestSuccessfulJob(UUID briefId) {
        return stepRepository.findByBriefIdAndKindOrderByCreatedAtDesc(briefId, JOB_KIND).stream()
            .filter(s -> BriefApplyStepEntity.DONE.equals(s.getStatus())).findFirst().map(JpaBriefApplyLedger::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JobView> runningJob(UUID briefId, Instant notBefore) {
        return stepRepository.findByBriefIdAndKindOrderByCreatedAtDesc(briefId, JOB_KIND).stream()
            .filter(s -> BriefApplyStepEntity.PENDING.equals(s.getStatus()) && s.getCreatedAt().isAfter(notBefore))
            .findFirst().map(JpaBriefApplyLedger::view);
    }

    private Optional<BriefApplyStepEntity> jobRow(UUID briefId, UUID jobId) {
        return stepRepository.findByBriefIdAndJobIdOrderBySeqAsc(briefId, jobId).stream()
            .filter(s -> JOB_KIND.equals(s.getKind())).findFirst();
    }

    private void update(UUID stepId, java.util.function.Consumer<BriefApplyStepEntity> change) {
        stepRepository.findById(stepId).ifPresent(s -> {
            change.accept(s);
            stepRepository.save(s);
        });
    }

    private static JobView view(BriefApplyStepEntity s) {
        return new JobView(s.getJobId(), s.getVersion(), s.getStatus(), s.getError(), s.getCreatedAt());
    }
}
