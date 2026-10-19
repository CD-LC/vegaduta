package com.agenticai.core.omnichannel.email;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Tenant-scoped suppression list writes (caller must have TenantContext set). */
@Service
public class EmailSuppressionService {

    private final EmailSuppressionRepository repository;

    public EmailSuppressionService(EmailSuppressionRepository repository) {
        this.repository = repository;
    }

    /** Idempotent: a second suppression of the same address (or a racing insert) is a no-op. */
    public void suppress(String email, String reason) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (repository.existsByEmail(normalized)) {
            return;
        }
        try {
            repository.save(new EmailSuppression(normalized, reason));
        } catch (DataIntegrityViolationException raced) {
            // unique(email) - another request suppressed it first; the goal is met.
        }
    }
}
