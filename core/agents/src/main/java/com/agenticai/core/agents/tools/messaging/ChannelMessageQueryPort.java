package com.agenticai.core.agents.tools.messaging;

import java.time.Instant;
import java.util.List;

/**
 * #604 ("read all messages from telegram" - Quick Task had no Telegram tool because no message
 * history existed anywhere to read from). {@code core/omnichannel} owns the actual message log
 * ({@code ChannelMessageLog}) but {@code core/omnichannel}'s own {@code pom.xml} already depends on
 * {@code core/agents} ("Depends on: agents, approval, audit, crypto, security, tenancy") - so
 * {@code core/agents} cannot depend back on {@code core/omnichannel} without a circular Maven
 * module dependency. This interface is the standard inversion for that: it is defined here, in
 * {@code core/agents} (where {@link TelegramMessagesToolCallback} needs it), and implemented over
 * in {@code core/omnichannel} (where the data actually lives) - Spring wires the two together at
 * runtime with no compile-time dependency in either direction beyond the one that already exists.
 *
 * <p>Deliberately takes a plain {@code String} for the channel, not {@code ChannelType} - that enum
 * also lives in {@code core/omnichannel}, and importing it here would reintroduce the exact
 * circularity this interface exists to avoid. Implementations are expected to match it case-
 * insensitively against their own channel enum's {@code name()}.
 */
public interface ChannelMessageQueryPort {

    /**
     * @param channel channel name, e.g. {@code "TELEGRAM"} - case-insensitive.
     * @param limit soft cap on how many messages to return, newest first.
     * @return the most recent messages for that channel, newest first. Empty (never null) if the
     *     channel is unknown or has no messages yet.
     */
    List<ChannelMessageRecord> recentMessages(String channel, int limit);

    /**
     * Cross-session conversation search (P1 developer-extensibility surface): case-insensitive
     * substring match over the persisted {@code channel_message_log} transcript - every channel,
     * every session, both directions (inbound customer messages AND the operator/agent replies,
     * which live in the same table since V214). This is what lets an agent answer "what did we
     * discuss with this customer last week" or "has anyone asked about refunds before" - session
     * memory itself is Redis with a TTL and is deliberately not searched.
     *
     * @param channel optional channel name filter (case-insensitive), null for all channels.
     * @param externalId optional single-sender filter, null for every sender.
     * @param query required substring to match in message bodies.
     * @param limit soft cap, newest first.
     * @return matching messages, newest first; empty (never null) when nothing matches or the
     *     port's backing module is absent.
     */
    List<ConversationSearchHit> searchMessages(
            String channel, String externalId, String query, int limit);

    record ChannelMessageRecord(String from, String body, Instant receivedAt) {}

    /**
     * One search hit. Richer than {@link ChannelMessageRecord} because a cross-channel result set
     * needs the channel on every row, and {@code direction}/{@code author} distinguish "a customer
     * said this" from "the operator replied this" - both live in the same transcript table.
     */
    record ConversationSearchHit(
            String channel,
            String from,
            String direction,
            String author,
            String body,
            Instant receivedAt) {}
}
