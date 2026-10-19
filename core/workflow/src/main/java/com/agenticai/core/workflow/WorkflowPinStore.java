package com.agenticai.core.workflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Data access and limits for {@link WorkflowNodePin}, with no permission logic on purpose: the engine reads
 * pins here on behalf of a draft run whose RUNNER may execute with them but not read them, and
 * {@link WorkflowService} prunes them on a graph save. Who may call the API is {@link WorkflowPinService}'s
 * job; the size limits live here so no caller can skip them.
 *
 * <p>Limits: {@value #MAX_PIN_CHARS} characters per pin, {@value #MAX_PINS} pins and {@value #MAX_TOTAL_CHARS}
 * characters in total per workflow. The data must be non-empty text; it is stored exactly as given, never
 * parsed or reformatted (JSON is usual but not required).
 */
@Component
public class WorkflowPinStore {

    public static final int MAX_PIN_CHARS = 256_000;
    public static final int MAX_PINS = 30;
    public static final int MAX_TOTAL_CHARS = 2_000_000;

    public static final Set<String> SOURCES = Set.of("test", "run", "manual", "trigger_sample");

    private static final int MAX_SOURCE_RUN_ID_CHARS = 64;
    private static final int MAX_CONFIG_HASH_CHARS = 128;

    private final WorkflowNodePinRepository repository;

    public WorkflowPinStore(WorkflowNodePinRepository repository) {
        this.repository = repository;
    }

    public Optional<WorkflowNodePin> find(UUID workflowId, String nodeId) {
        if (workflowId == null || nodeId == null) {
            return Optional.empty();
        }
        return repository.findById(new WorkflowNodePin.Key(workflowId, nodeId));
    }

    public List<WorkflowNodePin> list(UUID workflowId) {
        return repository.findByWorkflowIdOrderByNodeId(workflowId);
    }

    /**
     * Creates or replaces the pin of a node.
     *
     * @throws ResponseStatusException 400 when the data is empty or over a limit, or the source is unknown
     */
    @Transactional
    public WorkflowNodePin save(
        UUID workflowId, String nodeId, String data, String source, String sourceRunId, String pinnedBy,
        String configHash
    ) {
        if (data == null || data.isBlank()) {
            throw bad("Pin data must be non-empty text.");
        }
        if (data.length() > MAX_PIN_CHARS) {
            throw bad("Pin data is " + data.length() + " characters; the limit is " + MAX_PIN_CHARS
                + ". Reduce the node's output first (for example with a Transform node).");
        }
        if (source == null || !SOURCES.contains(source)) {
            throw bad("Pin source must be one of " + new ArrayList<>(new java.util.TreeSet<>(SOURCES)) + ".");
        }
        if (sourceRunId != null && sourceRunId.length() > MAX_SOURCE_RUN_ID_CHARS) {
            throw bad("sourceRunId is too long.");
        }
        if (configHash != null && configHash.length() > MAX_CONFIG_HASH_CHARS) {
            throw bad("configHash is too long.");
        }
        // Re-pinning a node replaces its own row, so it is counted against the OTHER pins only.
        if (repository.countOthers(workflowId, nodeId) >= MAX_PINS) {
            throw bad("This workflow already has " + MAX_PINS + " pinned nodes; unpin one first.");
        }
        long others = repository.sumSizeOthers(workflowId, nodeId);
        if (others + data.length() > MAX_TOTAL_CHARS) {
            throw bad("Pinned data for this workflow would exceed " + MAX_TOTAL_CHARS + " characters in total ("
                + others + " already pinned on other nodes); unpin something or pin less.");
        }
        WorkflowNodePin pin = repository.findById(new WorkflowNodePin.Key(workflowId, nodeId)).orElse(null);
        if (pin == null) {
            pin = new WorkflowNodePin(workflowId, nodeId, data, source, sourceRunId, pinnedBy, configHash);
        } else {
            pin.replace(data, source, sourceRunId, pinnedBy, configHash);
        }
        return repository.save(pin);
    }

    /** @return true when a pin existed and was removed */
    @Transactional
    public boolean delete(UUID workflowId, String nodeId) {
        Optional<WorkflowNodePin> existing = repository.findById(new WorkflowNodePin.Key(workflowId, nodeId));
        existing.ifPresent(repository::delete);
        return existing.isPresent();
    }

    /**
     * Graph-save hook: deletes the pins of nodes that are no longer in the saved graph, so a removed node
     * leaves no orphan data (which may hold PII) behind.
     *
     * @return how many pins were removed
     */
    @Transactional
    public int deleteForRemovedNodes(UUID workflowId, Collection<String> liveNodeIds) {
        Set<String> live = liveNodeIds == null ? Set.of() : Set.copyOf(liveNodeIds);
        List<String> removed = new ArrayList<>();
        for (WorkflowNodePin pin : repository.findByWorkflowIdOrderByNodeId(workflowId)) {
            if (!live.contains(pin.getNodeId())) {
                removed.add(pin.getNodeId());
            }
        }
        return removed.isEmpty() ? 0 : repository.deleteByWorkflowIdAndNodeIds(workflowId, removed);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
