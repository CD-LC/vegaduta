package com.agenticai.core.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory stand-ins for the pin and run-data repositories, plus the mapper the app serves JSON
 * with.
 */
final class PinTestSupport {

    private PinTestSupport() {}

    /**
     * Spring's own JSON mapper configuration (ISO dates, nulls kept): what the controllers
     * serialise with.
     */
    static ObjectMapper appMapper() {
        return tools.jackson.databind.json.JsonMapper.builder()
                .configureForJackson2()
                .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** A {@link WorkflowNodePinRepository} that really stores, counts, sums and deletes. */
    static WorkflowNodePinRepository pinRepository() {
        Map<WorkflowNodePin.Key, WorkflowNodePin> rows = new HashMap<>();
        WorkflowNodePinRepository repo = mock(WorkflowNodePinRepository.class);
        when(repo.findById(any(WorkflowNodePin.Key.class)))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        rows.get(inv.<WorkflowNodePin.Key>getArgument(0))));
        when(repo.save(any(WorkflowNodePin.class)))
                .thenAnswer(
                        inv -> {
                            WorkflowNodePin p = inv.getArgument(0);
                            rows.put(new WorkflowNodePin.Key(p.getWorkflowId(), p.getNodeId()), p);
                            return p;
                        });
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            WorkflowNodePin p = inv.getArgument(0);
                            rows.remove(new WorkflowNodePin.Key(p.getWorkflowId(), p.getNodeId()));
                            return null;
                        })
                .when(repo)
                .delete(any(WorkflowNodePin.class));
        when(repo.findByWorkflowIdOrderByNodeId(any(UUID.class)))
                .thenAnswer(
                        inv ->
                                rows.values().stream()
                                        .filter(
                                                p ->
                                                        p.getWorkflowId()
                                                                .equals(inv.<UUID>getArgument(0)))
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                        WorkflowNodePin::getNodeId))
                                        .toList());
        when(repo.countOthers(any(UUID.class), anyString()))
                .thenAnswer(
                        inv ->
                                rows.values().stream()
                                        .filter(
                                                p ->
                                                        p.getWorkflowId()
                                                                        .equals(
                                                                                inv
                                                                                        .<UUID>
                                                                                                getArgument(
                                                                                                        0))
                                                                && !p.getNodeId()
                                                                        .equals(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        1)))
                                        .count());
        when(repo.sumSizeOthers(any(UUID.class), anyString()))
                .thenAnswer(
                        inv ->
                                rows.values().stream()
                                        .filter(
                                                p ->
                                                        p.getWorkflowId()
                                                                        .equals(
                                                                                inv
                                                                                        .<UUID>
                                                                                                getArgument(
                                                                                                        0))
                                                                && !p.getNodeId()
                                                                        .equals(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        1)))
                                        .mapToLong(WorkflowNodePin::getSizeChars)
                                        .sum());
        when(repo.deleteByWorkflowIdAndNodeIds(any(UUID.class), anyCollection()))
                .thenAnswer(
                        inv -> {
                            UUID wf = inv.getArgument(0);
                            Collection<String> ids = inv.getArgument(1);
                            List<WorkflowNodePin.Key> gone = new ArrayList<>();
                            for (WorkflowNodePin p : rows.values()) {
                                if (p.getWorkflowId().equals(wf) && ids.contains(p.getNodeId())) {
                                    gone.add(
                                            new WorkflowNodePin.Key(
                                                    p.getWorkflowId(), p.getNodeId()));
                                }
                            }
                            gone.forEach(rows::remove);
                            return gone.size();
                        });
        return repo;
    }

    /** A {@link WorkflowRunNodeOutputRepository} that keeps the latest row per (run, node, seq). */
    static WorkflowRunNodeOutputRepository runOutputRepository(List<WorkflowRunNodeOutput> saved) {
        WorkflowRunNodeOutputRepository repo = mock(WorkflowRunNodeOutputRepository.class);
        when(repo.save(any(WorkflowRunNodeOutput.class)))
                .thenAnswer(
                        inv -> {
                            WorkflowRunNodeOutput o = inv.getArgument(0);
                            saved.removeIf(
                                    x ->
                                            x.getRunId().equals(o.getRunId())
                                                    && x.getNodeId().equals(o.getNodeId())
                                                    && x.getSeq() == o.getSeq());
                            saved.add(o);
                            return o;
                        });
        when(repo.findFirstByRunIdAndNodeIdOrderBySeqDesc(any(UUID.class), anyString()))
                .thenAnswer(
                        inv ->
                                saved.stream()
                                        .filter(
                                                x ->
                                                        x.getRunId()
                                                                        .equals(
                                                                                inv
                                                                                        .<UUID>
                                                                                                getArgument(
                                                                                                        0))
                                                                && x.getNodeId()
                                                                        .equals(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        1)))
                                        .max(
                                                java.util.Comparator.comparingInt(
                                                        WorkflowRunNodeOutput::getSeq)));
        when(repo.findFirstByRunIdAndNodeLabelOrderBySeqDescCreatedAtDesc(
                        any(UUID.class), anyString()))
                .thenAnswer(
                        inv ->
                                saved.stream()
                                        .filter(
                                                x ->
                                                        x.getRunId()
                                                                        .equals(
                                                                                inv
                                                                                        .<UUID>
                                                                                                getArgument(
                                                                                                        0))
                                                                && inv.<String>getArgument(1)
                                                                        .equals(x.getNodeLabel()))
                                        .max(
                                                java.util.Comparator.comparingInt(
                                                        WorkflowRunNodeOutput::getSeq)));
        return repo;
    }
}
