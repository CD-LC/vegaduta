package com.agenticai.core.agents.memory;

/**
 * SEC-004 scoped memory-id derivation. Extracted unchanged from {@link SessionMemoryService}, which
 * keeps the public entry point and the {@code SCOPED_ID_PREFIX} constant.
 */
final class SessionScopedIds {

    private SessionScopedIds() {}

    /**
     * SEC-004 fix. The id every method of this class must be called with: a server-derived digest
     * of WHO owns the conversation, never the raw client-chosen session id.
     *
     * <p>Before this, the Redis key was {@code agentic:session:<sessionId>} with {@code sessionId}
     * taken verbatim from the request body (web chat, developer API, AG-UI {@code threadId}, A2A
     * {@code contextId}) or derived from a public share id plus an unsalted IP hash. Anyone who
     * learned or guessed another conversation's id - in ANY tenant - got that transcript replayed
     * into their own prompt, and their turns appended to it (a persistent prompt injection against
     * the victim). Now the key is {@code agentic:session:c1:<sha256(tenant, agent, principal,
     * sessionId)>}: two callers only share a history when all four components match, so the same
     * client id used by another tenant, another user of the same tenant, or against another agent
     * lands in a disjoint list.
     *
     * <p>The components are length-prefixed before hashing, so no choice of delimiter characters
     * inside one component can shift bytes into another ({@code ("a:b","c")} and {@code
     * ("a","b:c")} hash differently). The raw digest is not a secret and needs no key: it is never
     * returned to a client, and producing a victim's value requires already knowing the victim's
     * tenant, agent, principal AND session id.
     *
     * <p>Migration: none. Histories stored under the old unscoped keys simply stop being read and
     * expire on their own TTL ({@code app.chat.session-ttl-hours}, 24h by default); a conversation
     * in flight during the deploy starts over with an empty history.
     *
     * <p>{@code DemoTranscriptEmailService} (core/tenancy, which cannot import this module)
     * re-implements this derivation byte for byte; {@code SessionMemoryScopeTest} pins that the two
     * agree.
     *
     * @param tenantId the {@code TenantContext} tenant id of the turn (null is allowed and hashed
     *     as empty - only reachable when no tenant is bound)
     * @param agentId the agent the caller ADDRESSED (the pre-experiment id, so a turn routed into
     *     an A/B treatment clone still shares history with its control turns)
     * @param principal the stable identity of the caller: authenticated user subject, API-key grant
     *     label, channel external id, or share-visitor id - i.e. the chat {@code subjectKey}
     * @param clientSessionId the id the client (or server-side channel) uses for this conversation
     */
    static String scopedSessionId(
            String tenantId, java.util.UUID agentId, String principal, String clientSessionId) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part :
                    new String[] {
                        tenantId,
                        agentId == null ? null : agentId.toString(),
                        principal,
                        clientSessionId
                    }) {
                byte[] bytes =
                        (part == null ? "" : part)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return SessionMemoryService.SCOPED_ID_PREFIX
                    + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
