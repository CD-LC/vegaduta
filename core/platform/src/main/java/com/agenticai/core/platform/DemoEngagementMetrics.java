package com.agenticai.core.platform;

/**
 * "Did anyone actually use the sandbox they were given?" - the bounce side of
 * the funnel, plus the two optional sub-features.
 *
 * <p>The image and channel figures are {@code Long} rather than {@code long}
 * on purpose. They are sourced from {@code demo_session.image_count} (V127) and
 * {@code control_plane.demo_channel_link} (V128), both landed by separate
 * workstreams; {@link DemoSandboxMetricsService} probes the catalog for them at
 * query time and returns {@code null} with a populated {@code
 * ...UnavailableReason} when they are absent, rather than reporting a zero that
 * an operator would read as "nobody used it".
 *
 * @param averageMessagesPerSandbox mean over ALL sandboxes including the ones
 *     that never sent a turn (so it is directly comparable with {@code
 *     engagedSandboxes}); {@code null} when there are no sandboxes at all.
 * @param engagedSandboxes sandboxes with at least one chat turn.
 * @param bouncedSandboxes sandboxes created that never sent a turn.
 * @param engagementRate engaged/created as a 0-1 fraction, {@code null} when
 *     nothing has been created.
 * @param sandboxesWithChannelLink distinct sandboxes with any channel link row
 *     (PENDING, LINKED or REVOKED); {@code linkedChannelLinks} narrows that to
 *     links that actually completed pairing.
 */
public record DemoEngagementMetrics(
    long totalMessages,
    Double averageMessagesPerSandbox,
    long engagedSandboxes,
    long bouncedSandboxes,
    Double engagementRate,
    Long imagesGenerated,
    Long sandboxesWithImages,
    String imageMetricsUnavailableReason,
    Long channelLinks,
    Long linkedChannelLinks,
    Long sandboxesWithChannelLink,
    String channelMetricsUnavailableReason
) {

    /** Base metrics with both optional sub-features still unresolved. */
    static DemoEngagementMetrics ofMessages(
        long totalMessages, Double averageMessagesPerSandbox,
        long engagedSandboxes, long bouncedSandboxes, Double engagementRate
    ) {
        return new DemoEngagementMetrics(
            totalMessages, averageMessagesPerSandbox, engagedSandboxes, bouncedSandboxes, engagementRate,
            null, null, null, null, null, null, null
        );
    }

    /** Either the image figures or the reason there aren't any - never both, never neither. */
    DemoEngagementMetrics withImages(Long imagesGenerated, Long sandboxesWithImages, String unavailableReason) {
        return new DemoEngagementMetrics(
            totalMessages, averageMessagesPerSandbox, engagedSandboxes, bouncedSandboxes, engagementRate,
            imagesGenerated, sandboxesWithImages, unavailableReason,
            channelLinks, linkedChannelLinks, sandboxesWithChannelLink, channelMetricsUnavailableReason
        );
    }

    /** Same contract as {@link #withImages} for the channel-link side. */
    DemoEngagementMetrics withChannels(
        Long channelLinks, Long linkedChannelLinks, Long sandboxesWithChannelLink, String unavailableReason
    ) {
        return new DemoEngagementMetrics(
            totalMessages, averageMessagesPerSandbox, engagedSandboxes, bouncedSandboxes, engagementRate,
            imagesGenerated, sandboxesWithImages, imageMetricsUnavailableReason,
            channelLinks, linkedChannelLinks, sandboxesWithChannelLink, unavailableReason
        );
    }
}
