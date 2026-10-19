package com.agenticai.core.omnichannel.email;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure header parsing + classification for inbound email, used by {@link
 * EmailReplyTracker}. No I/O so it is trivially unit-testable.
 *
 * <p>Classification precedence: {@link Kind#BOUNCE} (a DSN / MAILER-DAEMON
 * message) beats {@link Kind#AUTO_REPLY} (out-of-office, vacation responders,
 * bulk/auto-submitted mail) beats {@link Kind#REPLY} (a human answered). Both
 * standardised signals (RFC 3464 DSN content type, RFC 3834 {@code
 * Auto-Submitted}) and the widespread de-facto ones ({@code X-Autoreply},
 * {@code Precedence}, subject prefixes) are honoured.
 */
public final class EmailReplyClassifier {

    public enum Kind { REPLY, AUTO_REPLY, BOUNCE }

    /** The headers this classifier looks at; any may be null. */
    public record Headers(
        String inReplyTo, String references, String autoSubmitted, String xAutoreply,
        String precedence, String contentType, String subject, String from, String returnPath
    ) {
    }

    private static final Pattern MESSAGE_ID = Pattern.compile("<([^<>\\s]+)>");
    private static final Pattern PERMANENT_STATUS = Pattern.compile("(?<![\\d.])5\\.\\d{1,3}\\.\\d{1,3}(?![\\d.])");

    private EmailReplyClassifier() {
    }

    /**
     * Message-IDs referenced by {@code In-Reply-To} then {@code References}
     * (angle brackets stripped, de-duplicated, In-Reply-To first - it names the
     * direct parent, which is the best match for a bulk-send message).
     */
    public static List<String> referencedMessageIds(String inReplyTo, String references) {
        Set<String> ids = new LinkedHashSet<>();
        collect(inReplyTo, ids);
        collect(references, ids);
        return new ArrayList<>(ids);
    }

    private static void collect(String header, Set<String> out) {
        if (header == null || header.isBlank()) {
            return;
        }
        Matcher m = MESSAGE_ID.matcher(header);
        boolean any = false;
        while (m.find()) {
            out.add(m.group(1));
            any = true;
        }
        if (!any) {
            // Tolerate a bare, bracket-less id (some MTAs strip them).
            String bare = header.trim();
            if (!bare.contains(" ")) {
                out.add(bare);
            }
        }
    }

    /** Strips angle brackets/whitespace so an id compares equal to the stored form. */
    public static String normalizeMessageId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static Kind classify(Headers h) {
        String contentType = lower(h.contentType());
        String from = lower(h.from());
        String subject = lower(h.subject()).trim();
        String returnPath = h.returnPath() == null ? "" : h.returnPath().trim();

        boolean dsn = contentType.contains("multipart/report") || contentType.contains("message/delivery-status")
            || contentType.contains("report-type=delivery-status");
        boolean daemon = from.contains("mailer-daemon") || from.contains("postmaster@");
        boolean nullReturnPath = "<>".equals(returnPath);
        boolean bounceSubject = subject.startsWith("undeliverable") || subject.contains("delivery status notification")
            || subject.startsWith("mail delivery failed") || subject.startsWith("returned mail")
            || subject.startsWith("failure notice") || subject.contains("delivery has failed");
        if (dsn || daemon || (nullReturnPath && bounceSubject) || (bounceSubject && daemon)) {
            return Kind.BOUNCE;
        }

        String autoSubmitted = lower(h.autoSubmitted()).trim();
        boolean autoSubmittedSet = !autoSubmitted.isEmpty() && !autoSubmitted.startsWith("no");
        boolean xAuto = h.xAutoreply() != null && !h.xAutoreply().isBlank();
        String precedence = lower(h.precedence()).trim();
        boolean precedenceAuto = precedence.equals("auto_reply") || precedence.equals("auto-reply")
            || precedence.equals("bulk") || precedence.equals("junk");
        boolean autoSubject = subject.startsWith("automatic reply") || subject.startsWith("auto:")
            || subject.startsWith("auto-reply") || subject.startsWith("autoreply") || subject.startsWith("out of office")
            || subject.startsWith("out-of-office") || subject.contains("automatic reply:");
        if (autoSubmittedSet || xAuto || precedenceAuto || autoSubject) {
            return Kind.AUTO_REPLY;
        }
        return Kind.REPLY;
    }

    /**
     * A bounce is only a reason to suppress the recipient when it is a
     * permanent failure (enhanced status {@code 5.x.x}); a {@code 4.x.x}
     * (mailbox full, greylisting) or a DSN with no readable status is left
     * alone so a transient problem cannot permanently silence a real contact.
     */
    public static boolean isPermanentBounce(String bodyText) {
        return bodyText != null && PERMANENT_STATUS.matcher(bodyText).find();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
