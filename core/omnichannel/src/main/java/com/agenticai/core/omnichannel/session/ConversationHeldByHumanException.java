package com.agenticai.core.omnichannel.session;

/**
 * Thrown by {@code ChannelSessionService#resolveOrCreate} when an inbound
 * message arrives on a session a human operator currently holds
 * ({@link HandoffState#HUMAN}) - the live-handoff enforcement gate
 * (migration V214). By the time this is thrown the inbound message HAS been
 * recorded to the operator's transcript and the session's last-activity
 * fields HAVE been updated; what this exception stops is everything that
 * would otherwise happen next: the pairing notice, the channel workflow
 * trigger, and the agent's LLM reply.
 *
 * <p><b>Why enforcement lives inside {@code resolveOrCreate} at all.</b>
 * The scout-identified ideal check point is one line below the pairing gate
 * in each of the 10 inbound entry points ({@code
 * MetaWhatsAppWebhookController} etc.) - but those controllers belong to
 * other workstreams' scope, while {@code resolveOrCreate} is the single
 * shared call every one of those entry points already makes, immediately
 * before the pairing gate and everything downstream. Throwing from here is
 * behaviourally equivalent to the ideal point for every session that can be
 * held: a held session is always paired ({@code OperatorInboxService}
 * refuses to take over an unpaired one, and {@link ChannelSession#unpair()}
 * releases any hold), so the pairing-gate branch this "skips past" could
 * never have fired anyway.
 *
 * <p><b>Fail-closed, stated explicitly because it inverts the house
 * default.</b> Optional LLM enhancements here fail open
 * ({@code ReflexionService} et al degrade to no-op); this gate must not. If
 * the agent's right to speak cannot be established, the agent stays silent -
 * a human took this conversation over precisely because the agent should not
 * be talking, and "the check hiccuped so the bot barged into a live human
 * conversation" is the worst outcome on the board. Mechanically that posture
 * is free: the state is a column on the row {@code resolveOrCreate} has
 * already loaded in the same transaction, so there is no separate lookup to
 * fail. This is the {@code EncryptionService} posture, not the
 * {@code ReflexionService} one.
 *
 * <p><b>What each entry point does with it</b> (all verified at authoring
 * time, 2026-08-18): nine of the ten inbound paths wrap their per-message
 * processing in {@code catch (RuntimeException)} / {@code catch (Exception)}
 * that logs a warning and returns 2xx (webhooks) or continues the batch
 * (Email/X pollers) - Meta requires a fast 200 regardless, so this exception
 * is invisible to the sending platform there. {@code
 * TwilioWebhookController} is the one path with no local catch: it
 * propagates to {@code GlobalExceptionHandler}, which is why this class
 * extends {@link IllegalArgumentException} - that type has a named 400
 * handler, so Twilio sees a clean 400 with this message instead of falling
 * into the {@code Exception} catch-all, which would return an opaque 500 AND
 * record a {@code PlatformIssue} on every message of every held conversation
 * (pure triage noise for a deliberate, expected state). A dedicated
 * {@code GlobalExceptionHandler} mapping (returning 200-shaped TwiML for the
 * Twilio path) is the cleaner end state, but that file belongs to another
 * scope - flagged as a cross-workstream follow-up rather than reached for.
 *
 * <p>{@code VoiceRelayWebSocketHandler} also calls {@code resolveOrCreate},
 * and its catch would end the call with a spoken apology - which is exactly
 * why VOICE sessions are barred from ever entering {@link HandoffState#HUMAN}
 * at the service layer, making this exception unreachable on that path by
 * construction rather than by luck.
 *
 * <p>The message text doubles as the warning line those catch blocks log, so
 * it says plainly that this is expected suppression, not a failure.
 */
public class ConversationHeldByHumanException extends IllegalArgumentException {

    private final HandoffState state;

    public ConversationHeldByHumanException(ChannelSession session) {
        super("Conversation on " + session.getChannel() + " session " + session.getId()
            + " is held by a human operator (" + maskSubject(session.getTakenOverBy())
            + ") - automated reply suppressed. This is the expected effect of a live takeover"
            + " while it is active, not an error; the inbound message was still recorded"
            + " to the operator transcript.");
        this.state = session.getHandoffState();
    }

    public HandoffState getState() {
        return state;
    }

    /**
     * The operator's Keycloak subject is an internal identifier; this message
     * ends up in webhook warning logs and (on the Twilio path) in an HTTP 400
     * body, so it gets the same last-4 masking every webhook controller
     * already applies to phone numbers.
     */
    private static String maskSubject(String subject) {
        if (subject == null || subject.length() < 4) {
            return "***";
        }
        return "***" + subject.substring(subject.length() - 4);
    }
}
