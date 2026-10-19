package com.agenticai.core.agents.tools.messaging;

import java.time.Instant;
import java.util.List;

/**
 * Agent-side channel administration - same cross-module inversion as {@link
 * ChannelMessageQueryPort} (see its javadoc). Gives an agent the same read/act surface the
 * console's Channels screen has: what is connected, which conversations exist, and the trust
 * actions on a conversation (approve a pairing, unpair, re-point it at a different agent).
 *
 * <p>Only the reversible, tenant-scoped actions are exposed here. Credential connect/disconnect
 * deliberately is NOT: severing a channel is an infrastructure decision with upstream effects
 * (webhook deletion, gateway teardown, external-account unlink) that belongs to a human admin's
 * click, not a model's tool call - the same reason {@code request_human} exists rather than letting
 * the model decide to page a person unmediated. If a real use case ever justifies agent-driven
 * disconnect, it can be added then with its own analysis.
 */
public interface ChannelControlPort {

    /**
     * The same aggregate the console's Channels screen renders: one row per channel with its live
     * state, human-readable detail, paired-session count, most recent inbound timestamp and
     * currently routed default agent. Empty (never null) when the omnichannel module is absent.
     */
    List<ChannelOverviewEntry> overview();

    /**
     * @param channel optional channel name filter (case-insensitive), null or blank for every
     *     channel.
     * @param limit soft cap, most recently active first.
     * @return channel sessions (conversations) - paired ones AND senders still waiting on pairing
     *     approval, so the agent can see pending approvals it could act on. Empty (never null) when
     *     none exist.
     */
    List<ChannelSessionInfo> sessions(String channel, int limit);

    /**
     * One trust action on one channel session - the agent-facing subset of what {@code
     * ChannelPairingController} exposes to tenant admins.
     *
     * @param action {@code "approve_pairing"}, {@code "unpair"} or {@code "rebind_agent"}
     *     (case-insensitive).
     * @param sessionId the channel session's UUID.
     * @param agentId required for {@code rebind_agent} (the target agent's UUID), ignored otherwise
     *     - pass null.
     * @return ok=false with a human-readable detail on an unknown action, unknown session, or a
     *     failed precondition - never throws for those.
     */
    ChannelSessionActionResult actOnSession(String action, String sessionId, String agentId);

    record ChannelOverviewEntry(
            String key,
            String label,
            String state,
            String detail,
            long sessionCount,
            Instant lastMessageAt,
            String defaultAgentId) {}

    record ChannelSessionInfo(
            String sessionId,
            String channel,
            String externalId,
            boolean paired,
            String agentId,
            Instant lastMessageAt,
            String preview) {}

    record ChannelSessionActionResult(boolean ok, String detail) {}
}
