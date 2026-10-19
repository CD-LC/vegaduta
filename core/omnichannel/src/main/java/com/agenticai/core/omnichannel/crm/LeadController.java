package com.agenticai.core.omnichannel.crm;

import java.math.BigDecimal;
import com.agenticai.core.tenancy.ListPaging;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * /admin/leads backend (V337). Every route here is gated by
 * {@code PERM_MANAGE_LEADS} in SecurityConfig - leads are PII, so reads are
 * gated too (unlike the contact directory's read-open split).
 */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    static final int MAX_PAGE_SIZE = 200;
    private static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;

    private final LeadService service;

    public LeadController(LeadService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @GetMapping
    public PageResponse list(
        @RequestParam(required = false) String stage,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        Page<Lead> result = service.list(
            parseStage(stage), source, q,
            PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)), Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new PageResponse(
            result.getContent().stream().map(LeadResponse::from).toList(),
            result.getTotalElements(), result.getNumber(), result.getSize()
        );
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@RequestBody LeadRequest request) {
        return LeadResponse.from(service.create(toInput(request)));
    }

    @GetMapping("/{id}")
    public LeadResponse get(@PathVariable UUID id) {
        return LeadResponse.from(service.get(id));
    }

    @PutMapping("/{id}")
    public LeadResponse update(@PathVariable UUID id, @RequestBody LeadRequest request) {
        return LeadResponse.from(service.update(id, toInput(request)));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // ------------------------------------------------------------------
    // Stage + activities
    // ------------------------------------------------------------------

    @PostMapping("/{id}/stage")
    public LeadResponse stage(@PathVariable UUID id, @RequestBody StageRequest request) {
        LeadStage stage = parseStage(request.stage());
        return LeadResponse.from(service.changeStage(id, stage, request.note()));
    }

    @PostMapping("/{id}/activities")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse addActivity(@PathVariable UUID id, @RequestBody ActivityRequest request) {
        LeadActivityType type;
        try {
            type = LeadActivityType.parse(request.type());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown activity type: " + request.type());
        }
        return ActivityResponse.from(service.addActivity(id, type, request.body(), request.meta()));
    }

    @GetMapping("/{id}/activities")
    public ResponseEntity<List<ActivityResponse>> activities(@PathVariable UUID id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.activities(id).stream().map(ActivityResponse::from).toList(), limit, offset, 50, 200);
    }

    // ------------------------------------------------------------------
    // Pipeline, import, export
    // ------------------------------------------------------------------

    @GetMapping("/pipeline")
    public ResponseEntity<List<LeadService.StageTotal>> pipeline(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.pipeline(), limit, offset, 100, 500);
    }

    /** Multipart CSV import; field name {@code file}. Header-driven - see {@link LeadCsv}. */
    @PostMapping("/import")
    public LeadService.ImportResult importFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a .csv file to import");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is larger than 5 MB - split it and retry");
        }
        try {
            return service.importRows(LeadCsv.parse(new String(file.getBytes(), StandardCharsets.UTF_8)));
        } catch (LeadCsv.CsvException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read that file", e);
        }
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export() {
        byte[] body = LeadCsv.export(service.exportAll()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"leads.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body);
    }

    // ------------------------------------------------------------------

    static LeadStage parseStage(String raw) {
        try {
            return LeadStage.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage: " + raw);
        }
    }

    static LeadService.LeadInput toInput(LeadRequest r) {
        return new LeadService.LeadInput(
            r.name(), r.email(), r.phone(), r.company(), r.title(), r.source(), parseStage(r.stage()),
            r.score(), r.owner(), r.value(), r.tags(), r.notes()
        );
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public record LeadRequest(
        String name, String email, String phone, String company, String title, String source, String stage,
        Integer score, String owner, BigDecimal value, List<String> tags, String notes
    ) {
    }

    public record StageRequest(String stage, String note) {
    }

    public record ActivityRequest(String type, String body, Map<String, Object> meta) {
    }

    public record LeadResponse(
        UUID id, String name, String email, String phone, String company, String title, String source, LeadStage stage,
        int score, String owner, BigDecimal value, List<String> tags, String notes, Instant lastContactedAt,
        Instant lastRepliedAt, Instant createdAt, Instant updatedAt
    ) {
        static LeadResponse from(Lead l) {
            return new LeadResponse(
                l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getCompany(), l.getTitle(), l.getSource(),
                l.getStage(), l.getScore(), l.getOwner(), l.getValue(), l.tagList(), l.getNotes(),
                l.getLastContactedAt(), l.getLastRepliedAt(), l.getCreatedAt(), l.getUpdatedAt()
            );
        }
    }

    public record ActivityResponse(UUID id, UUID leadId, LeadActivityType type, String body, Map<String, Object> meta, Instant createdAt) {
        static ActivityResponse from(LeadActivity a) {
            return new ActivityResponse(a.getId(), a.getLeadId(), a.getType(), a.getBody(), a.getMeta(), a.getCreatedAt());
        }
    }

    public record PageResponse(List<LeadResponse> items, long total, int page, int size) {
    }
}
