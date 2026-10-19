package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DeviceSmsDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public record IngestSmsRequest(@NotEmpty @Valid List<Item> messages) {

    public record Item(String address, String body, @NotNull DeviceSmsDirection direction, Instant smsTimestamp) {
    }
}
