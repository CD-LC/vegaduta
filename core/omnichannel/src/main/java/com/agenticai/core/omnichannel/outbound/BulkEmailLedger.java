package com.agenticai.core.omnichannel.outbound;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * The email channel's records a bulk send needs - the suppression list, reply history, the
 * per-recipient send log and one-click unsubscribe links - for the workflow {@code bulk_email}
 * node. Kernel-owned (ADR-0011 rule a) so the node never imports the email channel; implemented in
 * {@code omnichannel.email} by {@code EmailBulkEmailLedger}.
 */
public interface BulkEmailLedger {

    /**
     * The addresses among {@code loweredEmails} that must not be mailed.
     *
     * @throws RuntimeException when the list cannot be read - callers fail closed, never treat it
     *     as empty
     */
    Set<String> suppressed(Collection<String> loweredEmails);

    /** Whether a human ever replied from this (lower-cased) address. */
    boolean hasHumanReplyFrom(String lowerEmail);

    void recordSent(
            String runId,
            String nodeId,
            String recipient,
            String subject,
            String messageId,
            String threadKey,
            Instant sentAt);

    void recordFailed(
            String runId,
            String nodeId,
            String recipient,
            String subject,
            String threadKey,
            String error);

    /** Whether one-click unsubscribe links can be signed in this deployment. */
    boolean canUnsubscribe();

    String unsubscribeUrl(String tenantId, String email);
}
