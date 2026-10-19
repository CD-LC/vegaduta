package com.agenticai.core.approval;

/**
 * Autonomy/Guardrails Strategy Stage C - opt-in marker an {@link
 * ApprovalActionExecutor}'s thrown exception can implement to tell {@link
 * HumanApprovalGateService}'s retry logic "this failure is transient, retry
 * me" (e.g. a network timeout calling a channel's API). Absence means
 * "don't retry" - the conservative default, matching this codebase's
 * established pattern of opt-in over assumed-safe (same posture as {@code
 * Agent.allowedToolNames} being opt-in rather than default-deny). A
 * malformed payload or invalid-credential failure won't fix itself on
 * retry, so silently retrying it would just delay a permanent failure
 * rather than resolve it - only a caller who knows the failure is
 * transient should opt in.
 *
 * <p>Deliberately just a marker (no method to override): an exception
 * either represents a transient condition or it doesn't - there's no
 * "maybe" this interface needs to express. Deliberately declared in {@code
 * approval}, not in a channel-specific package - this keeps {@link
 * HumanApprovalGateService}'s retry decision fully decoupled from any
 * specific action type, the same "this entity has no idea what
 * channel.whatsapp.send means" encapsulation {@link ApprovalRequest}'s
 * javadoc already establishes for {@code payloadJson}.
 */
public interface RetryableActionFailure {
}
