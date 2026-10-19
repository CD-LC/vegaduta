package com.agenticai.core.devices.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

/** Batched, not one-notification-per-request - a phone may see several notifications land between poll intervals. */
public record IngestNotificationsRequest(@NotEmpty @Valid List<Item> notifications) {

    public record Item(@NotBlank String packageName, String title, String text, Instant postedAt) {
    }
}
