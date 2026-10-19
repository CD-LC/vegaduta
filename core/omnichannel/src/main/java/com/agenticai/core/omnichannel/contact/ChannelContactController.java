package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * /admin/contacts backend (migration V238) - the tenant's contact directory and its bulk import.
 * Read endpoints stay on plain {@code authenticated()} like every other channel read (GET
 * pairing/pending already exposes the same senders); every write is {@code ROLE_tenant-admin}-gated
 * in SecurityConfig - creating a contact is granting a (future) stranger a live agent conversation
 * without the pairing queue, same trust tier as approving a pairing.
 *
 * <p>Import accepts .xlsx (Apache POI - version matched to documentgen's pin), .csv/.txt (one
 * {@code identifier[,name[,group]]} per line, comma or tab separated), or pasted rows in the same
 * shape via {@code /import-rows}. A first row whose first cell reads like a header
 * (phone/number/name/...) is skipped, not imported as a bogus contact.
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelContactController {

    /** Sheet ceiling - a contact list, not a data pipeline. */
    private static final int MAX_IMPORT_ROWS = 5000;

    private static final Pattern HEADER_CELL =
            Pattern.compile(
                    "phone|number|identifier|contact|mobile|whatsapp|email|id",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CSV_SPLIT = Pattern.compile("[,;\\t]");

    private final ChannelContactService contactService;
    private final ChannelSessionRepository sessionRepository;
    private final AgentRepository agentRepository;

    public ChannelContactController(
            ChannelContactService contactService,
            ChannelSessionRepository sessionRepository,
            AgentRepository agentRepository) {
        this.contactService = contactService;
        this.sessionRepository = sessionRepository;
        this.agentRepository = agentRepository;
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    @GetMapping("/contacts")
    public ResponseEntity<List<ContactResponse>> list(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        // channel is an optional filter on the directory listing (the web lists
        // every channel by default); parseChannel's "channel is required" is
        // for create/update only - throwing it here 500'd the Contacts page.
        ChannelType filter = (channel == null || channel.isBlank()) ? null : parseChannel(channel);
        List<ChannelContact> rows = contactService.list(filter, groupId, limit, offset);
        // Truncation must be visible: X-Total-Count is the real count (only
        // queried when the page is full or paged-in), X-Has-More flags older
        // contacts beyond this page - reach them with ?offset=.
        return ListPaging.respond(
                rows.stream().map(this::toResponse).toList(),
                ListPaging.limit(
                        limit,
                        ChannelContactService.LIST_DEFAULT_LIMIT,
                        ChannelContactService.LIST_MAX_LIMIT),
                ListPaging.offset(offset),
                () -> contactService.count(filter, groupId));
    }

    @PostMapping("/contacts")
    public ContactResponse create(@Valid @RequestBody ContactRequest request) {
        return toResponse(
                contactService.upsert(
                        parseChannel(request.channel()),
                        request.identifier(),
                        request.displayName(),
                        request.groupId(),
                        request.agentId(),
                        "IMPORT"));
    }

    @PutMapping("/contacts/{id}")
    public ContactResponse update(
            @PathVariable UUID id, @RequestBody ContactUpdateRequest request) {
        return toResponse(
                contactService.update(
                        id, request.displayName(), request.groupId(), request.agentId()));
    }

    @DeleteMapping("/contacts/{id}")
    public void delete(@PathVariable UUID id) {
        contactService.delete(id);
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    @GetMapping("/contact-groups")
    public ResponseEntity<List<GroupResponse>> listGroups(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            contactService.listGroups().stream()
                .map(
                        g ->
                                new GroupResponse(
                                        g.getId(),
                                        g.getName(),
                                        g.getDescription(),
                                        g.getAgentId(),
                                        agentName(g.getAgentId()),
                                        contactService.memberCount(g.getId())))
                .toList(),
            limit, offset, 50, 500);
    }

    @PostMapping("/contact-groups")
    public GroupResponse createGroup(@Valid @RequestBody GroupRequest request) {
        ContactGroup g =
                contactService.createGroup(
                        request.name(), request.description(), request.agentId());
        return new GroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getAgentId(),
                agentName(g.getAgentId()),
                0);
    }

    @PutMapping("/contact-groups/{id}")
    public GroupResponse updateGroup(
            @PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        ContactGroup g =
                contactService.updateGroup(
                        id, request.name(), request.description(), request.agentId());
        return new GroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getAgentId(),
                agentName(g.getAgentId()),
                contactService.memberCount(id));
    }

    @DeleteMapping("/contact-groups/{id}")
    public void deleteGroup(@PathVariable UUID id) {
        contactService.deleteGroup(id);
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Multipart file import (.xlsx / .csv / .txt). The file field is {@code file}; {@code channel}
     * picks which channel the numbers belong to and {@code groupId} optionally files every row
     * under one group (a per-row group NAME in the sheet wins when both are present).
     */
    @PostMapping("/contacts/import")
    public ChannelContactService.ImportResult importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("channel") String channel,
            @RequestParam(value = "groupId", required = false) UUID groupId) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Choose a .xlsx, .csv or .txt file to import");
        }
        String filename =
                file.getOriginalFilename() == null
                        ? ""
                        : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            List<ChannelContactService.ImportRow> rows =
                    filename.endsWith(".xlsx") || filename.endsWith(".xls")
                            ? parseSpreadsheet(file)
                            : parseLines(
                                    new String(
                                            file.getBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
            return contactService.importRows(parseChannel(channel), groupId, rows);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not read that file - use .xlsx, .csv or pasted rows",
                    e);
        }
    }

    /** Pasted rows - same line format as the CSV path, no file needed. */
    @PostMapping("/contacts/import-rows")
    public ChannelContactService.ImportResult importRows(
            @Valid @RequestBody ImportRequest request) {
        List<ChannelContactService.ImportRow> rows = parseLines(request.text());
        return contactService.importRows(parseChannel(request.channel()), request.groupId(), rows);
    }

    private List<ChannelContactService.ImportRow> parseSpreadsheet(MultipartFile file)
            throws Exception {
        List<ChannelContactService.ImportRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream();
                Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return rows;
            }
            boolean first = true;
            for (Row row : sheet) {
                String c0 = cellText(row, 0);
                String c1 = cellText(row, 1);
                String c2 = cellText(row, 2);
                if (first && HEADER_CELL.matcher(c0 == null ? "" : c0).find()) {
                    first = false;
                    continue;
                }
                first = false;
                if (c0 == null || c0.isBlank()) {
                    continue;
                }
                rows.add(new ChannelContactService.ImportRow(c0.trim(), c1, c2));
                if (rows.size() >= MAX_IMPORT_ROWS) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Sheet has more than "
                                    + MAX_IMPORT_ROWS
                                    + " rows - split it and retry");
                }
            }
        }
        return rows;
    }

    private static String cellText(Row row, int index) {
        var cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        // Numeric cells arrive as doubles - a phone column read as
        // 1.5551234567E9 must come back as digits, not scientific notation.
        return switch (cell.getCellType()) {
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d)
                        ? String.valueOf((long) d)
                        : String.valueOf(d);
            }
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> {
                String v = cell.getStringCellValue();
                yield v == null ? null : v.trim();
            }
        };
    }

    private List<ChannelContactService.ImportRow> parseLines(String text) {
        List<ChannelContactService.ImportRow> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        // Excel-saved CSVs carry a UTF-8 BOM that would silently corrupt the
        // first row's identifier.
        text = text.replace("\uFEFF", "");
        boolean first = true;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = CSV_SPLIT.split(trimmed, 3);
            if (first && HEADER_CELL.matcher(parts[0]).find()) {
                first = false;
                continue;
            }
            first = false;
            String identifier = stripQuotes(parts[0]);
            String name = parts.length > 1 ? stripQuotes(parts[1]) : null;
            String group = parts.length > 2 ? stripQuotes(parts[2]) : null;
            rows.add(new ChannelContactService.ImportRow(identifier, name, group));
            if (rows.size() > MAX_IMPORT_ROWS) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "More than " + MAX_IMPORT_ROWS + " rows - split the list and retry");
            }
        }
        return rows;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------

    private ContactResponse toResponse(ChannelContact c) {
        ChannelSession session =
                c.getSessionId() != null
                        ? sessionRepository.findById(c.getSessionId()).orElse(null)
                        : null;
        String groupName =
                contactService.findGroup(c.getGroupId()).map(ContactGroup::getName).orElse(null);
        return new ContactResponse(
                c.getId(),
                c.getChannel(),
                c.getIdentifier(),
                c.getDisplayName(),
                c.getGroupId(),
                groupName,
                c.getAgentId(),
                agentName(c.getAgentId()),
                c.getSource(),
                c.getSessionId(),
                session != null && session.isPaired(),
                session != null ? session.getLastMessageAt() : null,
                c.getLastSeenAt(),
                c.getCreatedAt());
    }

    private String agentName(UUID agentId) {
        return agentId != null
                ? agentRepository.findById(agentId).map(a -> a.getName()).orElse(null)
                : null;
    }

    static ChannelType parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel is required");
        }
        try {
            return ChannelType.valueOf(channel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown channel: " + channel);
        }
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record ContactRequest(
            @NotNull String channel,
            @NotNull String identifier,
            String displayName,
            UUID groupId,
            UUID agentId) {}

    public record ContactUpdateRequest(String displayName, UUID groupId, UUID agentId) {}

    public record GroupRequest(@NotNull String name, String description, UUID agentId) {}

    public record ImportRequest(@NotNull String channel, UUID groupId, String text) {}

    public record ContactResponse(
            UUID id,
            ChannelType channel,
            String identifier,
            String displayName,
            UUID groupId,
            String groupName,
            UUID agentId,
            String agentName,
            String source,
            UUID sessionId,
            boolean paired,
            Instant lastMessageAt,
            Instant lastSeenAt,
            Instant createdAt) {}

    public record GroupResponse(
            UUID id,
            String name,
            String description,
            UUID agentId,
            String agentName,
            long memberCount) {}
}
