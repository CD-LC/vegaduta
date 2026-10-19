package com.agenticai.core.platform.moderation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound blocklists for users, phone numbers, domains and regions, behind
 * {@code GET/PUT /api/platform/moderation/blocklists}.
 *
 * <p>Values are NORMALISED on write so that a lookup is a plain equality match
 * and near-duplicates cannot accumulate: {@code "Example.COM "} and
 * {@code "example.com"} must not be two separate blocks, one of which someone
 * later removes believing the domain is now allowed. Phone numbers keep only
 * their digits and a leading {@code +} for the same reason - the same number
 * written three ways is one block.
 */
@Service
public class ModerationBlocklistService {

    private static final Logger log = LoggerFactory.getLogger(ModerationBlocklistService.class);

    private final ModerationBlocklistEntryRepository repository;

    public ModerationBlocklistService(ModerationBlocklistEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ModerationBlocklistEntry> list(ModerationBlocklistType type) {
        return type == null
            ? repository.findByOrderByCreatedAtDesc()
            : repository.findByEntryTypeOrderByCreatedAtDesc(type);
    }

    /**
     * Adds or updates one entry, keyed on (type, normalised value). Setting
     * {@code active=false} is the supported way to unblock: the row stays, so
     * the audit trail of "this was blocked, then unblocked by X" survives.
     * Genuine deletion is not offered here for that reason.
     */
    @Transactional
    public ModerationBlocklistEntry upsert(ModerationBlocklistType type, String rawValue,
                                           boolean active, String reason, String actor) {
        String value = normalise(type, rawValue);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blocklist value must not be blank");
        }

        ModerationBlocklistEntry entry = repository.findByEntryTypeAndValue(type, value)
            .map(existing -> {
                existing.apply(active, reason, actor);
                return existing;
            })
            .orElseGet(() -> repository.save(
                new ModerationBlocklistEntry(type, value, reason, actor)));

        if (!active) {
            entry.apply(false, reason, actor);
        }

        log.info("Moderation blocklist {} '{}' active={} by {}", type, value, active, actor);
        return entry;
    }

    /** The active set for one type, for an enforcement path that wants to check membership cheaply. */
    @Transactional(readOnly = true)
    public Set<String> activeValues(ModerationBlocklistType type) {
        return repository.findByActiveTrue().stream()
            .filter(e -> e.getEntryType() == type)
            .map(ModerationBlocklistEntry::getValue)
            .collect(Collectors.toSet());
    }

    /** True when the given raw value is currently blocked for that type. */
    @Transactional(readOnly = true)
    public boolean isBlocked(ModerationBlocklistType type, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        return repository.findByEntryTypeAndValue(type, normalise(type, rawValue))
            .map(ModerationBlocklistEntry::isActive)
            .orElse(false);
    }

    /**
     * Type-aware normalisation. Kept package-private and unit-tested rather than
     * inlined, because the read path and the write path MUST agree exactly - if
     * they ever diverge, entries are written that can never be matched.
     */
    static String normalise(ModerationBlocklistType type, String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return switch (type) {
            // Keep digits and a leading +, so "+91 97424-24300", "+919742424300"
            // and "0091 9742424300" do not become three separate blocks.
            case PHONE -> {
                String digits = trimmed.replaceAll("[^0-9+]", "");
                yield digits.startsWith("+") ? "+" + digits.replaceAll("\\+", "") : digits;
            }
            // Strip a scheme/path if someone pastes a URL, and lower-case.
            case DOMAIN -> {
                String host = trimmed.toLowerCase(Locale.ROOT)
                    .replaceFirst("^[a-z]+://", "")
                    .replaceFirst("/.*$", "");
                yield host.startsWith("www.") ? host.substring(4) : host;
            }
            case USER, REGION -> trimmed.toLowerCase(Locale.ROOT);
        };
    }
}
