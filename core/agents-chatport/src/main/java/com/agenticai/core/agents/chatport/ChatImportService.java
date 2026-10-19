package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.chatport.ChatImportParser.ParsedChat;
import com.agenticai.core.agents.chatport.ChatImportParser.ParsedMessage;
import com.agenticai.core.agents.chatport.ChatPortDtos.Capacity;
import com.agenticai.core.agents.chatport.ChatPortDtos.Features;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedChatView;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedMessage;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedRef;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedSummary;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewConversation;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewStats;
import com.agenticai.core.agents.chatport.ChatPortDtos.SkippedChat;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos;
import com.agenticai.core.agents.memory.importer.MemoryImportParser;
import com.agenticai.core.agents.memory.importer.MemoryImportService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Bring your chat history from ChatGPT or Claude": parses the assistant's own data export and stores the
 * conversations the user picks as read-only {@link ImportedChat} records.
 *
 * <p>Deterministic and free: nothing here calls a model or a tool, writes memory, or computes an embedding. The
 * only side effects are rows in {@code imported_chat} (owned by the caller's subject) and one audit entry per
 * import. {@link #preview} writes nothing. The optional "extract memories" step ({@link #memoryCandidates}) only
 * <em>proposes</em> lines through the existing memory-import preview; saving them is a separate, explicit
 * call to {@code POST /api/memory/import} with the user's selection, under that feature's own flag and caps.
 *
 * <p>Gated by {@code app.chat-import.enabled} (default false): off = every route of
 * {@link ChatImportController} except the {@code /features} probe answers 404.
 */
@Service
public class ChatImportService {

    private static final Logger log = LoggerFactory.getLogger(ChatImportService.class);

    static final String AUDIT_ACTION = "CHAT_IMPORT";
    static final String AUDIT_ACTION_DELETE = "CHAT_IMPORT_DELETE";
    static final int SNIPPET_CHARS = 120;
    static final int TITLE_CHARS = 120;
    static final int SCREEN_INPUT_CHARS = 500;
    static final int LIST_LIMIT = 500;
    static final int MAX_MEMORY_CHATS = 50;

    static final String S_DUPLICATE = "duplicate";
    static final String S_LIMIT = "limit_reached";
    static final String S_NOT_FOUND = "not_found";

    private final ImportedChatRepository repository;
    private final ObjectProvider<MemoryImportService> memoryImport;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final boolean enabled;
    private final boolean exportEnabled;
    private final long maxBytes;
    private final int maxImportConversations;
    private final int maxStoredChats;
    private final long maxStoredBytes;
    private final int freeMaxStoredChats;
    private final long freeMaxStoredBytes;
    /** Optional (setter-injected so the constructor stays as tests build it): plan and shared-demo-tenant checks. */
    private TenantLimits tenantLimits;
    private final ChatImportParser.Limits limits;

    @Autowired
    public ChatImportService(
        ImportedChatRepository repository,
        ObjectProvider<MemoryImportService> memoryImport,
        ObjectProvider<AuditLogService> auditLogService,
        @Value("${app.chat-import.enabled:false}") boolean enabled,
        @Value("${app.chat-export.enabled:false}") boolean exportEnabled,
        @Value("${app.chat-import.max-bytes:26214400}") long maxBytes,
        @Value("${app.chat-import.max-conversations-per-import:500}") int maxImportConversations,
        @Value("${app.chat-import.max-scan-conversations:5000}") int maxScanConversations,
        @Value("${app.chat-import.max-messages-per-chat:2000}") int maxMessagesPerChat,
        @Value("${app.chat-import.max-chars-per-message:50000}") int maxCharsPerMessage,
        @Value("${app.chat-import.max-chars-per-chat:500000}") int maxCharsPerChat,
        @Value("${app.chat-import.max-chars-per-import:20000000}") long maxCharsPerImport,
        @Value("${app.chat-import.max-stored-chats:2000}") int maxStoredChats,
        @Value("${app.chat-import.max-mapping-nodes:20000}") int maxMappingNodes,
        @Value("${app.chat-import.max-zip-entries:10000}") int maxZipEntries,
        @Value("${app.chat-import.max-uncompressed-bytes:67108864}") long maxUncompressedBytes,
        @Value("${app.chat-import.max-zip-ratio:100}") int maxZipRatio,
        @Value("${app.chat-import.max-tree-nodes-per-chat:1000000}") int maxTreeNodes,
        @Value("${app.chat-import.max-tree-chars-per-chat:16000000}") long maxTreeChars,
        @Value("${app.chat-import.max-stored-bytes:262144000}") long maxStoredBytes,
        @Value("${app.chat-import.free-max-stored-chats:200}") int freeMaxStoredChats,
        @Value("${app.chat-import.free-max-stored-bytes:26214400}") long freeMaxStoredBytes
    ) {
        this.repository = repository;
        this.memoryImport = memoryImport;
        this.auditLogService = auditLogService;
        this.enabled = enabled;
        this.exportEnabled = exportEnabled;
        this.maxBytes = Math.max(1024L, maxBytes);
        this.maxImportConversations = Math.max(1, maxImportConversations);
        this.maxStoredChats = Math.max(1, maxStoredChats);
        this.maxStoredBytes = Math.max(1024L, maxStoredBytes);
        this.freeMaxStoredChats = Math.max(1, Math.min(freeMaxStoredChats, this.maxStoredChats));
        this.freeMaxStoredBytes = Math.max(1024L, Math.min(freeMaxStoredBytes, this.maxStoredBytes));
        this.limits = new ChatImportParser.Limits(
            Math.max(1, maxScanConversations),
            Math.max(1, maxMessagesPerChat),
            Math.max(1, maxCharsPerMessage),
            Math.max(1, maxCharsPerChat),
            Math.max(1L, maxCharsPerImport),
            Math.max(1, maxMappingNodes),
            Math.max(1, maxZipEntries),
            Math.max(1024L, maxUncompressedBytes),
            Math.max(1, maxZipRatio),
            Math.max(1000, maxTreeNodes),
            Math.max(1000L, maxTreeChars));
    }

    @Autowired(required = false)
    void setTenantLimits(TenantLimits tenantLimits) {
        this.tenantLimits = tenantLimits;
    }

    /**
     * The feature probe the web uses to decide which controls to show. Never 404s (it only reports the two flags);
     * import reads as off in the shared /try demo tenant, where uploads are refused.
     */
    public Features features() {
        return new Features(enabled && !sharedDemoTenant(), exportEnabled, maxBytes, maxImportConversations);
    }

    /** Uploads (preview and import) are not offered in the shared /try demo tenant: it serves every anonymous visitor. */
    private void requireNotDemoTenant() {
        if (sharedDemoTenant()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat import is not available in the demo workspace.");
        }
    }

    /** Failure-safe towards NOT storing: a failed lookup counts as the demo tenant. */
    private boolean sharedDemoTenant() {
        TenantLimits l = tenantLimits;
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (l == null || tenant == null) {
            return false;
        }
        try {
            return l.isSharedDemoTenant(tenant);
        } catch (RuntimeException e) {
            log.warn("Chat import: demo-tenant lookup failed - refusing the upload: {}", e.getMessage());
            return true;
        }
    }

    /** What one owner may keep: the FREE plan gets the smaller caps (a failed plan lookup also gets them). */
    private StorageCaps storageCaps() {
        TenantLimits l = tenantLimits;
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (l != null && tenant != null) {
            try {
                if (l.plan(tenant) != TenantPlan.FREE) {
                    return new StorageCaps(maxStoredChats, maxStoredBytes);
                }
            } catch (RuntimeException e) {
                log.warn("Chat import: plan lookup failed - applying the free caps: {}", e.getMessage());
            }
            return new StorageCaps(freeMaxStoredChats, freeMaxStoredBytes);
        }
        return new StorageCaps(maxStoredChats, maxStoredBytes);
    }

    private record StorageCaps(int chats, long bytes) {
    }

    /** 404 when the import is switched off. Public so the controller can refuse before it reads an upload. */
    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat import is disabled.");
        }
    }

    public long maxBytes() {
        return maxBytes;
    }

    // ---- preview ----

    /** Parses the file and lists what it holds. Writes nothing. */
    public PreviewResponse preview(String subject, byte[] data) {
        requireEnabled();
        requireNotDemoTenant();
        checkSize(data);
        ChatImportParser.ParseResult parsed = ChatImportParser.parse(data, limits, false, null);
        if (parsed.chats().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No importable conversations were found. Upload conversations.json (or the export .zip) from ChatGPT "
                    + "or Claude, or a chat exported from here as JSON.");
        }
        Map<String, Set<String>> existing = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        List<PreviewConversation> out = new ArrayList<>();
        for (ParsedChat chat : parsed.chats()) {
            sources.add(chat.source());
            Set<String> known = existing.computeIfAbsent(chat.source(), s -> new HashSet<>(repository.findSourceIds(subject, s)));
            boolean already = chat.sourceId() != null && known.contains(chat.sourceId());
            out.add(new PreviewConversation(chat.key(), chat.source(), safeText(chat.title(), TITLE_CHARS),
                chat.createdAt(), chat.messageCount(), safeText(chat.firstUserText(), SNIPPET_CHARS), already, chat.truncated()));
        }
        String source = sources.size() == 1 ? sources.iterator().next() : "mixed";
        return new PreviewResponse(source, out,
            new PreviewStats(parsed.totalConversations(), out.size(), parsed.skipped(), parsed.capReached()),
            new Capacity(repository.countByOwnerSubject(subject), storageCaps().chats(), maxImportConversations),
            parsed.warnings());
    }

    // ---- import ----

    /** Stores the selected conversations as read-only imported chats. No model call, memory write or embedding. */
    public ImportResponse importChats(String subject, byte[] data, List<String> ids) {
        requireEnabled();
        requireNotDemoTenant();
        checkSize(data);
        Set<String> selected = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    selected.add(id.strip());
                }
            }
        }
        if (selected.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one conversation to import.");
        }
        if (selected.size() > maxImportConversations) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Import at most " + maxImportConversations + " conversations at a time.");
        }
        long stored = repository.countByOwnerSubject(subject);
        StorageCaps caps = storageCaps();
        long storedBytes = repository.sumStoredBytes(subject);
        if (stored >= caps.chats() || storedBytes >= caps.bytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You already have " + stored + " imported chats, as much as can be kept. Delete some to import more.");
        }
        ChatImportParser.ParseResult parsed = ChatImportParser.parse(data, limits, true, selected);

        Map<String, Set<String>> existing = new LinkedHashMap<>();
        List<ImportedRef> done = new ArrayList<>();
        List<SkippedChat> skipped = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        long room = caps.chats() - stored;
        long usedBytes = storedBytes;
        for (ParsedChat chat : parsed.chats()) {
            handled.add(chat.key());
            Set<String> known = existing.computeIfAbsent(chat.source(), s -> new HashSet<>(repository.findSourceIds(subject, s)));
            if (chat.sourceId() != null && known.contains(chat.sourceId())) {
                skipped.add(new SkippedChat(chat.key(), safeText(chat.title(), TITLE_CHARS), S_DUPLICATE));
                continue;
            }
            // chat.chars() understates the stored size a little (JSON overhead, multi-byte text), so one import can
            // overshoot the byte cap slightly; the next import is then refused above.
            if (done.size() >= room || usedBytes + chat.chars() > caps.bytes()) {
                skipped.add(new SkippedChat(chat.key(), safeText(chat.title(), TITLE_CHARS), S_LIMIT));
                continue;
            }
            try {
                ImportedChat saved = repository.save(toEntity(subject, chat));
                if (chat.sourceId() != null) {
                    known.add(chat.sourceId());
                }
                usedBytes += chat.chars();
                done.add(new ImportedRef(saved.getId(), chat.key(), chat.title(), chat.messageCount()));
            } catch (DataIntegrityViolationException e) {
                // A concurrent import of the same export won the unique (owner, source, source_id) index.
                skipped.add(new SkippedChat(chat.key(), safeText(chat.title(), TITLE_CHARS), S_DUPLICATE));
            }
        }
        for (String id : selected) {
            if (!handled.contains(id)) {
                skipped.add(new SkippedChat(id, "", S_NOT_FOUND));
            }
        }
        recordAudit(subject, AUDIT_ACTION, "Imported " + done.size() + " chats (" + skipped.size() + " skipped).");
        log.info("Chat import: tenant={} imported={} skipped={}", tenantId(), done.size(), skipped.size());
        return new ImportResponse(done.size(), done, skipped, parsed.warnings());
    }

    // ---- read / delete ----

    public List<ImportedSummary> list(String subject) {
        requireEnabled();
        List<ImportedSummary> out = new ArrayList<>();
        for (ImportedChatRepository.Summary s : repository.findByOwnerSubjectOrderByImportedAtDesc(subject, PageRequest.of(0, LIST_LIMIT))) {
            out.add(new ImportedSummary(s.getId(), s.getSource(), s.getTitle(), s.getSourceCreatedAt(), s.getImportedAt(),
                s.getMessageCount(), s.isTruncated()));
        }
        return out;
    }

    public ImportedChatView get(String subject, UUID id) {
        requireEnabled();
        return view(find(subject, id));
    }

    public void delete(String subject, UUID id) {
        requireEnabled();
        ImportedChat chat = find(subject, id);
        repository.delete(chat);
        recordAudit(subject, AUDIT_ACTION_DELETE, "Deleted one imported chat.");
    }

    /** The caller's own imported chat, else 404 (same answer for "no such chat" and "someone else's"). */
    ImportedChat find(String subject, UUID id) {
        return repository.findByIdAndOwnerSubject(id, subject)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Imported chat not found."));
    }

    // ---- optional: memory candidates ----

    /**
     * Opt-in second step. Picks first-person statements from the user's side of the chosen imported chats and runs
     * them through the memory-import preview (secret / injection screening and de-duplication against existing
     * memory). Saves nothing: the web then calls the memory-import endpoint with whatever the user keeps.
     */
    public MemoryImportDtos.PreviewResponse memoryCandidates(String subject, List<UUID> chatIds) {
        requireEnabled();
        if (chatIds == null || chatIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one imported chat.");
        }
        Set<UUID> distinct = new LinkedHashSet<>(chatIds);
        if (distinct.size() > MAX_MEMORY_CHATS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at most " + MAX_MEMORY_CHATS + " chats at a time.");
        }
        MemoryImportService mem = memoryImport.getIfAvailable();
        if (mem == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory import is not available.");
        }
        MemoryImportDtos.ImportLimits memLimits = mem.limits(); // 404 when memory import is switched off
        List<String> userTexts = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (UUID id : distinct) {
            ImportedChat chat = repository.findByIdAndOwnerSubject(id, subject).orElse(null);
            if (chat == null) {
                continue;
            }
            sources.add(chat.getSource());
            for (Map<String, Object> m : chat.getMessages()) {
                if (ChatImportParser.ROLE_USER.equals(m.get("role")) && m.get("content") instanceof String s) {
                    userTexts.add(s);
                }
            }
        }
        String hint = sources.size() == 1 ? sources.iterator().next() : "other";
        List<String> lines = ChatMemoryCandidateExtractor.extract(userTexts, memLimits.maxFacts(), memLimits.maxChars());
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            String row = "- " + line + "\n";
            if (text.length() + row.length() > memLimits.maxChars()) {
                break;
            }
            text.append(row);
        }
        if (text.length() == 0) {
            return new MemoryImportDtos.PreviewResponse(List.of(), new MemoryImportDtos.PreviewStats(0, 0, 0, 0),
                new MemoryImportDtos.Capacity(0, 0), hint);
        }
        return mem.preview(subject, new MemoryImportDtos.PreviewRequest(text.toString(), hint));
    }

    // ---- helpers ----

    private void checkSize(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a conversations file first.");
        }
        if (data.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is larger than " + (maxBytes / (1024 * 1024)) + " MB. Upload just conversations.json from the export.");
        }
    }

    /**
     * Text shown in a preview: control/invisible characters removed, whitespace collapsed, cut at {@code max}
     * characters, and replaced by "[hidden]" when the memory-import screen says it looks like a credential, card,
     * ID number or other secret - a preview must never echo one back.
     */
    static String safeText(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String one = ChatPortText.oneLine(ChatPortText.truncate(ChatPortText.clean(raw), SCREEN_INPUT_CHARS, false));
        if (one.isEmpty()) {
            return "";
        }
        MemoryImportParser.Screened screened = MemoryImportParser.screen(one, SCREEN_INPUT_CHARS);
        if (MemoryImportParser.isSensitiveReason(screened.rejectedReason())) {
            return MemoryImportParser.HIDDEN;
        }
        return ChatPortText.truncate(one, max, true);
    }

    static ImportedChat toEntity(String subject, ParsedChat chat) {
        List<Map<String, Object>> messages = new ArrayList<>(chat.messages().size());
        for (ParsedMessage m : chat.messages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.role());
            row.put("content", m.content());
            row.put("ts", m.timestamp() == null ? null : m.timestamp().toString());
            row.put("attachments", m.attachments() == null ? List.of() : m.attachments());
            messages.add(row);
        }
        String title = ChatPortText.truncate(chat.title() == null || chat.title().isBlank() ? "Untitled chat" : chat.title(), 300, false);
        return new ImportedChat(subject, chat.source(), chat.sourceId(), title, chat.createdAt(), chat.updatedAt(),
            chat.messageCount(), chat.truncated(), messages);
    }

    static ImportedChatView view(ImportedChat c) {
        List<ImportedMessage> messages = new ArrayList<>();
        for (Map<String, Object> m : c.getMessages()) {
            Object role = m.get("role");
            Object content = m.get("content");
            Object ts = m.get("ts");
            List<String> attachments = new ArrayList<>();
            if (m.get("attachments") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String name) {
                        attachments.add(name);
                    }
                }
            }
            messages.add(new ImportedMessage(role instanceof String r ? r : ChatImportParser.ROLE_ASSISTANT,
                content instanceof String s ? s : "", parseInstant(ts), attachments));
        }
        return new ImportedChatView(c.getId(), c.getSource(), c.getTitle(), c.getSourceCreatedAt(), c.getImportedAt(),
            c.getMessageCount(), c.isTruncated(), messages);
    }

    static Instant parseInstant(Object ts) {
        if (!(ts instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
    }

    /** Provenance lives in the audit log (counts only, never chat text). Never throws. */
    private void recordAudit(String subject, String action, String detail) {
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(subject, action, "ImportedChat", null, AuditOutcome.SUCCESS, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Chat import: audit entry could not be written", e);
        }
    }
}
