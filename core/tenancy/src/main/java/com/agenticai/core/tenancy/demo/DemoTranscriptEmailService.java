package com.agenticai.core.tenancy.demo;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Email me this conversation" - {@code POST /api/demo/transcript-email}'s engine, and the
 * sandbox's SOFT capture: the transcript is genuinely useful to the visitor (their drafted replies
 * and price lists leave the throwaway tab), and in exchange the platform learns an address it can
 * send the existing pre-expiry reminder to. Both halves are stated on the /try page; neither is
 * smuggled.
 *
 * <h2>Where the transcript actually comes from, honestly</h2>
 *
 * There is no single conversation of record reachable from this module. The demo web chat's history
 * lives in the short-lived Redis session memory ({@code agentic:session:<sessionId>} - written by
 * {@code core/agents}' SessionMemoryService, 24h TTL), keyed by a client-generated id only the
 * browser holds; the channel bridge keeps a small pruned mirror buffer in {@code
 * control_plane.demo_channel_message}; and the per-tenant-schema records are out of this slice's
 * reach on purpose. So: when the browser passes its chat {@code sessionId}, the Redis history is
 * used (the real, full-fidelity web conversation, up to {@link #MAX_TURNS} recent turns and
 * whatever the TTL has kept); otherwise the mirror buffer is the fallback. The email says in as
 * many words that it is the RECENT part of the conversation, because that is all either source can
 * promise.
 *
 * <h2>The Redis read, security posture stated plainly</h2>
 *
 * {@code agentic:session:*} is a shared namespace - authenticated Playground chats live under the
 * same prefix - and this endpoint is unauthenticated, so accepting a caller-supplied session id IS
 * letting an anonymous caller name a Redis key to read. Why that is acceptable here and not a data
 * hole: session ids are client-minted random UUIDs (128-bit, never listed, never derivable from
 * anything else this API returns), so possession of one is de facto proof of having been party to
 * that conversation - the same capability-url argument {@code DemoGeneratedImage} already
 * documents. The guards on top: the value must parse as a UUID (no wildcards, no crafted keys),
 * only the most recent {@link #MAX_TURNS} turns are read, and the output goes to an address the
 * caller already had to type - it is never echoed into the API response. An enumeration attempt
 * therefore burns the per-IP limiter on 2^122 odds per guess. SEC-004 (2026-09-30) removed the
 * remaining hole: the Redis key is no longer {@code agentic:session:<id>} but a digest of the
 * CALLER'S demo session (tenant schema, agent, subject) plus the id ({@link #webChatMemoryId}), so
 * even a leaked session id of another sandbox or of an authenticated Playground chat resolves to a
 * key that does not exist - only this sandbox's own conversation is ever readable here.
 *
 * <h2>Never a 5xx</h2>
 *
 * Same contract as every other demo surface: every user-reachable outcome is a {@code {sent,
 * message}} the /try page renders as a sentence. The address capture happens FIRST and survives
 * every downstream failure - a mail outage must not cost the lead.
 */
@Service
public class DemoTranscriptEmailService {

    private static final Logger log = LoggerFactory.getLogger(DemoTranscriptEmailService.class);

    /** One WARN per minute while Redis is down, not one per transcript request. */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, java.time.Duration.ofMinutes(1));

    /**
     * Duplicates SessionMemoryService's key prefix (and, since SEC-004, its scoped-id derivation -
     * see {@link #scopedSessionId}) rather than importing it: the module graph runs agents ->
     * tenancy, so this side cannot see that constant. Break glass if it ever changes there - the
     * failure mode is benign (empty history, mirror-buffer fallback) but silent.
     */
    private static final String SESSION_MEMORY_KEY_PREFIX = "agentic:session:";

    /**
     * Most recent web-chat turns included. The demo message cap is 20 turns (40 entries), so this
     * covers all of it.
     */
    private static final int MAX_TURNS = 40;

    private final DemoSessionRecorder recorder;
    private final DemoChannelMessageRepository channelMessageRepository;
    private final PlatformSettingsService platformSettings;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ObjectMapper objectMapper;
    private final String fromAddress;
    private final String appPublicUrl;
    private final int defaultMaxSendsPerSession;

    public DemoTranscriptEmailService(
            DemoSessionRecorder recorder,
            DemoChannelMessageRepository channelMessageRepository,
            PlatformSettingsService platformSettings,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            ObjectMapper objectMapper,
            @Value("${spring.mail.username:no-reply@example.com}") String fromAddress,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.demo.transcript-email.max-per-session:2}")
                    int defaultMaxSendsPerSession) {
        this.recorder = recorder;
        this.channelMessageRepository = channelMessageRepository;
        this.platformSettings = platformSettings;
        this.redisTemplateProvider = redisTemplateProvider;
        this.mailSenderProvider = mailSenderProvider;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
        this.appPublicUrl = appPublicUrl;
        this.defaultMaxSendsPerSession = defaultMaxSendsPerSession;
    }

    /**
     * What the endpoint returns, verbatim - a boolean and a sentence the /try page can render
     * as-is.
     */
    public record Result(boolean sent, String message) {}

    private record TranscriptLine(boolean fromVisitor, String body) {}

    /**
     * @param chatSessionId the browser's chat session id, or null - see {@code
     *     DemoTranscriptEmailRequest#sessionId} for why it exists. Expired sessions are
     *     deliberately still served: the transcript is a register-to-keep-this artifact, most
     *     valuable exactly when the sandbox has just lapsed (the Redis history may already be gone
     *     by then; the mirror buffer is not).
     */
    public Result send(DemoSession session, String rawEmail, String chatSessionId) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);

        // Capture FIRST, unconditionally - the address is the durable value
        // here and must survive the cap, a missing mail sender, and a bounced
        // send alike. This is also what arms the pre-expiry reminder.
        recorder.recordTranscriptEmailCapture(session.getId(), email);

        int maxSends =
                platformSettings.getInt(
                        "demo.transcript-email.max-per-session", defaultMaxSendsPerSession);
        if (session.getTranscriptEmailsSent() >= maxSends) {
            // Read from the row as loaded this request - two perfectly timed
            // concurrent calls could both pass and land maxSends+1 total.
            // Accepted: the per-IP limiter bounds the abuse case, and the cap
            // exists to stop mail loops, not to be a billing-grade counter.
            return new Result(
                    false,
                    "This conversation has already been emailed the maximum number of times.");
        }

        List<TranscriptLine> lines = collectTranscript(session, chatSessionId);
        if (lines.isEmpty()) {
            return new Result(
                    false,
                    "There's no conversation to send yet - chat with your agent first, then email"
                            + " the transcript.");
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            // spring.mail.host unset on this deployment - same ObjectProvider
            // posture (and same honest log) as NewsletterService and
            // DemoSessionExpiryJob.
            log.warn(
                    "Transcript email for demo sandbox {} skipped - no JavaMailSender configured"
                            + " (spring.mail.host unset)",
                    session.getId());
            return new Result(
                    false,
                    "Email sending isn't available right now. Your address is saved - your sandbox"
                            + " keeps working.");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("Your VegaDūta sandbox conversation");
            helper.setText(buildHtml(session, lines), true);
            mailSender.send(message);
            recorder.recordTranscriptEmailSent(session.getId());
            log.info(
                    "Sent transcript email for demo sandbox {} ({} lines)",
                    session.getId(),
                    lines.size());
            return new Result(true, "Sent! Check " + email + " for your conversation.");
        } catch (jakarta.mail.MessagingException | RuntimeException e) {
            // MailException is a RuntimeException; MessagingException comes
            // from the helper. Either way: logged, allowance NOT spent (the
            // counter only moves on an accepted send), visitor gets a
            // sentence, never a stack trace.
            log.warn("Transcript email failed for demo sandbox {}", session.getId(), e);
            return new Result(
                    false,
                    "We couldn't send the email just now. Your address is saved - please try again"
                            + " in a moment.");
        }
    }

    // ---------------------------------------------------------------------

    private List<TranscriptLine> collectTranscript(DemoSession session, String chatSessionId) {
        List<TranscriptLine> web = readWebHistory(session, chatSessionId);
        if (!web.isEmpty()) {
            return web;
        }
        // Fallback: the channel bridge's pruned mirror buffer - only populated
        // for sandboxes that linked Telegram / tried the WhatsApp preview, and
        // only the most recent handful, but better than an empty email.
        return channelMessageRepository
                .findByDemoSessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(
                        m ->
                                new TranscriptLine(
                                        m.getDirection() == DemoChannelMessage.Direction.IN,
                                        m.getBody()))
                .toList();
    }

    private List<TranscriptLine> readWebHistory(DemoSession session, String chatSessionId) {
        UUID parsed = parseUuidOrNull(chatSessionId);
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (parsed == null || redis == null || session.getAgentId() == null) {
            return List.of();
        }
        // SEC-004: the key is derived from the CALLER'S OWN demo session (its
        // tenant schema, its agent, its "demo:<id>" subject) plus the client
        // id - exactly the scoped id DemoChatController's turns were stored
        // under - so a session id belonging to any other conversation (another
        // sandbox, or an authenticated Playground chat) resolves to a key that
        // simply does not exist. The caller-supplied id can no longer name an
        // arbitrary Redis list.
        String memoryId = webChatMemoryId(session, parsed.toString());
        List<String> raw;
        try {
            raw = redis.opsForList().range(SESSION_MEMORY_KEY_PREFIX + memoryId, -MAX_TURNS, -1);
        } catch (RuntimeException e) {
            // Redis unreachable: fall through to the DB-backed channel mirror in collectTranscript
            // instead of failing the whole "email me the transcript" request.
            REDIS_DOWN_LOG.warn(
                    "demo-transcript-history",
                    "Demo transcript: Redis session history unavailable (Redis error: "
                            + e.getClass().getSimpleName()
                            + ") - using the channel mirror instead");
            return List.of();
        }
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<TranscriptLine> lines = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                JsonNode turn = objectMapper.readTree(json);
                String content = turn.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                // Same shape core/agents' ChatTurn serialises: role is
                // "USER"/"ASSISTANT". Anything unrecognised renders as the
                // agent side rather than being dropped - a lost label is
                // better than a lost line.
                lines.add(new TranscriptLine("USER".equals(turn.path("role").asText()), content));
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                log.warn("Skipping unreadable turn while building a demo transcript email", e);
            }
        }
        return lines;
    }

    /**
     * The scoped session-memory id the demo web chat of {@code session} stores {@code
     * clientSessionId}'s turns under: {@code DemoChatController} calls {@code
     * AgentChatService#chat} inside {@code DemoSessionService#inDemoTenantContext} (tenant id = the
     * sandbox's schema) with the sandbox's seeded agent and the {@code "demo:" + session id}
     * subject.
     */
    public static String webChatMemoryId(DemoSession session, String clientSessionId) {
        return scopedSessionId(
                session.getTenantSchema(),
                session.getAgentId(),
                DemoSessionService.DEMO_ACTOR_PREFIX + session.getId(),
                clientSessionId);
    }

    /**
     * Byte-for-byte copy of {@code SessionMemoryService#scopedSessionId} (core/agents - not
     * importable from this module, same reason the key prefix above is duplicated). {@code
     * SessionMemoryScopeTest} in core/agents asserts the two produce identical ids; if they ever
     * diverge the transcript silently falls back to the channel mirror buffer (benign, but wrong).
     */
    public static String scopedSessionId(
            String tenantId, UUID agentId, String principal, String clientSessionId) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String part :
                    new String[] {
                        tenantId,
                        agentId == null ? null : agentId.toString(),
                        principal,
                        clientSessionId
                    }) {
                byte[] bytes =
                        (part == null ? "" : part)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "c1:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * UUID-or-nothing gate on the caller-supplied Redis key component - see the class javadoc's
     * security section.
     */
    private UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------

    /**
     * Table-based markup, inline CSS only, no remote assets - the same constraints (and
     * deliberately the same look: #f4f3fb canvas, white card, #7c5cff VegaDūta wordmark header,
     * Hindi tagline footer) as the Keycloak email theme's templates in {@code
     * deploy/keycloak/themes/agentic-ai/email/html/}, so every mail the platform sends reads as one
     * product.
     */
    private String buildHtml(DemoSession session, List<TranscriptLine> lines) {
        long daysLeft = session.daysRemaining(Instant.now());
        boolean lapsed = session.isExpired(Instant.now());
        String keepLine =
                lapsed
                        ? "Your sandbox has ended, but everything you built is kept - create a free"
                                + " account to carry on where you left off."
                        : (daysLeft <= 1
                                ? "Your sandbox ends soon. Create a free account to keep this agent"
                                        + " and carry on where you left off."
                                : "Your sandbox is live for "
                                        + daysLeft
                                        + " more days. Create a free account any time to keep this"
                                        + " agent.");

        StringBuilder rows = new StringBuilder();
        for (TranscriptLine line : lines) {
            String speaker = line.fromVisitor() ? "You" : "Agent";
            String speakerColor = line.fromVisitor() ? "#33303d" : "#7c5cff";
            rows.append("<tr><td style=\"padding:0 0 14px 0;\">")
                    .append("<div style=\"font-size:12px;font-weight:700;color:")
                    .append(speakerColor)
                    .append(";margin:0 0 2px 0;\">")
                    .append(speaker)
                    .append("</div>")
                    .append(
                            "<div style=\"font-size:14px;color:#33303d;line-height:1.55;white-space:pre-wrap;\">")
                    .append(escapeHtml(line.body()))
                    .append("</div></td></tr>");
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Your VegaDūta sandbox conversation</title>
</head>
<body style="margin:0;padding:0;background-color:#f4f3fb;font-family:Arial,Helvetica,sans-serif;">
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f3fb;padding:32px 16px;">
<tr>
<td align="center">
<table role="presentation" width="480" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;width:100%%;background-color:#ffffff;border-radius:12px;border:1px solid #e7e4f7;">
<tr>
<td style="padding:28px 32px 20px 32px;text-align:center;border-bottom:1px solid #efeaff;">
<img src="%3$s/logo-mark.png" alt="" width="38" height="28" style="display:inline-block;vertical-align:middle;border:0;margin:0 10px 0 0;">
<img src="%3$s/vegaduta-wordmark.png" alt="VegaDūta" width="132" height="35" style="display:inline-block;vertical-align:middle;border:0;height:35px;width:132px;">
</td>
</tr>
<tr>
<td style="padding:28px 32px 8px 32px;">
<p style="margin:0 0 6px 0;font-size:15px;color:#33303d;font-weight:700;">Your sandbox conversation</p>
<p style="margin:0 0 20px 0;font-size:13px;color:#6b6775;line-height:1.6;">This is the recent part of your conversation with your sandbox agent - the excerpt we keep for the sandbox, not necessarily every message since the start.</p>
<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">%s</table>
</td>
</tr>
<tr>
<td style="padding:8px 32px 28px 32px;">
<p style="margin:0 0 16px 0;font-size:14px;color:#33303d;line-height:1.6;">%s</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0">
<tr>
<td style="border-radius:8px;background-color:#7c5cff;">
<a href="%s/signup" target="_blank" style="display:inline-block;padding:12px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">Create a free account</a>
</td>
</tr>
</table>
</td>
</tr>
<tr>
<td style="padding:20px 32px 28px 32px;border-top:1px solid #efeaff;text-align:center;">
<div style="font-size:12px;color:#a09cae;line-height:1.6;">
You received this because this address was entered in a <span style="font-family:'Yatra One',Georgia,'Times New Roman',serif;font-weight:400;color:#33303d;letter-spacing:0.2px;">Vega<span style="color:#f97316;">Dūta</span></span> sandbox. No account was created and you are not subscribed to anything.<br>
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
                .formatted(rows.toString(), escapeHtml(keepLine), appPublicUrl);
    }

    /**
     * The transcript is anonymous-visitor-typed text going into an HTML email - unescaped it is
     * stored-XSS into whatever renders the mail. Standard five, plus the visitor's line breaks
     * survive via the {@code white-space:pre-wrap} on the containing div rather than {@code <br>}
     * injection.
     */
    private String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
