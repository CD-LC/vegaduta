package com.agenticai.core.workflow;

/**
 * Thrown by {@code WorkflowService#setTriggerChannel} (not, despite this
 * javadoc's prior claim, {@code WorkflowTriggerNodeService#setTrigger} -
 * the rejection happens before that "dumb" CRUD is ever called) for
 * {@link WorkflowTriggerChannel#CALL}. Stale-doc fix: this codebase DOES now
 * have a voice/telephony adapter ({@code TwilioVoiceWebhookController}, real-
 * time Conversation Relay voice agents, Master Roadmap 2026 item #17) - but
 * it wires an inbound call straight to a channel-bound agent conversation,
 * the same way WhatsApp/Telegram do, and has nothing to do with the
 * workflow-designer's trigger-channel feature this exception guards. A
 * workflow still cannot be bound to CALL the way it can to WEBHOOK/WHATSAPP/
 * TELEGRAM/SMS, so accepting that setting would still silently do nothing;
 * that part of the original claim stands. Mapped to 400 by
 * {@code GlobalExceptionHandler}, same posture as {@code WorkflowValidationException}.
 */
public class UnsupportedTriggerChannelException extends RuntimeException {

    public UnsupportedTriggerChannelException(String message) {
        super(message);
    }
}
