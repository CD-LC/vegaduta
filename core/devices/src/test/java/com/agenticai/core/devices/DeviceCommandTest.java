package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The command lifecycle: PENDING at enqueue, DELIVERED when the device's poll picks it up,
 * COMPLETED/FAILED when the device reports back - no intermediate "running" state by design.
 */
class DeviceCommandTest {

    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private static DeviceCommand command() {
        return new DeviceCommand(DEVICE_ID, "TAP", "{\"x\":10,\"y\":20}", RUN_ID);
    }

    @Test
    void aNewCommandIsPendingWithNoTimestampsBeyondCreation() {
        DeviceCommand command = command();

        assertThat(command.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(command.getCommandType()).isEqualTo("TAP");
        assertThat(command.getPayload()).isEqualTo("{\"x\":10,\"y\":20}");
        assertThat(command.getWorkflowRunId()).isEqualTo(RUN_ID);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.PENDING);
        assertThat(command.getResult()).isNull();
        assertThat(command.getCreatedAt()).isNotNull();
        assertThat(command.getDeliveredAt()).isNull();
        assertThat(command.getCompletedAt()).isNull();
        assertThat(command.getId()).isNull();
    }

    @Test
    void aCommandQueuedWithoutAWorkflowKeepsANullRunId() {
        DeviceCommand command = new DeviceCommand(DEVICE_ID, "TYPE_TEXT", "hi", null);

        assertThat(command.getWorkflowRunId()).isNull();
    }

    @Test
    void markDeliveredRecordsWhenTheDevicePickedItUp() {
        DeviceCommand command = command();

        command.markDelivered();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.DELIVERED);
        assertThat(command.getDeliveredAt()).isNotNull();
        assertThat(command.getCompletedAt()).isNull();
    }

    @Test
    void completeStoresTheTerminalStatusAndTheDevicesResultText() {
        DeviceCommand command = command();
        command.markDelivered();

        command.complete(DeviceCommandStatus.COMPLETED, "{\"ok\":true}");

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.COMPLETED);
        assertThat(command.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(command.getCompletedAt()).isNotNull();

        command.complete(DeviceCommandStatus.FAILED, "no such element");

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(command.getResult()).isEqualTo("no such element");
    }
}
