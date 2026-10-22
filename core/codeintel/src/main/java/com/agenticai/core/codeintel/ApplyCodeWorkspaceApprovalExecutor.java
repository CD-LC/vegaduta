package com.agenticai.core.codeintel;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies full-file-content writes/deletes to a code-intel scratch workspace, ONLY once a human has
 * approved the request - see {@link RunCodeWorkspaceApprovalExecutor}'s javadoc for the full
 * two-gate design (this platform's approval first, then code-intel's own independent policy) and
 * the known limitation around not being able to surface the outcome back through {@link
 * ApprovalRequest} itself.
 */
// 2026-07-20 hotfix: this bean unconditionally depended on CodeIntelClient,
// which is itself @ConditionalOnProperty(code-intel.enabled=true) - default
// false. Without this annotation, the WHOLE application fails to start
// whenever code-intel is off (the out-of-the-box default), not just this
// feature - confirmed via a real startup stack trace naming this exact
// class. Mirrors CodeIntelClient's own conditional so this bean only
// exists when its one dependency does.
@Component
@ConditionalOnBean(CodeIntelClient.class)
public class ApplyCodeWorkspaceApprovalExecutor implements ApprovalActionExecutor {

    public static final String ACTION_TYPE = "codeintel.workspace.apply";

    private static final Logger log =
            LoggerFactory.getLogger(ApplyCodeWorkspaceApprovalExecutor.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodeIntelClient client;

    public ApplyCodeWorkspaceApprovalExecutor(CodeIntelClient client) {
        this.client = client;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    @Override
    public void execute(ApprovalRequest request) {
        ApplyPayload payload = parsePayload(request.getPayloadJson());
        String raw =
                client.applyToWorkspace(
                        payload.repoId(), payload.workspaceId(), payload.files(), null);
        JsonNode result = readTree(raw);
        CodeWorkspaceResultParser.checkForFailure("apply_code_changes", result);
        int fileCount = payload.files() == null ? 0 : payload.files().size();
        log.info(
                "code-intel apply to workspace {} (repo {}) approved and executed - {} file change(s) requested",
                payload.workspaceId(),
                payload.repoId(),
                fileCount);
    }

    private ApplyPayload parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new CodeWorkspaceExecutionException("Approval request has no payload to apply");
        }
        try {
            return MAPPER.readValue(payloadJson, ApplyPayload.class);
        } catch (Exception e) {
            throw new CodeWorkspaceExecutionException(
                    "Could not parse apply_code_changes payload: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RetryableCodeWorkspaceExecutionException(
                    "apply_code_changes returned a non-JSON response: " + e.getMessage());
        }
    }

    /**
     * {@code adminOnly} is read by {@code ApprovalController#enforceApproverAuthorization} straight
     * off the stored payload JSON - see {@link ApplyCodeChangesToolCallback}.
     */
    public record ApplyPayload(
            String repoId,
            String workspaceId,
            List<Map<String, Object>> files,
            boolean adminOnly) {}
}
