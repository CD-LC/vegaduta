package com.agenticai.core.omnichannel.status;

/**
 * One channel's connection state for the Channels screen: the summary on the status grid ({@link
 * ChannelStatusService}) and the live "test connection" probe ({@link ChannelHealthController}).
 * Implemented in each channel's package (ADR-0011 rule a); the controllers own the grid's order,
 * labels and session counts. WhatsApp, which spans two providers, is reported from {@code
 * whatsapp.WhatsAppProvider} instead.
 *
 * <p>Every summary tracks the ACTIVE-only definition every send/receive path uses (#597): a
 * channel's {@code current()} deliberately keeps returning DISCONNECTED rows so its own detail
 * panel can say "was connected as X - reconnect below", but this aggregate must not call a
 * disconnected credential connected.
 */
public interface ChannelConnection {

    /** The Channels screen key, e.g. {@code telegram}. */
    String key();

    /**
     * @param state {@code CONNECTED}, {@code NOT_CONNECTED} or {@code NEEDS_ATTENTION}
     */
    record Summary(String state, String detail) {

        public static Summary connected(String detail) {
            return new Summary("CONNECTED", detail);
        }

        public static Summary notConnected(String detail) {
            return new Summary("NOT_CONNECTED", detail);
        }

        public static Summary needsAttention(String detail) {
            return new Summary("NEEDS_ATTENTION", detail);
        }
    }

    /** Cheap: reads stored credential state only, no outbound call. */
    Summary summary();

    /**
     * The same real, non-billed call activation used to prove the credential - never a synthetic
     * ping.
     *
     * @return a short identity string ("who answered"), never any secret
     * @throws ChannelNotConnectedException when the channel has no ACTIVE credential at all
     */
    String probe();
}
