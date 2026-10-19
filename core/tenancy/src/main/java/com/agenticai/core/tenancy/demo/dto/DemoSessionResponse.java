package com.agenticai.core.tenancy.demo.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The shape both {@code POST /api/demo/session} and {@code GET
 * /api/demo/session} return.
 *
 * @param demoToken the raw bearer token, present ONLY on the create
 *     response - {@code null} on every status read, because only the SHA-256
 *     hash is persisted and there is nothing to return. The browser is the
 *     sole holder; losing it means starting a new sandbox.
 * @param agentId the pre-seeded sandbox agent, or {@code null} if seeding
 *     did not succeed (the sandbox is still usable - the chat endpoint
 *     re-attempts the seed on first use).
 * @param agentName human-facing name of that agent, or {@code null}.
 * @param daysRemaining whole days left, floored at 0 - the number the
 *     {@code /try} countdown renders.
 * @param messagesUsed / {@code messageLimit} the per-sandbox turn cap, so
 *     the UI can show the real remaining allowance rather than discovering
 *     it by hitting a wall.
 * @param imagesUsed / {@code imageLimit} the same, for AI image generation
 *     (migration V127). Reported here and not only on the image response so
 *     that a RESUMED sandbox knows its remaining allowance on first paint -
 *     without it the /try page would have to render "2 images left" to a
 *     visitor who already used both, and only correct itself after they hit
 *     the wall. That is precisely the "discover the limit by tripping it"
 *     failure this record already avoids for messages.
 * @param imageEnabled false when {@code demo.image.enabled} is off, so the
 *     UI hides the affordance entirely rather than offering a button that
 *     can only decline.
 * @param expired true once the session has lapsed; the sandbox is read-only
 *     from that point and the UI switches to a register-to-keep-going state.
 * @param readOnly true when the sandbox is past its editable window
 *     ({@code demo.editable-hours}, default 24h) but not yet expired - the
 *     view-only phase. The transcript stays reviewable (see
 *     {@code GET /api/demo/history}); chat/image/video refuse softly.
 *     Mutually exclusive with {@code expired} by construction.
 * @param editableUntil the instant the editable phase ends
 *     ({@code createdAt + demo.editable-hours}) - reported on create AND
 *     status so the UI can show a real countdown instead of discovering the
 *     flip by having a message refused.
 * @param persona the one-click persona preset this sandbox was created with
 *     ({@code "kirana"} etc - see {@code DemoPersona}), or {@code null} for
 *     the generic agent. Echoed on both create and status so a RESUMED
 *     sandbox renders the same persona framing it started with, exactly the
 *     reasoning already documented above for {@code imagesUsed}.
 */
public record DemoSessionResponse(
    String demoToken,
    UUID tenantId,
    String workspaceName,
    UUID agentId,
    String agentName,
    Instant createdAt,
    Instant expiresAt,
    long daysRemaining,
    int messagesUsed,
    int messageLimit,
    int imagesUsed,
    int imageLimit,
    boolean imageEnabled,
    boolean expired,
    boolean readOnly,
    Instant editableUntil,
    String persona
) {
}
