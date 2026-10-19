package com.agenticai.core.omnichannel.trigger;

/**
 * Optional context an inbound channel controller attaches when it routes a
 * received message to a workflow (see {@link WorkflowTriggerPort#triggerAsync(
 * java.util.UUID, String, String, InboundTriggerContext)}). It carries the one
 * thing the plain {@code (workflowId, input, requestedBy)} trigger deliberately
 * does NOT: the actual address to reply to - e.g. the sender's WhatsApp number -
 * so a workflow's {@code Output(channel=whatsapp)} node can send its result back
 * to whoever triggered the run, rather than only to a number hard-coded on the
 * node.
 *
 * <p>Why this is kept separate from {@code requestedBy}: {@code requestedBy} is a
 * masked, human-readable audit label ({@code "system:whatsapp-inbound:+15*****89"})
 * that is safe to log, whereas {@link #replyToAddress()} is the real, unmasked
 * destination. It exists only to be handed straight to the outbound send and
 * must NOT be written to application logs or the run's audit label - the same
 * masking discipline every inbound-channel controller already follows for the
 * sender's number.
 *
 * @param channel        the channel the message arrived on (only {@link
 *                       WorkflowTriggerChannel#WHATSAPP} is wired to a reply-to-
 *                       sender Output node today; the field is kept general so a
 *                       consumer can confirm the reply address matches the
 *                       channel it intends to send on).
 * @param replyToAddress the real destination to reply to (e.g. the sender's
 *                       phone number, unmasked); never logged.
 */
public record InboundTriggerContext(WorkflowTriggerChannel channel, String replyToAddress) {
}
