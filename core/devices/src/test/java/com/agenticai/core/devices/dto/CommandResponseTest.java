package com.agenticai.core.devices.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.devices.DeviceCommand;
import com.agenticai.core.devices.DeviceCommandStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandResponseTest {

    @Test
    void fromMapsAPendingCommandWithNullTimestamps() {
        DeviceCommand command = new DeviceCommand(UUID.randomUUID(), "TAP", "{}", null);

        CommandResponse response = CommandResponse.from(command);

        assertThat(response.id()).isEqualTo(command.getId());
        assertThat(response.commandType()).isEqualTo("TAP");
        assertThat(response.payload()).isEqualTo("{}");
        assertThat(response.status()).isEqualTo(DeviceCommandStatus.PENDING);
        assertThat(response.result()).isNull();
        assertThat(response.createdAt()).isEqualTo(command.getCreatedAt());
        assertThat(response.deliveredAt()).isNull();
        assertThat(response.completedAt()).isNull();
    }

    @Test
    void fromMapsACompletedCommandsTimestampsAndResult() {
        DeviceCommand command = new DeviceCommand(UUID.randomUUID(), "TYPE_TEXT", "hi", null);
        command.markDelivered();
        command.complete(DeviceCommandStatus.COMPLETED, "done");

        CommandResponse response = CommandResponse.from(command);

        assertThat(response.status()).isEqualTo(DeviceCommandStatus.COMPLETED);
        assertThat(response.result()).isEqualTo("done");
        assertThat(response.deliveredAt()).isEqualTo(command.getDeliveredAt());
        assertThat(response.completedAt()).isEqualTo(command.getCompletedAt());
    }
}
