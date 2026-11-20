package com.agenticai.core.devices;

import com.agenticai.core.devices.dto.CommandResponse;
import com.agenticai.core.devices.dto.DeviceResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Console-side device management facade for {@link DeviceController}: owns the repository access
 * and transactions, and maps entities to response DTOs so the controller never touches entities.
 */
@Service
public class DeviceAdminService {

    private final MobileDeviceRepository deviceRepository;
    private final DeviceCommandService commandService;

    public DeviceAdminService(
            MobileDeviceRepository deviceRepository, DeviceCommandService commandService) {
        this.deviceRepository = deviceRepository;
        this.commandService = commandService;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listDevices() {
        return deviceRepository.findByOrderByCreatedAtDesc().stream()
                .map(DeviceResponse::from)
                .toList();
    }

    @Transactional
    public void revoke(UUID deviceId) {
        MobileDevice device =
                deviceRepository
                        .findById(deviceId)
                        .orElseThrow(() -> new DeviceException("Unknown device: " + deviceId));
        device.revoke();
        deviceRepository.save(device);
    }

    @Transactional(readOnly = true)
    public List<CommandResponse> commandHistory(UUID deviceId) {
        return commandService.history(deviceId).stream().map(CommandResponse::from).toList();
    }

    @Transactional
    public CommandResponse enqueue(UUID deviceId, String commandType, String payload) {
        return CommandResponse.from(commandService.enqueue(deviceId, commandType, payload, null));
    }
}
