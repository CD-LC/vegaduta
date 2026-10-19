package com.agenticai.core.workflow.dto;

import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.workflow.WebhookAuthMode;
import jakarta.validation.constraints.NotNull;

/** {@code webhookAuthMode} is ignored unless {@code channel == WEBHOOK} (defaults to SHARED_SECRET if omitted). */
public record WorkflowTriggerChannelRequest(@NotNull WorkflowTriggerChannel channel, WebhookAuthMode webhookAuthMode) {
}
