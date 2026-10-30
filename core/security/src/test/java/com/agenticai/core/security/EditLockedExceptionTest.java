package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link EditLockedException} carries the structured fields HTTP 423 needs ({@code lockedReason},
 * {@code editableUntil}) while still catching as a plain {@link TenantLimitExceededException} for
 * every legacy call site.
 */
class EditLockedExceptionTest {

    @Test
    void itIsATenantLimitExceededExceptionWithStructuredFields() {
        Instant editableUntil = Instant.parse("2026-09-01T00:00:00Z");

        EditLockedException e =
                new EditLockedException(
                        "Free-tier edit window closed",
                        "free_tier_grace_window_expired",
                        editableUntil);

        assertThat(e).isInstanceOf(TenantLimitExceededException.class);
        assertThat(e.getMessage()).isEqualTo("Free-tier edit window closed");
        assertThat(e.getLockedReason()).isEqualTo("free_tier_grace_window_expired");
        assertThat(e.getEditableUntil()).isEqualTo(editableUntil);
    }

    @Test
    void editableUntilIsNullableForObjectsWithNoMeaningfulUnlockInstant() {
        EditLockedException e = new EditLockedException("locked", "never_editable", null);

        assertThat(e.getEditableUntil()).isNull();
    }
}
