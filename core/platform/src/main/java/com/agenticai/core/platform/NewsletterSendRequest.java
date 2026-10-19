package com.agenticai.core.platform;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/platform/newsletter} request body.
 *
 * <p>{@code recipientTenantIds} null or empty means "every active, opted-in tenant" - a non-empty
 * list scopes the send to just those tenant ids, still filtered down to {@code news_opt_in = true}
 * (see {@link NewsletterService#send}; passing a tenant id that hasn't opted in is a no-op skip,
 * not an error).
 *
 * <p>{@code bodyText} is the optional plain-text alternative for the {@code multipart/alternative}
 * send. When null or blank the service derives one from {@code bodyHtml} (tags stripped, entities
 * decoded, link URLs kept in parentheses after the link text); an admin-authored one is always
 * preferred because it reads better in text-only clients and spam filters compare the two parts.
 */
public record NewsletterSendRequest(
        String subject, String bodyHtml, String bodyText, List<UUID> recipientTenantIds) {}
