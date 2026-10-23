package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatExportRenderer.Conversation;
import com.agenticai.core.agents.chatport.ChatExportRenderer.Format;
import com.agenticai.core.agents.chatport.ChatExportRenderer.Message;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.CallerSessionIds;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chat export: a VegaDuta conversation as Markdown / JSON / plain text, plus the same for the chats
 * a user imported ({@link ImportedChat}) one at a time or all at once.
 *
 * <p>A live conversation is the Redis session list ({@link SessionMemoryService#getFullHistory}) -
 * the same read the transcript UIs use. Its authorisation is the one the sibling session endpoints
 * already apply (rewind, cancel): RUNNER on the agent, answering 404 otherwise. That is not enough
 * on its own, because the session id is client-chosen and the history is keyed by it alone: the
 * export therefore also requires the caller's tenant and subject to match the owner record {@link
 * SessionMemoryService} writes on a session's first append (404 otherwise, the same answer as "no
 * such session"). Sessions started before that record existed are not exportable. The record binds
 * tenant and user, not the agent: a user's own session is exportable through any agent they may
 * run. A turn carries only role, text and time (no tool-call metadata is stored), so there is
 * nothing to summarise on that side; attachments appear by name from the notes the chat service
 * appends to a user turn.
 *
 * <p>Live-session export is gated by {@code app.chat-export.enabled}; exporting imported chats
 * rides on {@code app.chat-import.enabled}. Both default to false. No model call, no writes except
 * one audit entry.
 */
@Service
public class ChatExportService {

    private static final Logger log = LoggerFactory.getLogger(ChatExportService.class);

    static final String AUDIT_ACTION = "CHAT_EXPORT";
    static final int MAX_SESSION_ID_CHARS = 200;
    static final int BULK_PAGE = 50;
    static final String NO_SESSION_MESSAGE =
            "No conversation was found for this session. Sessions are only kept for a limited"
                    + " time.";

    /** The bytes to send, its content type and a safe download filename. */
    public record ExportFile(byte[] bytes, String contentType, String filename) {}

    private final AgentService agentService;
    private final SessionMemoryService sessionMemoryService;
    private final ImportedChatRepository repository;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final boolean exportEnabled;
    private final boolean importEnabled;
    private final long bulkMaxChars;

    @Autowired
    public ChatExportService(
            AgentService agentService,
            SessionMemoryService sessionMemoryService,
            ImportedChatRepository repository,
            ObjectProvider<AuditLogService> auditLogService,
            @Value("${app.chat-export.enabled:false}") boolean exportEnabled,
            @Value("${app.chat-import.enabled:false}") boolean importEnabled,
            @Value("${app.chat-export.bulk-max-chars:20000000}") long bulkMaxChars) {
        this.agentService = agentService;
        this.sessionMemoryService = sessionMemoryService;
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.exportEnabled = exportEnabled;
        this.importEnabled = importEnabled;
        this.bulkMaxChars = Math.max(1000L, bulkMaxChars);
    }

    // ---- a live VegaDuta conversation ----

    public ExportFile exportSession(UUID agentId, String sessionId, String formatName) {
        if (!exportEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat export is disabled.");
        }
        Format format = Format.parse(formatName);
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        String sid = validSessionId(sessionId);
        // The session id is client-chosen, so RUNNER on the agent proves nothing about the
        // session. History and its owner record live under the CALLER-scoped id (SEC-004), and
        // only the tenant + subject that started it may read it back; anyone else (and any
        // session with no owner record) gets the same 404 as a session that does not exist.
        String memorySessionId =
                CallerSessionIds.scopedForCurrentCaller(agentId, sid)
                        .filter(this::callerOwnsSession)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, NO_SESSION_MESSAGE));
        List<ChatTurn> turns = sessionMemoryService.getFullHistory(memorySessionId);
        if (turns == null || turns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NO_SESSION_MESSAGE);
        }
        Conversation conversation = toConversation(sid, agent.getName(), turns);
        Instant now = Instant.now();
        String body = ChatExportRenderer.render(format, List.of(conversation), now);
        recordAudit(
                "Exported a chat session (" + turns.size() + " turns, " + format.extension() + ").",
                "ChatSession",
                sid);
        return new ExportFile(
                body.getBytes(StandardCharsets.UTF_8),
                format.contentType(),
                ChatExportRenderer.filename("vegaduta-chat", now, format));
    }

    static Conversation toConversation(String sessionId, String agentName, List<ChatTurn> turns) {
        List<Message> messages = new ArrayList<>(turns.size());
        Instant first = null;
        for (ChatTurn t : turns) {
            // A record-only turn (a decided action's outcome, kept for the model) is not a message.
            if (t == null || ChatTurn.isToolRecordOnly(t)) {
                continue;
            }
            if (first == null) {
                first = t.timestamp();
            }
            String raw = t.content() == null ? "" : t.content();
            if (t.role() == MessageRole.USER) {
                ChatExportRenderer.SplitMessage split =
                        ChatExportRenderer.splitAttachmentNotes(raw);
                messages.add(
                        new Message(
                                ChatImportParser.ROLE_USER,
                                split.content(),
                                t.timestamp(),
                                split.attachments()));
            } else {
                // A failed reply is stored behind a technical marker; an export says what happened
                // in words.
                String shown =
                        ChatTurn.isFailedReply(t)
                                ? "(This reply failed: "
                                        + TranscriptView.textOf(t.role(), raw)
                                        + ")"
                                : raw;
                messages.add(
                        new Message(
                                ChatImportParser.ROLE_ASSISTANT, shown, t.timestamp(), List.of()));
            }
        }
        List<String> notes = new ArrayList<>();
        if (turns.size() >= SessionMemoryService.MAX_STORED_ENTRIES) {
            notes.add(
                    "A session keeps only its most recent "
                            + SessionMemoryService.MAX_STORED_ENTRIES
                            + " messages, so the start of a very long conversation may be"
                            + " missing.");
        }
        String name = agentName == null || agentName.isBlank() ? null : agentName;
        String title = name == null ? "Conversation" : "Conversation with " + name;
        return new Conversation(
                sessionId, "vegaduta", title, name, first, null, messages, false, notes);
    }

    private boolean callerOwnsSession(String sessionId) {
        return TenantContext.current()
                .map(
                        ctx ->
                                sessionMemoryService.isOwnedBy(
                                        sessionId, ctx.getTenantId(), ctx.getSubject()))
                .orElse(false);
    }

    private static String validSessionId(String sessionId) {
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > MAX_SESSION_ID_CHARS
                || sessionId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException("That session id is not valid.");
        }
        return sessionId;
    }

    // ---- imported chats ----

    public ExportFile exportImportedChat(String subject, UUID id, String formatName) {
        requireImportEnabled();
        Format format = Format.parse(formatName);
        ImportedChat chat =
                repository
                        .findByIdAndOwnerSubject(id, subject)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Imported chat not found."));
        Instant now = Instant.now();
        Conversation conversation = toConversation(chat);
        String body = ChatExportRenderer.render(format, List.of(conversation), now);
        recordAudit(
                "Exported one imported chat (" + format.extension() + ").",
                "ImportedChat",
                id.toString());
        return new ExportFile(
                body.getBytes(StandardCharsets.UTF_8),
                format.contentType(),
                ChatExportRenderer.filename(chat.getTitle(), now, format));
    }

    /**
     * Every imported chat of the caller in one file. Capped at {@code
     * app.chat-export.bulk-max-chars} message characters: past that the file stops and says so in
     * its first conversation's notes (export the rest one by one). This is the "take everything
     * with you" counterpart of the tenant data export; live sessions are not listed because the
     * session store cannot enumerate a user's sessions.
     */
    public ExportFile exportAllImported(String subject, String formatName) {
        requireImportEnabled();
        Format format = Format.parse(formatName);
        long total = repository.countByOwnerSubject(subject);
        List<Conversation> conversations = new ArrayList<>();
        long chars = 0;
        boolean cut = false;
        int page = 0;
        outer:
        while (true) {
            List<ImportedChat> batch =
                    repository.findFullByOwnerSubjectOrderByImportedAtDesc(
                            subject, PageRequest.of(page, BULK_PAGE));
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (ImportedChat chat : batch) {
                Conversation c = toConversation(chat);
                long n = 0;
                for (Message m : c.messages()) {
                    n += m.content() == null ? 0 : m.content().length();
                }
                if (!conversations.isEmpty() && chars + n > bulkMaxChars) {
                    cut = true;
                    break outer;
                }
                conversations.add(c);
                chars += n;
            }
            if (batch.size() < BULK_PAGE) {
                break;
            }
            page++;
        }
        if (cut && !conversations.isEmpty()) {
            Conversation head = conversations.get(0);
            List<String> notes = new ArrayList<>(head.notes() == null ? List.of() : head.notes());
            notes.add(
                    "This export stopped after "
                            + conversations.size()
                            + " of "
                            + total
                            + " imported chats because it reached the size limit. Export the"
                            + " remaining chats one at a time.");
            conversations.set(
                    0,
                    new Conversation(
                            head.id(),
                            head.source(),
                            head.title(),
                            head.agentName(),
                            head.createdAt(),
                            head.importedAt(),
                            head.messages(),
                            head.truncated(),
                            notes));
        }
        Instant now = Instant.now();
        String body = ChatExportRenderer.render(format, conversations, now);
        recordAudit(
                "Exported "
                        + conversations.size()
                        + " imported chats ("
                        + format.extension()
                        + ").",
                "ImportedChat",
                null);
        return new ExportFile(
                body.getBytes(StandardCharsets.UTF_8),
                format.contentType(),
                ChatExportRenderer.filename("vegaduta-imported-chats", now, format));
    }

    static Conversation toConversation(ImportedChat chat) {
        ChatPortDtos.ImportedChatView view = ChatImportService.view(chat);
        List<Message> messages = new ArrayList<>(view.messages().size());
        for (ChatPortDtos.ImportedMessage m : view.messages()) {
            messages.add(new Message(m.role(), m.content(), m.timestamp(), m.attachments()));
        }
        return new Conversation(
                chat.getSourceId() != null ? chat.getSourceId() : String.valueOf(chat.getId()),
                chat.getSource(),
                chat.getTitle(),
                null,
                chat.getSourceCreatedAt(),
                chat.getImportedAt(),
                messages,
                chat.isTruncated(),
                List.of());
    }

    private void requireImportEnabled() {
        if (!importEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat import is disabled.");
        }
    }

    /** One audit entry per export (counts only, never chat text). Never throws. */
    private void recordAudit(String detail, String targetType, String targetId) {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(
                        subject, AUDIT_ACTION, targetType, targetId, AuditOutcome.SUCCESS, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Chat export: audit entry could not be written", e);
        }
    }
}
