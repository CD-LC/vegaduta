package com.agenticai.core.omnichannel.crm;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Built-in Leads CRM (V337): pipeline records, the activity timeline, CSV
 * import/export support and the entry points the workflow {@code crm_lead}
 * node and the (future) {@code email.reply_received} listener call.
 *
 * <p>Update semantics on {@link LeadInput}: a {@code null} field means "leave
 * unchanged"; a blank string clears a text field. Email is the natural key
 * (case-insensitive) for {@link #upsert} and {@link #recordEmailReply}.
 */
@Service
public class LeadService {

    private static final int MAX_BODY = 20_000;

    private final LeadRepository leads;
    private final LeadActivityRepository activities;

    public LeadService(LeadRepository leads, LeadActivityRepository activities) {
        this.leads = leads;
        this.activities = activities;
    }

    /** All fields optional; see class javadoc for null/blank semantics. */
    public record LeadInput(
        String name, String email, String phone, String company, String title, String source, LeadStage stage,
        Integer score, String owner, BigDecimal value, List<String> tags, String notes
    ) {
    }

    public record ImportResult(int created, int updated, int skipped, List<String> errors) {
    }

    public record StageTotal(LeadStage stage, long count, BigDecimal value) {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Lead> list(LeadStage stage, String source, String q, Pageable pageable) {
        Specification<Lead> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (stage != null) {
                ps.add(cb.equal(root.get("stage"), stage));
            }
            if (source != null && !source.isBlank()) {
                ps.add(cb.equal(cb.lower(root.get("source")), source.trim().toLowerCase(Locale.ROOT)));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.add(cb.or(
                    cb.like(cb.lower(cb.coalesce(root.<String>get("name"), "")), like, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("email"), "")), like, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("company"), "")), like, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.<String>get("phone"), "")), like, '\\')
                ));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return leads.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Lead get(UUID id) {
        return leads.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Lead> findByEmail(String email) {
        String normalized = Lead.normalizeEmail(email);
        return normalized == null ? Optional.empty() : leads.findByEmail(normalized);
    }

    @Transactional(readOnly = true)
    public List<LeadActivity> activities(UUID leadId) {
        get(leadId);
        return activities.findByLeadIdOrderByCreatedAtDesc(leadId);
    }

    @Transactional(readOnly = true)
    public List<Lead> exportAll() {
        return leads.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    /** Every stage present (zero-filled), in board order. */
    @Transactional(readOnly = true)
    public List<StageTotal> pipeline() {
        Map<LeadStage, StageTotal> byStage = new EnumMap<>(LeadStage.class);
        for (Object[] row : leads.pipelineTotals()) {
            LeadStage s = (LeadStage) row[0];
            BigDecimal v = row[2] instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(row[2]));
            byStage.put(s, new StageTotal(s, ((Number) row[1]).longValue(), v));
        }
        List<StageTotal> out = new ArrayList<>();
        for (LeadStage s : LeadStage.values()) {
            out.add(byStage.getOrDefault(s, new StageTotal(s, 0, BigDecimal.ZERO)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public Lead create(LeadInput in) {
        String email = Lead.normalizeEmail(in.email());
        if (isBlank(in.name()) && email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A lead needs a name or an email");
        }
        if (email != null && leads.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A lead with that email already exists");
        }
        Lead lead = new Lead(blankToNull(in.name()), email);
        apply(lead, in);
        return leads.save(lead);
    }

    @Transactional
    public Lead update(UUID id, LeadInput in) {
        Lead lead = get(id);
        String email = Lead.normalizeEmail(in.email());
        if (in.email() != null && email != null && !email.equals(lead.getEmail())) {
            Optional<Lead> clash = leads.findByEmail(email);
            if (clash.isPresent() && !clash.get().getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A lead with that email already exists");
            }
        }
        apply(lead, in);
        if (in.stage() != null && in.stage() != lead.getStage()) {
            // apply() only copies plain fields; stage goes through changeStage so the timeline records it.
            return changeStage(id, in.stage(), null);
        }
        return leads.save(lead);
    }

    @Transactional
    public void delete(UUID id) {
        Lead lead = get(id);
        leads.delete(lead);
    }

    @Transactional
    public Lead changeStage(UUID id, LeadStage stage, String note) {
        if (stage == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stage is required");
        }
        Lead lead = get(id);
        moveStage(lead, stage, note);
        return leads.save(lead);
    }

    @Transactional
    public LeadActivity addActivity(UUID leadId, LeadActivityType type, String body, Map<String, Object> meta) {
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        Lead lead = get(leadId);
        Instant now = Instant.now();
        if (type == LeadActivityType.EMAIL_SENT) {
            lead.setLastContactedAt(now);
            if (lead.getStage() == LeadStage.NEW) {
                moveStage(lead, LeadStage.CONTACTED, "First email sent");
            }
        } else if (type == LeadActivityType.EMAIL_REPLY) {
            lead.setLastRepliedAt(now);
            if (lead.getStage() == LeadStage.NEW || lead.getStage() == LeadStage.CONTACTED) {
                moveStage(lead, LeadStage.REPLIED, "Reply received");
            }
        }
        lead.touch();
        leads.save(lead);
        return activities.save(new LeadActivity(leadId, type, clip(body), meta));
    }

    /** Create-or-update keyed by email (falls back to always-create when there is no email). Used by import and the workflow node. */
    @Transactional
    public Lead upsert(LeadInput in) {
        String email = Lead.normalizeEmail(in.email());
        if (email != null) {
            Optional<Lead> existing = leads.findByEmail(email);
            if (existing.isPresent()) {
                return update(existing.get().getId(), in);
            }
        }
        return create(in);
    }

    /**
     * Entry point for the reply-detection stream: records an EMAIL_REPLY on
     * the lead with this address (moving NEW/CONTACTED leads to REPLIED).
     * Empty when the sender is not a known lead - replies from strangers are
     * not auto-created as leads.
     */
    @Transactional
    public Optional<Lead> recordEmailReply(String email, String body) {
        String normalized = Lead.normalizeEmail(email);
        if (normalized == null) {
            return Optional.empty();
        }
        Optional<Lead> lead = leads.findByEmail(normalized);
        lead.ifPresent(l -> addActivity(l.getId(), LeadActivityType.EMAIL_REPLY, body, null));
        return lead.map(l -> leads.findById(l.getId()).orElse(l));
    }

    @Transactional
    public ImportResult importRows(List<LeadCsv.ParsedRow> rows) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (LeadCsv.ParsedRow row : rows) {
            if (row.error() != null) {
                skipped++;
                errors.add("Row " + row.row() + ": " + row.error());
                continue;
            }
            boolean exists = row.input().email() != null
                && leads.findByEmail(Lead.normalizeEmail(row.input().email())).isPresent();
            upsert(row.input());
            if (exists) {
                updated++;
            } else {
                created++;
            }
        }
        return new ImportResult(created, updated, skipped, errors.size() > 50 ? errors.subList(0, 50) : errors);
    }

    // ------------------------------------------------------------------

    private void moveStage(Lead lead, LeadStage stage, String note) {
        LeadStage from = lead.getStage();
        if (from == stage) {
            return;
        }
        lead.setStage(stage);
        Instant now = Instant.now();
        if (stage == LeadStage.CONTACTED) {
            lead.setLastContactedAt(now);
        } else if (stage == LeadStage.REPLIED) {
            lead.setLastRepliedAt(now);
        }
        lead.touch();
        Lead saved = leads.save(lead);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", from.name());
        meta.put("to", stage.name());
        activities.save(new LeadActivity(
            saved.getId(), LeadActivityType.STAGE_CHANGE,
            clip(note == null || note.isBlank() ? from + " -> " + stage : note), meta
        ));
    }

    /** Copies non-null plain fields (everything except stage). */
    private void apply(Lead lead, LeadInput in) {
        if (in.name() != null) {
            lead.setName(blankToNull(in.name()));
        }
        if (in.email() != null) {
            lead.setEmail(in.email());
        }
        if (in.phone() != null) {
            lead.setPhone(blankToNull(in.phone()));
        }
        if (in.company() != null) {
            lead.setCompany(blankToNull(in.company()));
        }
        if (in.title() != null) {
            lead.setTitle(blankToNull(in.title()));
        }
        if (in.source() != null) {
            lead.setSource(blankToNull(in.source()));
        }
        if (in.score() != null) {
            lead.setScore(in.score());
        }
        if (in.owner() != null) {
            lead.setOwner(blankToNull(in.owner()));
        }
        if (in.value() != null) {
            lead.setValue(in.value());
        }
        if (in.tags() != null) {
            lead.setTags(Lead.joinTags(in.tags()));
        }
        if (in.notes() != null) {
            lead.setNotes(blankToNull(in.notes()));
        }
        // A brand-new lead may be created directly in a later stage (import "stage" column).
        if (in.stage() != null && lead.getId() == null) {
            lead.setStage(in.stage());
        }
        lead.touch();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s) {
        return s != null && s.length() > MAX_BODY ? s.substring(0, MAX_BODY) : s;
    }
}
