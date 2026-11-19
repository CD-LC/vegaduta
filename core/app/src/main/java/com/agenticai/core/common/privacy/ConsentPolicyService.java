package com.agenticai.core.common.privacy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-agent consent policy: whether a channel conversation starts with a notice and consent step,
 * the notice text per language, and the purpose the consent is for. Raw JDBC on the current
 * tenant's schema (like the other {@code common/*} features).
 */
@Service
public class ConsentPolicyService {

    static final Pattern LANGUAGE_TAG = Pattern.compile("[a-z]{2,3}(-[a-z0-9]{2,8})?");
    static final Pattern PURPOSE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_NOTICE_CHARS = 3000;

    /** What a tenant admin sends. {@code noticeVersion} is never taken from the client. */
    public record Request(
            boolean enabled,
            String purpose,
            String defaultLanguage,
            Map<String, String> notices,
            Map<String, String> instructions,
            Map<String, List<String>> keywords,
            Boolean eraseOnWithdrawal) {}

    public record Policy(
            UUID agentId,
            boolean enabled,
            String purpose,
            int noticeVersion,
            String defaultLanguage,
            Map<String, String> notices,
            Map<String, String> instructions,
            Map<String, List<String>> keywords,
            boolean eraseOnWithdrawal,
            String updatedBy,
            Instant updatedAt) {

        ConsentText.Keywords keywordSet() {
            return new ConsentText.Keywords(
                    keywords.getOrDefault("accept", List.of()),
                    keywords.getOrDefault("decline", List.of()),
                    keywords.getOrDefault("withdraw", List.of()));
        }
    }

    private final ConsentPolicyJdbcRepository repository;

    public ConsentPolicyService(ConsentPolicyJdbcRepository repository) {
        this.repository = repository;
    }

    public Optional<Policy> find(UUID agentId) {
        return repository.find(agentId);
    }

    /**
     * Creates or replaces the agent's policy. The notice version goes up by one whenever any notice
     * text differs from what was stored, so contacts who agreed to the old words are asked again.
     */
    public Policy put(UUID agentId, Request req, String actor) {
        if (!repository.agentExists(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such agent");
        }
        String purpose = req.purpose() == null ? "support" : req.purpose().trim().toLowerCase();
        String defaultLanguage =
                req.defaultLanguage() == null ? "en" : req.defaultLanguage().trim().toLowerCase();
        Map<String, String> notices = clean(req.notices());
        Map<String, String> instructions = clean(req.instructions());
        Map<String, List<String>> keywords =
                req.keywords() == null ? Map.of() : Map.copyOf(req.keywords());
        validate(req.enabled(), purpose, defaultLanguage, notices, instructions);

        Optional<Policy> existing = find(agentId);
        int version =
                existing.map(p -> p.notices().equals(notices) ? p.noticeVersion() : p.noticeVersion() + 1)
                        .orElse(1);
        boolean erase = req.eraseOnWithdrawal() == null || req.eraseOnWithdrawal();
        repository.upsert(
                agentId, req.enabled(), purpose, version, defaultLanguage, notices, instructions,
                keywords, erase, actor);
        return find(agentId).orElseThrow();
    }

    private static void validate(
            boolean enabled,
            String purpose,
            String defaultLanguage,
            Map<String, String> notices,
            Map<String, String> instructions) {
        if (!PURPOSE.matcher(purpose).matches()) {
            throw bad("purpose must be 1-64 characters of a-z, 0-9, '-' or '_'");
        }
        if (!LANGUAGE_TAG.matcher(defaultLanguage).matches()) {
            throw bad("defaultLanguage must be a language tag such as 'en' or 'hi'");
        }
        for (String lang : notices.keySet()) {
            if (!LANGUAGE_TAG.matcher(lang).matches()) {
                throw bad("notice language '" + lang + "' is not a language tag");
            }
        }
        for (String lang : instructions.keySet()) {
            if (!LANGUAGE_TAG.matcher(lang).matches()) {
                throw bad("instructions language '" + lang + "' is not a language tag");
            }
        }
        for (String text : notices.values()) {
            if (text.length() > MAX_NOTICE_CHARS) {
                throw bad("a notice is longer than " + MAX_NOTICE_CHARS + " characters");
            }
        }
        if (enabled && !notices.containsKey(defaultLanguage)) {
            throw bad("enabling consent needs a notice in the default language '" + defaultLanguage + "'");
        }
    }

    private static Map<String, String> clean(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach(
                    (k, v) -> {
                        if (k != null && v != null && !v.isBlank()) {
                            out.put(k.trim().toLowerCase(), v.strip());
                        }
                    });
        }
        return out;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
