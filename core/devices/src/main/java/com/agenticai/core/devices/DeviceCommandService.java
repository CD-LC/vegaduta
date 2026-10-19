package com.agenticai.core.devices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues and resolves {@link DeviceCommand}s - the "Device Control" workflow
 * node calls {@link #enqueue} and returns immediately (see {@code
 * WorkflowExecutionEngine#executeDeviceControlNode}'s javadoc for why this is
 * necessarily fire-and-forget, not a synchronous round trip like the Email/
 * Telegram/WhatsApp output destinations); the device's own poll loop and
 * result callback (both in {@code DeviceGatewayController}) call the rest.
 */
@Service
public class DeviceCommandService {

    private final MobileDeviceRepository deviceRepository;
    private final DeviceCommandRepository commandRepository;

    public DeviceCommandService(MobileDeviceRepository deviceRepository, DeviceCommandRepository commandRepository) {
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
    }

    @Transactional
    public DeviceCommand enqueue(UUID deviceId, String commandType, String payload, UUID workflowRunId) {
        MobileDevice device = deviceRepository.findById(deviceId)
            .filter(d -> !d.isRevoked())
            .orElseThrow(() -> new DeviceException("Unknown or revoked device: " + deviceId));
        return commandRepository.save(new DeviceCommand(device.getId(), commandType, payload, workflowRunId));
    }

    @Transactional
    public Optional<DeviceCommand> nextPendingCommand(MobileDevice device) {
        device.touch();
        deviceRepository.save(device);
        return commandRepository.findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(device.getId(), DeviceCommandStatus.PENDING)
            .map(command -> {
                command.markDelivered();
                return commandRepository.save(command);
            });
    }

    @Transactional
    public DeviceCommand recordResult(MobileDevice device, UUID commandId, DeviceCommandStatus status, String result) {
        if (status != DeviceCommandStatus.COMPLETED && status != DeviceCommandStatus.FAILED) {
            throw new DeviceException("Result status must be COMPLETED or FAILED, got: " + status);
        }
        DeviceCommand command = commandRepository.findByIdAndDeviceId(commandId, device.getId())
            .orElseThrow(() -> new DeviceException("Unknown command " + commandId + " for this device"));
        command.complete(status, result);
        return commandRepository.save(command);
    }

    @Transactional(readOnly = true)
    public List<DeviceCommand> history(UUID deviceId) {
        return commandRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }
}
