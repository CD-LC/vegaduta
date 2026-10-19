package com.agenticai.core.omnichannel.session;

/**
 * Which way a {@link ChannelMessageLog} row travelled (migration V214).
 * Until V214 the log was inbound-only by construction - the single write
 * site was {@code ChannelSessionService#resolveOrCreate} - so every
 * pre-existing row is backfilled {@link #INBOUND} by the column's
 * {@code DEFAULT}, which is true for all of them by definition.
 *
 * <p>{@link #OUTBOUND} today means an operator reply written by {@code
 * OperatorInboxService#reply}. Agent replies are NOT yet logged (their send
 * sites live in the per-channel webhook controllers and adapters, other
 * workstreams' scope) - a documented gap in the operator transcript, not a
 * silent one; see V214's migration header.
 */
public enum ChannelMessageDirection {
    INBOUND,
    OUTBOUND
}
