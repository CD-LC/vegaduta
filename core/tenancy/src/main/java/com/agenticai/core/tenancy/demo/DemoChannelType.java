package com.agenticai.core.tenancy.demo;

/**
 * Which real-world surface a {@link DemoChannelLink} row describes.
 *
 * <p>Deliberately its own small enum rather than a reuse of {@code
 * com.agenticai.core.omnichannel.session.ChannelType}: that enum lives in a
 * module this one cannot depend on (the edge runs omnichannel &rarr;
 * tenancy), it carries channels that make no sense for an anonymous 5-day
 * sandbox (Email/IMAP, Slack, Discord, X), and - the honest reason -
 * {@link #WHATSAPP_PREVIEW} is not a channel at all, so putting it in the
 * real channel enum would be a lie that some future switch statement would
 * eventually act on.
 */
public enum DemoChannelType {

    /**
     * A genuine two-way binding to the shared platform demo bot on Telegram.
     * Messages sent to that bot from the linked chat are answered by this
     * sandbox's real agent, over the real Bot API. Nothing about this one is
     * simulated.
     */
    TELEGRAM,

    /**
     * A genuine two-way binding to the PLATFORM-owned Meta WhatsApp Business
     * number (click-to-chat {@code wa.me} link prefilled with the pairing
     * code). The visitor's phone number is only ever stored as a keyed hash
     * ({@code external_id}); see {@code DemoWhatsAppBot#hashPhone}. Absent
     * unless the platform number is configured. NOT an unofficial
     * "link your own device" flow, which is deliberately not offered.
     */
    WHATSAPP,

    /**
     * NOT a WhatsApp connection. The /try page renders the visitor's REAL
     * agent reply inside a WhatsApp-styled frame and says so in as many
     * words; this value exists only so the funnel can answer "how many
     * visitors tried the WhatsApp affordance" next to the Telegram number.
     *
     * <p>See {@code DemoChannelController}'s javadoc for why an actual
     * WhatsApp connection is not offered to anonymous visitors.
     */
    WHATSAPP_PREVIEW
}
