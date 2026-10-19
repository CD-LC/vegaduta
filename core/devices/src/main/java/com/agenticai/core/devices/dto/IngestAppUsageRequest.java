package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DeviceAppUsageEventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record IngestAppUsageRequest(@NotEmpty @Valid List<Item> events) {

    public record Item(@NotBlank String packageName, @NotNull DeviceAppUsageEventType eventType, Instant eventTime) {
    }
}
