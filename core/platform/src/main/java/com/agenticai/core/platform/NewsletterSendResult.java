package com.agenticai.core.platform;

/**
 * {@code POST /api/platform/newsletter} response - a bare summary of what
 * just happened. {@code sent} is the number of tenant-admin mailboxes the
 * message was actually handed to {@code JavaMailSender} for (not a delivery
 * guarantee - SMTP is fire-and-forget from this call's point of view);
 * {@code skipped} covers every other considered tenant: not opted in, no
 * resolvable admin email, mail not configured, or a send that threw.
 */
public record NewsletterSendResult(int sent, int skipped) {
}
