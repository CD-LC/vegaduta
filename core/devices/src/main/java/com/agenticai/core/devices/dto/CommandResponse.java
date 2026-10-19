package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DeviceCommand;
import com.agenticai.core.devices.DeviceCommandStatus;
import java.time.Instant;
import java.util.UUID;

public record CommandResponse(
    UUID id,
    String commandType,
    String payload,
    DeviceCommandStatus status,
    String result,
    Instant createdAt,
    Instant deliveredAt,
    Instant completedAt
) {

    public static CommandResponse from(DeviceCommand command) {
        return new CommandResponse(
            command.getId(), command.getCommandType(), command.getPayload(), command.getStatus(),
            command.getResult(), command.getCreatedAt(), command.getDeliveredAt(), command.getCompletedAt()
        );
    }
}
