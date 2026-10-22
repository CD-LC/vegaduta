package com.agenticai.core.platform;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platform-admin "Newsletter / news broadcast" feature - a super-admin writes a subject + HTML body
 * once, picks which tenants (or "all"), and this sends it to each selected tenant's admin, using
 * each tenant's own per-tenant opt-in ({@code control_plane.tenant.news_opt_in}, captured at signup
 * by a separate workstream - this service only ever reads it) as the hard gate: an opted-out tenant
 * is never mailed, even if its id is explicitly passed in {@code recipientTenantIds}.
 *
 * <p>Deliberately queries {@code control_plane.tenant}/{@code control_plane.app_user} with a raw
 * {@link JdbcTemplate} query rather than going through {@code Tenant}'s JPA entity - {@code
 * news_opt_in} is a column owned by a different, separately-landing workstream (see this class's
 * own callers/javadoc for that context), and a raw column read avoids coupling this feature's
 * compile-time correctness to exactly when that other migration lands or whether {@code
 * Tenant.java} ever grows a mapped field for it. Only {@link NewsletterRecipient#tenantId}/{@code
 * name}/{@code adminEmail}/{@code newsOptIn} are read this way - nothing here writes to {@code
 * control_plane.tenant} or {@code control_plane.app_user}.
 *
 * <p>{@code sent}/{@code skipped} counts and {@code JavaMailSender} usage: a tenant admin's email
 * is the {@code control_plane.app_user} row with {@code role = 'TENANT_ADMIN'} for that tenant
 * (earliest one, if somehow more than one exists), same "admin of record" convention {@code
 * TenantContext}/Keycloak provisioning already uses elsewhere in this codebase. {@link
 * JavaMailSender} is injected via {@link ObjectProvider} rather than a hard constructor dependency:
 * {@code spring-boot-starter-mail} only creates that bean once {@code spring.mail.host} is set (see
 * deploy/.env's {@code KEYCLOAK_SMTP_*} / core's own {@code spring.mail.*} wiring, owned by a
 * separate integration step) - without this, this module's Spring context would fail to start in
 * any environment where mail isn't configured yet (tests, a fresh local boot). When unavailable,
 * every candidate is counted as {@code skipped} rather than throwing, and a warning is logged once
 * per send.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class NewsletterService {

    private static final Logger log = LoggerFactory.getLogger(NewsletterService.class);

    private static final String RECIPIENTS_SQL =
            "SELECT t.id AS tenant_id, t.name AS tenant_name, t.news_opt_in AS news_opt_in,"
                + " au.email AS admin_email FROM control_plane.tenant t LEFT JOIN LATERAL (  "
                + " SELECT email FROM control_plane.app_user   WHERE tenant_id = t.id AND role ="
                + " 'TENANT_ADMIN'   ORDER BY created_at ASC LIMIT 1 ) au ON true WHERE t.status ="
                + " 'ACTIVE' ORDER BY t.name";

    private static final String INSERT_CAMPAIGN_SQL =
            "INSERT INTO control_plane.newsletter_campaign (id, subject, sent_count, created_at,"
                    + " created_by) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final String appPublicUrl;
    private final String unsubscribeMailto;

    public NewsletterService(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.username:no-reply@example.com}") String fromAddress,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.newsletter.unsubscribe-mailto:}") String unsubscribeMailto) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.appPublicUrl = appPublicUrl.replaceAll("/+$", "");
        // List-Unsubscribe needs a reachable mailto target; fall back to the sender address.
        this.unsubscribeMailto =
                (unsubscribeMailto == null || unsubscribeMailto.isBlank())
                        ? fromAddress
                        : unsubscribeMailto.strip();
    }

    /**
     * {@code GET /api/platform/newsletter/recipients} - every active tenant, opted-in or not (see
     * this class's javadoc).
     */
    public List<NewsletterRecipient> listRecipients() {
        return jdbcTemplate.query(
                RECIPIENTS_SQL,
                (rs, rowNum) ->
                        new NewsletterRecipient(
                                UUID.fromString(rs.getString("tenant_id")),
                                rs.getString("tenant_name"),
                                rs.getString("admin_email"),
                                rs.getBoolean("news_opt_in")));
    }

    /**
     * {@code POST /api/platform/newsletter} - see class javadoc for the opt-in gate and the {@link
     * JavaMailSender}-unavailable fallback. {@code sentBy} is the caller's validated-JWT subject
     * (same {@code TenantContext}-sourced actor id every other platform-admin write action in this
     * codebase already threads through), logged on the {@code control_plane.newsletter_campaign}
     * row this writes exactly once per call.
     */
    public NewsletterSendResult send(NewsletterSendRequest request, String sentBy) {
        if (request == null || request.subject() == null || request.subject().isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (request.bodyHtml() == null || request.bodyHtml().isBlank()) {
            throw new IllegalArgumentException("bodyHtml is required");
        }

        List<UUID> requestedIds = request.recipientTenantIds();
        Set<UUID> scope =
                (requestedIds == null || requestedIds.isEmpty()) ? null : Set.copyOf(requestedIds);

        List<NewsletterRecipient> candidates = new ArrayList<>();
        for (NewsletterRecipient recipient : listRecipients()) {
            if (scope == null || scope.contains(recipient.tenantId())) {
                candidates.add(recipient);
            }
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn(
                    "Newsletter send skipped for all {} candidate tenant(s) - JavaMailSender is not"
                            + " configured (spring.mail.host unset). Subject: '{}'",
                    candidates.size(),
                    request.subject());
        }

        int sent = 0;
        int skipped = 0;
        for (NewsletterRecipient candidate : candidates) {
            if (!candidate.newsOptIn()
                    || candidate.adminEmail() == null
                    || candidate.adminEmail().isBlank()) {
                skipped++;
                continue;
            }
            if (mailSender == null) {
                skipped++;
                continue;
            }
            try {
                sendOne(
                        mailSender,
                        candidate.adminEmail(),
                        request.subject(),
                        request.bodyHtml(),
                        request.bodyText());
                sent++;
            } catch (MailException | jakarta.mail.MessagingException e) {
                log.warn(
                        "Failed to send newsletter to tenant {} ({})",
                        candidate.tenantId(),
                        com.agenticai.core.security.LogMasking.email(candidate.adminEmail()),
                        e);
                skipped++;
            }
        }

        logCampaign(request.subject(), sent, sentBy);
        return new NewsletterSendResult(sent, skipped);
    }

    /**
     * One recipient, one {@code multipart/alternative} message: a {@code text/plain} part first and
     * the branded {@code text/html} part last (clients pick the last part they can render, so HTML
     * wins where supported and the plain text is what text-only clients and spam filters read).
     * {@code bodyText} is used verbatim when the admin supplied one; otherwise it is derived from
     * {@code bodyHtml} by {@link #htmlToPlainText}. Same {@code List-Unsubscribe} / {@code
     * List-Unsubscribe-Post} pair as {@code BulkEmailSender}, here with a {@code mailto:} target
     * (the newsletter has no per-recipient unsubscribe URL - opt-out is a support-handled change to
     * {@code control_plane.tenant.news_opt_in}).
     */
    void sendOne(
            JavaMailSender mailSender,
            String toAddress,
            String subject,
            String bodyHtml,
            String bodyText)
            throws jakarta.mail.MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(toAddress);
        helper.setSubject(subject);

        String plain =
                (bodyText == null || bodyText.isBlank()) ? derivePlainText(bodyHtml) : bodyText;
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plain, "UTF-8");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(wrapBranded(bodyHtml), "text/html; charset=UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);
        message.setContent(alternative);

        message.setHeader(
                "List-Unsubscribe", "<mailto:" + unsubscribeMailto + "?subject=unsubscribe>");
        message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("email-send");
        mailSender.send(message);
    }

    /**
     * Fallback text/plain body: the stripped HTML plus the same opt-in footer {@link #wrapBranded}
     * renders.
     */
    private static String derivePlainText(String bodyHtml) {
        String text = htmlToPlainText(bodyHtml);
        if (bodyHtml.toLowerCase(Locale.ROOT).contains("<html")) {
            return text;
        }
        return text
                + "\n\n"
                + "--\n"
                + "You received this because your workspace opted in to VegaD\u016bta product"
                + " news.\n"
                + "VegaD\u016bta - AI staff for your business";
    }

    private static final Pattern DROP_BLOCKS =
            Pattern.compile("(?is)<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern ANCHOR =
            Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?i)<li\\b[^>]*>");
    private static final Pattern LIST_ITEM_END = Pattern.compile("(?i)</li\\s*>");
    private static final Pattern BLOCK_END =
            Pattern.compile(
                    "(?i)</(p|div|tr|table|ul|ol|h[1-6]|blockquote|section|article|header|footer)\\s*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern HORIZONTAL_WS = Pattern.compile("[ \\t\\u00a0]+");
    private static final Pattern LINE_EDGES = Pattern.compile("(?m)^[ \\t]+|[ \\t]+$");
    private static final Pattern BLANK_RUNS = Pattern.compile("\\n{3,}");

    /**
     * Best-effort HTML to plain text for the {@code text/plain} alternative: drops {@code
     * <head>}/{@code <style>}/{@code <script>} and comments, rewrites {@code <a href="U">T</a>} as
     * {@code T (U)} (just {@code U} when the text already is the URL), turns line breaks, list
     * items and block ends into newlines, strips every other tag, decodes the common named and all
     * numeric entities, then collapses runs of whitespace. Package-private for the unit test.
     */
    static String htmlToPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String s = DROP_BLOCKS.matcher(html).replaceAll("");
        s = COMMENTS.matcher(s).replaceAll("");
        s = rewriteAnchors(s);
        s = LINE_BREAK.matcher(s).replaceAll("\n");
        s = LIST_ITEM.matcher(s).replaceAll("\n- ");
        s = LIST_ITEM_END.matcher(s).replaceAll("");
        s = BLOCK_END.matcher(s).replaceAll("\n\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        s = HORIZONTAL_WS.matcher(s).replaceAll(" ");
        s = LINE_EDGES.matcher(s).replaceAll("");
        s = BLANK_RUNS.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    private static String rewriteAnchors(String s) {
        Matcher m = ANCHOR.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String href = m.group(2).strip();
            String label = ANY_TAG.matcher(m.group(3)).replaceAll("").strip();
            String replacement;
            if (label.isEmpty() || label.equalsIgnoreCase(href)) {
                replacement = href;
            } else if (href.isEmpty() || href.startsWith("#")) {
                replacement = label;
            } else {
                replacement = label + " (" + href + ")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decodeEntities(String s) {
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String decoded;
            try {
                int cp =
                        ref.startsWith("x") || ref.startsWith("X")
                                ? Integer.parseInt(ref.substring(1), 16)
                                : Integer.parseInt(ref);
                decoded =
                        Character.isValidCodePoint(cp)
                                ? new String(Character.toChars(cp))
                                : m.group();
            } catch (NumberFormatException e) {
                decoded = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(out);
        return out.toString()
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&ndash;", "\u2013")
                .replace("&mdash;", "\u2014")
                .replace("&hellip;", "\u2026")
                .replace("&copy;", "\u00a9")
                .replace("&amp;", "&");
    }

    /**
     * Admin-authored newsletter bodies are HTML fragments; wrap them in the platform's standard
     * branded email shell (same table-based/inline-CSS look, deliberately, as {@code
     * DemoTranscriptEmailService#buildHtml} and the Keycloak email theme in {@code
     * deploy/keycloak/themes/agentic-ai/email/html/}: winged-emblem logo at an absolute URL + the
     * two-tone "VegaDūta" wordmark) so every mail the platform sends reads as one product. A body
     * that is already a full document (contains {@code <html}) is sent untouched — nesting two HTML
     * documents breaks rendering in most clients.
     */
    private String wrapBranded(String bodyHtml) {
        if (bodyHtml.toLowerCase(Locale.ROOT).contains("<html")) {
            return bodyHtml;
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
</head>
<body style="margin:0;padding:0;background-color:#f4f3fb;font-family:Arial,Helvetica,sans-serif;">
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f3fb;padding:32px 16px;">
<tr>
<td align="center">
<table role="presentation" width="480" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;width:100%%;background-color:#ffffff;border-radius:12px;border:1px solid #e7e4f7;">
<tr>
<td style="padding:28px 32px 20px 32px;text-align:center;border-bottom:1px solid #efeaff;">
<img src="%2$s/logo-mark.png" alt="" width="38" height="28" style="display:inline-block;vertical-align:middle;border:0;margin:0 10px 0 0;">
<img src="%2$s/vegaduta-wordmark.png" alt="VegaDūta" width="132" height="35" style="display:inline-block;vertical-align:middle;border:0;height:35px;width:132px;">
</td>
</tr>
<tr>
<td style="padding:28px 32px 24px 32px;color:#33303d;font-size:15px;line-height:1.6;">
%s
</td>
</tr>
<tr>
<td style="padding:20px 32px 28px 32px;border-top:1px solid #efeaff;text-align:center;">
<div style="font-size:12px;color:#a09cae;line-height:1.6;">
You received this because your workspace opted in to <span style="font-family:'Yatra One',Georgia,'Times New Roman',serif;font-weight:400;color:#33303d;letter-spacing:0.2px;">Vega<span style="color:#f97316;">Dūta</span></span> product news.<br>
<span style="font-family:'Yatra One',Georgia,'Times New Roman',serif;font-weight:400;color:#33303d;letter-spacing:0.2px;">Vega<span style="color:#f97316;">Dūta</span></span> — AI staff for your business
</div>
</td>
</tr>
</table>
</td>
</tr>
</table>
</body>
</html>
"""
                .formatted(bodyHtml, appPublicUrl);
    }

    private void logCampaign(String subject, int sentCount, String createdBy) {
        jdbcTemplate.update(
                INSERT_CAMPAIGN_SQL,
                UUID.randomUUID(),
                subject,
                sentCount,
                Timestamp.from(Instant.now()),
                createdBy);
    }
}
