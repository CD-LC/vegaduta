package com.agenticai.core.workflow;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowPinRequest;
import com.agenticai.core.workflow.dto.WorkflowPinResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The permission-checked side of data pinning (spec section 5): list, create and delete the pinned
 * output of a node on the draft graph.
 *
 * <p>Permissions (the existing {@link PermissionLevel} ladder RUNNER &lt; VIEWER &lt; EDITOR):
 *
 * <ul>
 *   <li>create / replace / delete a pin, and read the pinned data: EDITOR, the level that edits the
 *       graph;
 *   <li>list pins: RUNNER or above, but {@code data} is null unless the caller is an EDITOR. A
 *       VIEWER (who may read the graph configuration) gets metadata only, because pins are run data
 *       - often real customer data - not configuration. Nobody below EDITOR gets a 403 for listing:
 *       the UI needs the "pinned" badges to label a draft run honestly.
 * </ul>
 *
 * Draft runs and persisted-pin step tests need EDITOR (a RUNNER runs the PRODUCTION version only),
 * so a pin is only ever consumed by someone who could read it. What this does NOT hide: the trace
 * of a draft run shows the pinned output and the inputs of nodes after it (redacted and capped like
 * any trace payload, readable at VIEWER like any run trace). Every create and delete is
 * audit-logged (who, which node, source, size - never the pinned data itself). The engine does not
 * come through here: it reads {@link WorkflowPinStore} directly for the run it serves.
 */
@Service
public class WorkflowPinService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPinService.class);

    static final String AUDIT_SET = "WORKFLOW_PIN_SET";
    static final String AUDIT_DELETE = "WORKFLOW_PIN_DELETE";
    private static final String AUDIT_TARGET = "Workflow";

    /** Sorted keys, so the same config always serialises (and hashes) the same way. */
    private static final ObjectMapper CANONICAL = JsonMappers.sortedMapKeys();

    private final WorkflowService workflowService;
    private final WorkflowPinStore store;
    private final AuditLogService auditLogService;

    public WorkflowPinService(
            WorkflowService workflowService,
            WorkflowPinStore store,
            AuditLogService auditLogService) {
        this.workflowService = workflowService;
        this.store = store;
        this.auditLogService = auditLogService;
    }

    public List<WorkflowPinResponse> list(UUID workflowId) {
        workflowService.requireAccess(workflowId, PermissionLevel.RUNNER);
        boolean canRead = canEdit(workflowId);
        return store.list(workflowId).stream().map(p -> toResponse(p, canRead)).toList();
    }

    public WorkflowPinResponse put(
            UUID workflowId, String nodeId, WorkflowPinRequest request, String actor) {
        workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A pin needs a body with the data to pin.");
        }
        WorkflowNodeDto node = findNode(workflowService.graph(workflowId), nodeId);
        if (node == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Node '"
                            + nodeId
                            + "' is not in this workflow's saved graph - save the canvas first.");
        }
        String source =
                request.source() == null || request.source().isBlank()
                        ? "manual"
                        : request.source().strip();
        String hash =
                request.configHash() != null && !request.configHash().isBlank()
                        ? request.configHash().strip()
                        : configHash(node);
        WorkflowNodePin saved =
                store.save(
                        workflowId,
                        nodeId,
                        request.data(),
                        source,
                        blankToNull(request.sourceRunId()),
                        actor,
                        hash);
        audit(
                actor,
                AUDIT_SET,
                workflowId,
                "Pinned node '"
                        + nodeId
                        + "' (source "
                        + source
                        + ", "
                        + saved.getSizeChars()
                        + " chars).");
        return toResponse(saved, true);
    }

    /** Idempotent: deleting a pin that does not exist is not an error. */
    public void delete(UUID workflowId, String nodeId, String actor) {
        workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
        if (store.delete(workflowId, nodeId)) {
            audit(actor, AUDIT_DELETE, workflowId, "Unpinned node '" + nodeId + "'.");
        }
    }

    /**
     * A stable hash of what makes the node produce its output: its type and config (not its label
     * or position). Recorded when a pin is created and compared by the linter (STALE_PIN) to flag
     * pinned data that predates a config change.
     */
    public static String configHash(WorkflowNodeDto node) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT));
        shape.put("config", node.config());
        try {
            byte[] json = CANONICAL.writeValueAsBytes(shape);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 32);
        } catch (tools.jackson.core.JacksonException | NoSuchAlgorithmException e) {
            // Unreachable for plain config maps; a missing hash only disables the stale-pin hint.
            log.warn("Could not hash the config of node {}: {}", node.id(), e.getMessage());
            return null;
        }
    }

    static WorkflowPinResponse toResponse(WorkflowNodePin pin, boolean withData) {
        return new WorkflowPinResponse(
                pin.getNodeId(),
                pin.getSource(),
                pin.getSourceRunId(),
                pin.getPinnedBy(),
                pin.getPinnedAt() == null ? null : pin.getPinnedAt().toString(),
                pin.getSizeChars(),
                pin.getConfigHash(),
                withData ? pin.getDataText() : null);
    }

    private boolean canEdit(UUID workflowId) {
        try {
            workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
            return true;
        } catch (ResourceAccessDeniedException e) {
            return false;
        }
    }

    private static WorkflowNodeDto findNode(WorkflowGraphDto graph, String nodeId) {
        for (WorkflowNodeDto n : graph.nodes()) {
            if (nodeId.equals(n.id())) {
                return n;
            }
        }
        return null;
    }

    /** Best effort: a failure writing the audit row must not undo or fail the pin change itself. */
    private void audit(String actor, String action, UUID workflowId, String detail) {
        try {
            auditLogService.record(
                    actor == null ? "unknown" : actor,
                    action,
                    AUDIT_TARGET,
                    workflowId.toString(),
                    AuditOutcome.SUCCESS,
                    detail);
        } catch (RuntimeException e) {
            log.warn("Could not record audit entry {} for workflow {}", action, workflowId, e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
