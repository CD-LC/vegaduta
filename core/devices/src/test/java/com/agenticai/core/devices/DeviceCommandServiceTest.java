package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Queue semantics of {@link DeviceCommandService}: enqueue refuses unknown/revoked devices,
 * the poll marks the next pending command DELIVERED (and heartbeats the device), and result
 * reporting only accepts the two terminal states.
 */
class DeviceCommandServiceTest {

    private MobileDeviceRepository deviceRepository;
    private DeviceCommandRepository commandRepository;
    private DeviceCommandService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(MobileDeviceRepository.class);
        commandRepository = mock(DeviceCommandRepository.class);
        service = new DeviceCommandService(deviceRepository, commandRepository);
    }

    private static MobileDevice activeDevice() {
        return new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok");
    }

    private static MobileDevice revokedDevice() {
        MobileDevice device = activeDevice();
        device.revoke();
        return device;
    }

    @Test
    void enqueueSavesAPendingCommandForAKnownActiveDevice() {
        UUID deviceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(activeDevice()));
        when(commandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceCommand saved = service.enqueue(deviceId, "TAP", "{}", runId);

        assertThat(saved.getStatus()).isEqualTo(DeviceCommandStatus.PENDING);
        assertThat(saved.getCommandType()).isEqualTo("TAP");
        assertThat(saved.getPayload()).isEqualTo("{}");
        assertThat(saved.getWorkflowRunId()).isEqualTo(runId);
    }

    @Test
    void enqueueRejectsAnUnknownOrRevokedDevice() {
        UUID unknown = UUID.randomUUID();
        when(deviceRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.enqueue(unknown, "TAP", "{}", null))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining(unknown.toString());

        UUID revoked = UUID.randomUUID();
        when(deviceRepository.findById(revoked)).thenReturn(Optional.of(revokedDevice()));
        assertThatThrownBy(() -> service.enqueue(revoked, "TAP", "{}", null))
                .isInstanceOf(DeviceException.class);

        verify(commandRepository, never()).save(any());
    }

    @Test
    void nextPendingCommandHeartbeatsTheDeviceAndMarksTheOldestPendingDelivered() {
        MobileDevice device = activeDevice();
        DeviceCommand pending =
                new DeviceCommand(device.getId(), "OPEN_APP", "com.example", null);
        when(commandRepository.findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(
                        device.getId(), DeviceCommandStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(commandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<DeviceCommand> next = service.nextPendingCommand(device);

        assertThat(next).containsSame(pending);
        assertThat(pending.getStatus()).isEqualTo(DeviceCommandStatus.DELIVERED);
        assertThat(pending.getDeliveredAt()).isNotNull();
        // The poll itself is liveness evidence, even with an empty queue.
        assertThat(device.getLastSeenAt()).isNotNull();
        verify(deviceRepository).save(device);
        verify(commandRepository).save(pending);
    }

    @Test
    void nextPendingCommandStillHeartbeatsWhenTheQueueIsEmpty() {
        MobileDevice device = activeDevice();
        when(commandRepository.findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(
                        device.getId(), DeviceCommandStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThat(service.nextPendingCommand(device)).isEmpty();
        assertThat(device.getLastSeenAt()).isNotNull();
        verify(commandRepository, never()).save(any());
    }

    @Test
    void recordResultOnlyAcceptsTerminalStates() {
        MobileDevice device = activeDevice();
        UUID commandId = UUID.randomUUID();

        for (DeviceCommandStatus bad : List.of(DeviceCommandStatus.PENDING, DeviceCommandStatus.DELIVERED)) {
            assertThatThrownBy(() -> service.recordResult(device, commandId, bad, "x"))
                    .isInstanceOf(DeviceException.class)
                    .hasMessageContaining("COMPLETED or FAILED");
        }
    }

    @Test
    void recordResultRejectsACommandTheDeviceDoesNotOwn() {
        MobileDevice device = activeDevice();
        UUID commandId = UUID.randomUUID();
        when(commandRepository.findByIdAndDeviceId(commandId, device.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.recordResult(
                                        device, commandId, DeviceCommandStatus.COMPLETED, "ok"))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining(commandId.toString());
    }

    @Test
    void recordResultCompletesTheCommandAndPersistsIt() {
        MobileDevice device = activeDevice();
        DeviceCommand command =
                new DeviceCommand(device.getId(), "TAP", "{}", null);
        command.markDelivered();
        UUID commandId = UUID.randomUUID();
        when(commandRepository.findByIdAndDeviceId(commandId, device.getId()))
                .thenReturn(Optional.of(command));
        when(commandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceCommand saved =
                service.recordResult(device, commandId, DeviceCommandStatus.FAILED, "boom");

        assertThat(saved.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(saved.getResult()).isEqualTo("boom");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void historyIsANewestFirstPassThroughToTheRepository() {
        UUID deviceId = UUID.randomUUID();
        List<DeviceCommand> rows = List.of(new DeviceCommand(deviceId, "TAP", "{}", null));
        when(commandRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId)).thenReturn(rows);

        assertThat(service.history(deviceId)).isSameAs(rows);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(commandRepository).findByDeviceIdOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue()).isEqualTo(deviceId);
    }
}
