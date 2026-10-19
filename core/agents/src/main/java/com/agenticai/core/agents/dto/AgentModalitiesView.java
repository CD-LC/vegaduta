package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.modality.ModelModalities;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code modalities} object on {@link AgentResponse} - the shared API
 * contract the web Playground reads to decide which attach controls to
 * offer, plus the two hard inline-attachment limits so the frontend's
 * pre-flight checks match the backend's rejections exactly.
 */
public record AgentModalitiesView(
    boolean image,
    boolean audio,
    boolean video,
    boolean document,
    long maxAttachmentBytes,
    int maxAttachments,
    // Data-analysis sandbox: true when CSV/XLSX/JSON/PDF/TXT uploads are accepted for this agent
    // because they are mounted in its run_code sandbox (Code execution on AND server flag on),
    // independent of the model's own document modality.
    boolean sandboxData,
    // Why sandboxData is false, as a short user-facing sentence (turn on Code execution / connect
    // E2B / not available yet). Only ever set when the platform flag
    // app.sandbox.data-analysis.enabled is ON; null - and omitted from the JSON - otherwise, so the
    // response is unchanged by default. Always null when sandboxData is true.
    @JsonInclude(JsonInclude.Include.NON_NULL) String sandboxDataUnavailableReason
) {
    public static AgentModalitiesView of(ModelModalities modalities) {
        return of(modalities, false);
    }

    public static AgentModalitiesView of(ModelModalities modalities, boolean sandboxData) {
        return of(modalities, sandboxData, null);
    }

    public static AgentModalitiesView of(
        ModelModalities modalities, boolean sandboxData, String sandboxDataUnavailableReason
    ) {
        ModelModalities m = modalities == null ? ModelModalities.NONE : modalities;
        return new AgentModalitiesView(
            m.image(), m.audio(), m.video(), m.document(),
            ModelModalities.MAX_ATTACHMENT_TOTAL_BYTES, ModelModalities.MAX_ATTACHMENTS, sandboxData,
            sandboxData || sandboxDataUnavailableReason == null || sandboxDataUnavailableReason.isBlank()
                ? null : sandboxDataUnavailableReason
        );
    }
}
