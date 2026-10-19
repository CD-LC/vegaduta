package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns V128's two tables: which messenger chats are bound to which throwaway
 * sandbox, and the small display buffer that lets a message typed on a phone
 * appear on the {@code /try} tab still open on a laptop.
 *
 * <h2>Where the trust boundary actually is</h2>
 * Two very different callers reach this class, and they are trusted very
 * differently:
 * <ul>
 *   <li>{@code DemoChannelController} - the browser holding the demo token.
 *       It may ask for a code for ITS OWN session and nothing else; every
 *       method it reaches takes a resolved {@link DemoSession}, never a
 *       session id off the wire.</li>
 *   <li>The inbound Telegram webhook - anyone on the internet who can find
 *       the bot. It supplies a pairing code and a chat id, and
 *       {@link #redeem} is the ONLY way in. That method is where the abuse
 *       thinking lives.</li>
 * </ul>
 *
 * <h2>Abuse posture for the inbound side, stated plainly</h2>
 * <ol>
 *   <li>A code is single-use: {@link DemoChannelLink#markLinked} clears it in
 *       the same transaction that binds the chat, so a code shoulder-surfed
 *       off a screenshot is already spent.</li>
 *   <li>One chat is bound to at most one live sandbox. Redeeming a second
 *       code from the same Telegram account revokes the first binding here,
 *       and V128's {@code uq_demo_channel_link_live_external} partial unique
 *       index is the backstop if two redeems race. Without this, one Telegram
 *       account could hold several sandboxes open and multiply its free
 *       inference.</li>
 *   <li>Turns spent over Telegram come out of the SAME
 *       {@code demo_session.message_count} budget as turns typed on the
 *       page - the bridge calls {@code DemoSessionService#recordMessage}, it
 *       does not keep a second allowance. That, not the rate limiter, is the
 *       hard bound on what a paired chat can cost.</li>
 *   <li>Expiry needs no separate job: a binding to an expired session stops
 *       answering because the session check runs on every inbound turn, and
 *       the underlying tenant is suspended by
 *       {@code DemoSessionExpiryJob} regardless.</li>
 * </ol>
 */
@Service
public class DemoChannelLinkService {

    private static final Logger log = LoggerFactory.getLogger(DemoChannelLinkService.class);

    /**
     * How many collision retries when minting a code. Four characters over a
     * 28-symbol alphabet is ~614k combinations against a live-code population
     * that is realistically in the tens, so a single collision is already
     * improbable and three in a row is not a thing that happens - the retry
     * exists so the improbable case is a retry rather than a 500.
     */
    private static final int CODE_MINT_ATTEMPTS = 5;

    /** Hard ceiling on what a poll can return, so a long-lived tab cannot ask for an unbounded page. */
    private static final int MAX_MESSAGES_PER_POLL = 50;

    private final DemoChannelLinkRepository linkRepository;
    private final DemoChannelMessageRepository messageRepository;
    private final int mirrorRetainedMessages;

    public DemoChannelLinkService(
        DemoChannelLinkRepository linkRepository,
        DemoChannelMessageRepository messageRepository,
        @Value("${app.demo.channels.mirror-retained-messages:40}") int mirrorRetainedMessages
    ) {
        this.linkRepository = linkRepository;
        this.messageRepository = messageRepository;
        this.mirrorRetainedMessages = Math.max(2, mirrorRetainedMessages);
    }

    /** What {@link #redeem} did, so the webhook can pick the right sentence to send back. */
    public enum RedeemOutcome {
        /** Bound. {@code link} is the freshly-linked row. */
        LINKED,
        /** Already bound to this exact chat - a visitor who tapped Start twice. Idempotent, not an error. */
        ALREADY_LINKED,
        /** No live code matched. Covers typos, spent codes, and codes from a sandbox that has since gone. */
        UNKNOWN_CODE,
        /** The code was real but its sandbox has ended. */
        SESSION_ENDED
    }

    public record RedeemResult(RedeemOutcome outcome, DemoChannelLink link, DemoSession session) {
    }

    // ------------------------------------------------------------------
    // Browser-facing (always for the caller's OWN resolved session)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DemoChannelLink> linksFor(DemoSession session) {
        return linkRepository.findByDemoSessionId(session.getId());
    }

    @Transactional(readOnly = true)
    public Optional<DemoChannelLink> linkFor(DemoSession session, DemoChannelType channel) {
        return linkRepository.findByDemoSessionIdAndChannel(session.getId(), channel);
    }

    /**
     * Issues (or re-uses) the pairing code this sandbox shows on {@code /try}.
     *
     * <p>Idempotent by design - a visitor who reloads the page, or whose
     * browser fires the effect twice under StrictMode, must see the SAME code
     * rather than invalidating the one already on their phone screen. An
     * already-linked sandbox gets its existing link back untouched; a revoked
     * one is re-armed in place (V128 keeps one row per session per channel).
     */
    @Transactional
    public DemoChannelLink issuePairingCode(DemoSession session, DemoChannelType channel) {
        Optional<DemoChannelLink> existing = linkRepository.findByDemoSessionIdAndChannel(session.getId(), channel);
        if (existing.isPresent()) {
            DemoChannelLink link = existing.get();
            if (link.isLinked() || link.getPairingCode() != null) {
                return link;
            }
            link.reissue(mintCode(channel));
            return link;
        }
        return linkRepository.saveAndFlush(
            new DemoChannelLink(session.getId(), channel, mintCode(channel), Instant.now())
        );
    }

    /**
     * Records that this sandbox used a no-transport affordance - today only
     * {@link DemoChannelType#WHATSAPP_PREVIEW}. Creates the row on first use
     * so "offered" and "activated" stay distinguishable in the funnel, and
     * bumps the per-channel turn counter.
     *
     * <p>No pairing code is minted: there is nothing to pair with. See
     * {@code DemoChannelController} for why WhatsApp is a preview and not a
     * connection.
     */
    @Transactional
    public DemoChannelLink recordPreviewUse(DemoSession session, DemoChannelType channel) {
        Instant now = Instant.now();
        DemoChannelLink link = linkRepository.findByDemoSessionIdAndChannel(session.getId(), channel)
            .orElseGet(() -> linkRepository.saveAndFlush(new DemoChannelLink(session.getId(), channel, null, now)));
        link.markLinked(null, null, now);
        link.recordMessage(now);
        return link;
    }

    /** Visitor-initiated disconnect from {@code /try}, and the same path the bot's {@code /stop} takes. */
    @Transactional
    public void revoke(DemoSession session, DemoChannelType channel) {
        linkRepository.findByDemoSessionIdAndChannel(session.getId(), channel)
            .ifPresent(link -> link.markRevoked(Instant.now()));
    }

    // ------------------------------------------------------------------
    // Inbound-webhook facing (untrusted input)
    // ------------------------------------------------------------------

    /**
     * Could this text be a pairing code at all? Lets the inbound webhook tell
     * "the visitor pasted VD-7F3K" apart from "the visitor said hello" and
     * answer each appropriately - replying "I couldn't match that code" to
     * someone who just said hello is the kind of small wrongness that makes a
     * product feel like a machine.
     *
     * <p>Exposed rather than the normaliser itself, so the actual code format
     * stays an implementation detail of this package.
     */
    public static boolean looksLikePairingCode(String raw) {
        return DemoPairingCodes.normalize(raw) != null;
    }

    /** Finds a pairing code embedded in free text (WhatsApp), or null. See {@link DemoPairingCodes#extract}. */
    public static String extractPairingCode(String text) {
        return DemoPairingCodes.extract(text);
    }

    /** The webhook's hot path: which live sandbox owns this chat, if any. */
    @Transactional(readOnly = true)
    public Optional<DemoChannelLink> findLiveBinding(DemoChannelType channel, String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.length() > 128) {
            return Optional.empty();
        }
        return linkRepository.findByChannelAndExternalIdAndState(channel, externalId, DemoChannelLinkState.LINKED);
    }

    /**
     * Binds {@code externalId} to whichever sandbox issued {@code rawCode}.
     *
     * @param rawCode exactly what the human typed or what rode in on the
     *     {@code t.me} deep link - normalised here, so callers never have to
     *     care about case, a missing {@code VD-}, or a trailing full stop.
     * @param sessionLookup how to load the session behind a matched code.
     *     Passed in rather than injected because {@code DemoSessionService}
     *     lives on the other side of this class (it depends on the repository
     *     layer this one also uses, and wiring it in the other direction would
     *     be a cycle) - and because it keeps this method honestly testable.
     */
    @Transactional
    public RedeemResult redeem(
        DemoChannelType channel,
        String rawCode,
        String externalId,
        String peerLabel,
        java.util.function.Function<UUID, Optional<DemoSession>> sessionLookup
    ) {
        String code = DemoPairingCodes.normalize(rawCode);
        if (code == null || externalId == null || externalId.isBlank() || externalId.length() > 128) {
            return new RedeemResult(RedeemOutcome.UNKNOWN_CODE, null, null);
        }

        Optional<DemoChannelLink> found = linkRepository.findByChannelAndPairingCode(channel, code);
        if (found.isEmpty()) {
            // Could be a typo, a spent code, or a sandbox that has been
            // revoked. Deliberately one outcome for all three: telling an
            // anonymous caller which of those it was would turn this into a
            // code oracle for very little user benefit.
            return new RedeemResult(RedeemOutcome.UNKNOWN_CODE, null, null);
        }

        DemoChannelLink link = found.get();
        Optional<DemoSession> session = sessionLookup.apply(link.getDemoSessionId());
        if (session.isEmpty()) {
            return new RedeemResult(RedeemOutcome.UNKNOWN_CODE, null, null);
        }
        if (session.get().isExpired(Instant.now())) {
            return new RedeemResult(RedeemOutcome.SESSION_ENDED, link, session.get());
        }
        if (externalId.equals(link.getExternalId()) && link.isLinked()) {
            return new RedeemResult(RedeemOutcome.ALREADY_LINKED, link, session.get());
        }

        // One chat, one live sandbox - see the class javadoc. Revoking the
        // previous binding BEFORE claiming the new one is what keeps this
        // side of V128's partial unique index satisfied.
        Instant now = Instant.now();
        linkRepository.findByChannelAndExternalIdAndState(channel, externalId, DemoChannelLinkState.LINKED)
            .ifPresent(previous -> {
                log.info("Demo channel {}: rebinding an external chat from sandbox {} to sandbox {}",
                    channel, previous.getDemoSessionId(), link.getDemoSessionId());
                previous.markRevoked(now);
            });
        linkRepository.flush();

        link.markLinked(externalId, truncate(peerLabel, 64), now);
        try {
            linkRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // The index caught a concurrent double-redeem. Nothing useful to
            // tell the sender beyond "try again" - and the retry will find
            // the code already spent, which is the correct end state.
            log.warn("Demo channel {} redeem lost a race on the live-external index", channel);
            return new RedeemResult(RedeemOutcome.UNKNOWN_CODE, null, null);
        }
        return new RedeemResult(RedeemOutcome.LINKED, link, session.get());
    }

    @Transactional
    public void recordChannelTurn(UUID linkId) {
        linkRepository.findById(linkId).ifPresent(link -> link.recordMessage(Instant.now()));
    }

    @Transactional
    public void revokeById(UUID linkId) {
        linkRepository.findById(linkId).ifPresent(link -> link.markRevoked(Instant.now()));
    }

    // ------------------------------------------------------------------
    // The display buffer
    // ------------------------------------------------------------------

    /**
     * Appends one line to the {@code /try} mirror and prunes the tail.
     *
     * <p>Best-effort on purpose: this is a nicety for a browser tab that may
     * not even be open. A failure here must never cost the sender their
     * actual reply, so it is swallowed and logged rather than thrown into the
     * webhook's turn.
     */
    @Transactional
    public void mirror(UUID demoSessionId, DemoChannelType channel, DemoChannelMessage.Direction direction, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            messageRepository.save(new DemoChannelMessage(
                demoSessionId, channel, direction, truncate(body, 4000), Instant.now()
            ));
            prune(demoSessionId);
        } catch (RuntimeException e) {
            log.warn("Could not mirror a demo {} message for sandbox {} - the reply itself is unaffected",
                channel, demoSessionId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<DemoChannelMessage> messagesSince(DemoSession session, DemoChannelType channel, Instant since) {
        Instant floor = since == null ? Instant.EPOCH : since;
        return messageRepository.findSince(session.getId(), channel, floor, PageRequest.of(0, MAX_MESSAGES_PER_POLL));
    }

    private void prune(UUID demoSessionId) {
        List<Instant> cutoff = messageRepository.findCreatedAtDescending(
            demoSessionId, PageRequest.of(mirrorRetainedMessages, 1)
        );
        if (!cutoff.isEmpty()) {
            messageRepository.deleteOlderThan(demoSessionId, cutoff.get(0));
        }
    }

    private String mintCode(DemoChannelType channel) {
        for (int attempt = 0; attempt < CODE_MINT_ATTEMPTS; attempt++) {
            String candidate = DemoPairingCodes.newCode();
            if (linkRepository.findByChannelAndPairingCode(channel, candidate).isEmpty()) {
                return candidate;
            }
        }
        // Unreachable in practice (see CODE_MINT_ATTEMPTS). Returning the last
        // candidate anyway would risk a constraint violation the caller cannot
        // do anything useful with; failing here is caught by the controller and
        // becomes "we couldn't set that up just now".
        throw new IllegalStateException("Could not mint a unique demo pairing code for " + channel);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
