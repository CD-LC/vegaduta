package com.agenticai.core.tenancy.demo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups over V128's {@code control_plane.demo_channel_link}. Every finder
 * here is backed by an index created in that migration - there is no scan on
 * the inbound-webhook path, which matters because that path is reachable by
 * anyone on the internet who can find the demo bot.
 */
public interface DemoChannelLinkRepository extends JpaRepository<DemoChannelLink, UUID> {

    /** The {@code /try} page's poll: everything this sandbox has been offered or bound to. */
    List<DemoChannelLink> findByDemoSessionId(UUID demoSessionId);

    /** Unique by V128's {@code uq_demo_channel_link_session_channel}. */
    Optional<DemoChannelLink> findByDemoSessionIdAndChannel(UUID demoSessionId, DemoChannelType channel);

    /**
     * Pairing-code redemption. Unique while the code is live (codes are
     * cleared on redeem), so this can only ever match one sandbox.
     */
    Optional<DemoChannelLink> findByChannelAndPairingCode(DemoChannelType channel, String pairingCode);

    /**
     * The inbound hot path: which live sandbox owns this Telegram chat.
     * Filtered on state so a revoked binding never resurrects - a chat whose
     * sandbox was disconnected gets the "pair again" prompt, not somebody
     * else's agent.
     */
    Optional<DemoChannelLink> findByChannelAndExternalIdAndState(
        DemoChannelType channel, String externalId, DemoChannelLinkState state
    );
}
