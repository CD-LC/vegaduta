package com.agenticai.core.omnichannel.email;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The PRD's "Mail-Checking Agent" (Section 3.2: "scans unread threads, summarizes context against
 * .md files, drafts replies") made real - X-style {@link TenantSchemaIterator} polling, since IMAP
 * has no webhook.
 *
 * <p><b>Poll interval</b>: 5 minutes, matching the PRD's own "polls an IMAP server every 5 minutes"
 * spec exactly.
 *
 * <p><b>Unread-only, mark-as-read</b>: searches {@code INBOX} for {@code UNSEEN} messages (the
 * PRD's own "scans unread threads" language), and flags each {@code \Seen} right after queuing its
 * reply so a message is never processed twice - no separate UID-tracking column needed.
 *
 * <p><b>Multi-replica safe</b> since the {@link PgAdvisoryJobLock} wrap: the mark-as-read dedupe
 * above only protects against reprocessing across <em>ticks</em>, not against two replicas fetching
 * the same UNSEEN batch concurrently (both would see the message unread and both would reply). With
 * the lock, exactly one replica polls per tick.
 */
@Component
public class EmailInboundPollingJob {

    private static final long FIXED_DELAY_MS = 300_000L;
    private static final long INITIAL_DELAY_MS = 30_000L;
    private static final Logger log = LoggerFactory.getLogger(EmailInboundPollingJob.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final String REQUESTED_BY_PREFIX = "system:email-poll:";
    private static final String JOB_LOCK_NAME = "email-inbound-poll";

    private final PgAdvisoryJobLock jobLock;
    private final TenantSchemaIterator schemaIterator;
    private final EmailCredentialRepository credentialRepository;
    private final EncryptionService encryptionService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final HumanApprovalGateService gateService;
    private final WorkflowEventPublisher workflowEventPublisher;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final InboundMessageDedupService dedupService;
    // Reply/bounce detection for bulk_email sends. Null only in unit tests built
    // with the legacy constructor below - tracking is then skipped.
    private final EmailReplyTracker replyTracker;

    public EmailInboundPollingJob(
            PgAdvisoryJobLock jobLock,
            TenantSchemaIterator schemaIterator,
            EmailCredentialRepository credentialRepository,
            EncryptionService encryptionService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService) {
        this(
                jobLock,
                schemaIterator,
                credentialRepository,
                encryptionService,
                channelSessionService,
                agentChatService,
                gateService,
                workflowEventPublisher,
                workflowTriggerNodeService,
                workflowTriggerPort,
                dedupService,
                null);
    }

    @Autowired
    public EmailInboundPollingJob(
            PgAdvisoryJobLock jobLock,
            TenantSchemaIterator schemaIterator,
            EmailCredentialRepository credentialRepository,
            EncryptionService encryptionService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            InboundMessageDedupService dedupService,
            EmailReplyTracker replyTracker) {
        this.replyTracker = replyTracker;
        this.jobLock = jobLock;
        this.schemaIterator = schemaIterator;
        this.credentialRepository = credentialRepository;
        this.encryptionService = encryptionService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.gateService = gateService;
        this.workflowEventPublisher = workflowEventPublisher;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.dedupService = dedupService;
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void pollAllTenants() {
        // Note this holds the advisory lock's pinned connection for the whole
        // multi-tenant IMAP sweep (minutes, worst case) - accepted: the sweep
        // is exactly the critical section, and losing replicas skip at DEBUG
        // rather than queueing behind it.
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::pollAllTenantsExclusively);
    }

    private void pollAllTenantsExclusively() {
        schemaIterator.forEachActiveTenantSchema(
                schema -> {
                    Optional<EmailCredential> credential =
                            credentialRepository
                                    .findFirstByOrderByCreatedAtAsc()
                                    .filter(c -> c.getStatus() == EmailCredentialStatus.ACTIVE);
                    credential.ifPresent(this::pollMailbox);
                });
    }

    private void pollMailbox(EmailCredential credential) {
        boolean useSsl = credential.isUseSsl();
        Properties props = new Properties();
        String protocol = useSsl ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "15000");

        String password = encryptionService.decrypt(credential.getEncryptedImapPassword());
        try {
            // The stored host is re-checked on every poll: the allow-list may have changed since it
            // was saved, and the addresses it resolves to are pinned for this connection.
            ImapEndpoint.apply(props, protocol, credential.getImapHost(), credential.getImapPort());
        } catch (RuntimeException e) {
            log.warn(
                    "Skipping email poll for mailbox {}: {}",
                    com.agenticai.core.security.LogMasking.email(credential.getImapUsername()),
                    e.getMessage());
            return;
        }
        Session session = Session.getInstance(props);
        try (Store store = session.getStore(protocol)) {
            store.connect(
                    credential.getImapHost(),
                    credential.getImapPort(),
                    credential.getImapUsername(),
                    password);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            try {
                Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                for (Message message : unseen) {
                    try {
                        handleMessage(message);
                    } catch (Exception e) {
                        // Stack trace matters more here than anywhere else in this job: the
                        // finally below marks the message \Seen regardless, so this log line is
                        // the ONLY artefact that will ever exist for a customer email the
                        // platform permanently dropped. getMessage() alone (often null on an NPE
                        // deep in handleMessage) makes that undiagnosable after the fact.
                        log.warn(
                                "Failed to process inbound email - marking it \\Seen anyway, it"
                                        + " will not be retried",
                                e);
                    } finally {
                        // Mark \Seen even on failure - an unparsable message would
                        // otherwise be retried forever every 5 minutes.
                        message.setFlag(Flags.Flag.SEEN, true);
                    }
                }
            } finally {
                inbox.close(false);
            }
            credentialRepository.save(touchPolled(credential));
        } catch (Exception e) {
            log.warn(
                    "Email poll failed for mailbox {}: {}",
                    com.agenticai.core.security.LogMasking.email(credential.getImapUsername()),
                    e.getMessage());
        }
    }

    private EmailCredential touchPolled(EmailCredential credential) {
        credential.markPolled();
        return credential;
    }

    private void handleMessage(Message message) throws Exception {
        InternetAddress[] from = (InternetAddress[]) message.getFrom();
        if (from == null || from.length == 0) {
            return;
        }
        String fromAddress = from[0].getAddress();
        String subject = message.getSubject() == null ? "(no subject)" : message.getSubject();
        String body = extractText(message);

        // Reply/bounce detection for bulk_email sends runs BEFORE the empty-body
        // gate (an HTML-only reply still counts as a reply) and before the
        // agent auto-reply below. Bounces and auto-responders are never fed to
        // the agent: answering a MAILER-DAEMON or an out-of-office is how mail
        // loops start.
        EmailReplyClassifier.Kind kind = trackReply(message, fromAddress, subject, body);
        if (kind == EmailReplyClassifier.Kind.BOUNCE
                || kind == EmailReplyClassifier.Kind.AUTO_REPLY) {
            log.info(
                    "Inbound email from {} classified {} - not routed to an agent",
                    maskRecipient(fromAddress),
                    kind);
            return;
        }
        if (body == null || body.isBlank()) {
            return;
        }

        // Same inbound-dedup gate as the webhook channels, claimed BEFORE
        // any processing. The mark-as-\Seen guard only holds if the \Seen
        // flag write actually lands: a connection drop after handleMessage
        // but before setFlag (or a crash mid-batch) leaves the message
        // UNSEEN, and the next tick would re-reply to it. Keyed on the RFC
        // 5322 Message-ID header - the provider-native id every real MTA
        // stamps, stable across re-fetches, unlike IMAP UIDs (per-mailbox,
        // reset by UIDVALIDITY changes) or any content hash. A message with
        // no Message-ID passes through per tryClaim's null contract - dedup
        // is never a reason to drop a message that has no id to dedup on.
        // TenantSchemaIterator has already set TenantContext here, so the
        // Redis key is correctly tenant-scoped.
        String[] messageIdHeader = message.getHeader("Message-ID");
        String providerMessageId =
                messageIdHeader != null && messageIdHeader.length > 0 ? messageIdHeader[0] : null;
        if (!dedupService.tryClaim(ChannelType.EMAIL, providerMessageId)) {
            log.info(
                    "Ignoring duplicate inbound email {} from {}",
                    providerMessageId,
                    maskRecipient(fromAddress));
            return;
        }

        log.info("Inbound email from {}", maskRecipient(fromAddress));
        ChannelSession session =
                channelSessionService.resolveOrCreate(ChannelType.EMAIL, fromAddress, subject);

        if (!session.isPaired()) {
            handleUnpairedSender(session, fromAddress, subject);
            return;
        }

        // Channel-based trigger selection (#422), extended to EMAIL. IMAP
        // polling has no live HTTP response to reply into (unlike CALL), so
        // this follows TwilioSmsWebhookController's simpler contextless
        // triggerAsync overload rather than Telegram/WhatsApp's - a workflow
        // reply, like this job's own agent-chat reply, still goes out
        // asynchronously via EmailChannelAdapter/SMTP either way, so no
        // reply-to context is needed here for that to keep working. Same "a
        // bound workflow takes over from the channel's own default
        // agent-chat behavior" precedence every other channel here uses.
        // Agent-event-trigger fix (post-blackout audit): fires unconditionally
        // now, independent of a workflow binding on this channel - see
        // AgentEventTriggerDispatcher's javadoc, "three honesty items" #1.
        workflowEventPublisher.publishNewRun(
                WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED,
                Map.of(
                        "channel",
                        "email",
                        "fromMasked",
                        maskRecipient(fromAddress),
                        "bodyLength",
                        body.length()));

        Optional<WorkflowTriggerNodeService.TriggerBinding> triggeredBinding =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.EMAIL);
        if (triggeredBinding.isPresent()) {
            if (workflowTriggerNodeService.tryAcquireTriggerSlot(
                    WorkflowTriggerChannel.EMAIL, fromAddress)) {
                log.info(
                        "Inbound email from {} routed to workflow {} (channel trigger)",
                        maskRecipient(fromAddress),
                        triggeredBinding.get().workflowId());
                workflowTriggerPort.triggerAsync(
                        triggeredBinding.get().workflowId(),
                        triggeredBinding.get().nodeId(),
                        body,
                        REQUESTED_BY_PREFIX + maskRecipient(fromAddress));
            } else {
                log.info(
                        "Inbound email from {} skipped - workflow trigger rate limit exceeded",
                        maskRecipient(fromAddress));
            }
            return;
        }

        ChatTurnRequest chatTurn =
                ChatTurnRequest.builder(
                                session.getAgentId(),
                                session.getSessionId(),
                                session.getExternalId(),
                                body)
                        .build();
        TurnContext turnContext =
                TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
        // Stage 4 (Chat file upload) added trailing attachmentKey/attachmentMimeType
        // params to AgentChatService.chat() - null/null here, same reasoning as
        // every other omnichannel inbound path (see DiscordWebhookController's comment).
        String reply =
                agentChatService
                        .chat(turnContext, chatTurn)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .block();

        if (reply != null && !reply.isBlank()) {
            String replySubject =
                    subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                            ? subject
                            : "Re: " + subject;
            sendReplyThroughApprovalGate(
                    fromAddress,
                    replySubject,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId()) + reply);
        }
    }

    private EmailReplyClassifier.Kind trackReply(
            Message message, String fromAddress, String subject, String body) {
        try {
            EmailReplyClassifier.Headers headers =
                    new EmailReplyClassifier.Headers(
                            header(message, "In-Reply-To"),
                            header(message, "References"),
                            header(message, "Auto-Submitted"),
                            header(message, "X-Autoreply") != null
                                    ? header(message, "X-Autoreply")
                                    : header(message, "X-Autorespond"),
                            header(message, "Precedence"),
                            header(message, "Content-Type"),
                            subject,
                            fromAddress,
                            header(message, "Return-Path"));
            if (replyTracker == null) {
                return EmailReplyClassifier.classify(headers);
            }
            return replyTracker.track(headers, fromAddress, body).kind();
        } catch (Exception e) {
            log.warn("Email reply tracking failed - continuing with normal inbound handling", e);
            return EmailReplyClassifier.Kind.REPLY;
        }
    }

    private static String header(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : String.join(" ", values);
    }

    private void handleUnpairedSender(ChannelSession session, String fromAddress, String subject) {
        if (session.isPairingNoticeSent()) {
            log.info(
                    "Ignoring email from still-unpaired sender {} (already notified)",
                    maskRecipient(fromAddress));
            return;
        }
        String notice =
                "Thanks for reaching out. For security, an admin needs to approve this conversation"
                        + " before I can respond. Your pairing code is "
                        + session.getPairingCode()
                        + ".";
        String replySubject =
                subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")
                        ? subject
                        : "Re: " + subject;
        sendReplyThroughApprovalGate(
                fromAddress,
                replySubject,
                channelSessionService.consumeAiDisclosurePrefix(session.getId()) + notice);
        channelSessionService.markPairingNoticeSent(session.getId());
    }

    private void sendReplyThroughApprovalGate(String to, String subject, String replyText) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new EmailChannelAdapter.SendPayload(to, subject, replyText));
        } catch (Exception e) {
            log.error("Failed to serialize email auto-reply payload for {}", maskRecipient(to), e);
            return;
        }
        gateService.evaluate(
                EmailChannelAdapter.ACTION_TYPE,
                "Auto-reply email to " + maskRecipient(to),
                payloadJson,
                REQUESTED_BY_PREFIX + to);
    }

    /**
     * Best-effort plain-text extraction - handles a simple text/plain message or the first
     * text/plain part of a multipart one.
     */
    private String extractText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? null : content.toString();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("text/plain")) {
                    Object content = bodyPart.getContent();
                    return content == null ? null : content.toString();
                }
            }
            // No plain-text part found - fall back to the first nested multipart's text, if any.
            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);
                if (bodyPart.isMimeType("multipart/*")) {
                    String nested = extractText(bodyPart);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        return null;
    }

    private static String maskRecipient(String to) {
        if (to == null || to.length() < 4) {
            return "***";
        }
        return "***" + to.substring(to.length() - 4);
    }
}
