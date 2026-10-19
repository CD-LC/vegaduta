package com.agenticai.core.devices;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.devices.dto.ClaimResponse;
import com.agenticai.core.devices.dto.CommandResponse;
import com.agenticai.core.devices.dto.DeviceResponse;
import com.agenticai.core.devices.dto.EnqueueCommandRequest;
import com.agenticai.core.devices.dto.PairingCodeResponse;
import com.agenticai.core.devices.dto.RegisterDeviceRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated (Keycloak JWT) console-side half of the Mobile SDK: any
 * signed-in tenant member can generate a pairing code for their own phone,
 * list/revoke devices, and send a device a one-off command outside of a
 * workflow run. The other half - what the phone itself calls, with no JWT -
 * is {@link DeviceGatewayController}.
 *
 * <p>Not admin-gated, unlike Twilio/LLM-provider/webhook config in {@code
 * SecurityConfig} - pairing your own device is a personal utility action
 * (same trust tier as creating an agent), not a platform-wide credential
 * change a non-admin teammate shouldn't be able to make.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DevicePairingService pairingService;
    private final DeviceCommandService commandService;
    private final MobileDeviceRepository deviceRepository;

    public DeviceController(
        DevicePairingService pairingService,
        DeviceCommandService commandService,
        MobileDeviceRepository deviceRepository
    ) {
        this.pairingService = pairingService;
        this.commandService = commandService;
        this.deviceRepository = deviceRepository;
    }

    @PostMapping("/pairing-code")
    public PairingCodeResponse generatePairingCode() {
        return PairingCodeResponse.from(pairingService.generateCode());
    }

    /**
     * #157 follow-up - the Mobile SDK's Keycloak-login alternative to the
     * pairing-code flow above (see {@link RegisterDeviceRequest}'s javadoc).
     * No explicit {@code SecurityConfig} matcher needed: this path is
     * already JWT-authenticated by the same default {@code
     * anyRequest().authenticated()} rule every other endpoint on this
     * controller relies on, unlike {@code DeviceGatewayController}'s
     * permitAll endpoints.
     */
    @PostMapping("/register")
    public ClaimResponse register(@Valid @RequestBody RegisterDeviceRequest request) {
        DevicePairingService.ClaimResult result = pairingService.registerAuthenticated(request.deviceName(), request.platform());
        return new ClaimResponse(result.deviceId(), result.deviceToken());
    }

    @GetMapping
    public ResponseEntity<List<DeviceResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            deviceRepository.findByOrderByCreatedAtDesc().stream().map(DeviceResponse::from).toList(),
            limit, offset, 50, 500);
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID deviceId) {
        MobileDevice device = deviceRepository.findById(deviceId)
            .orElseThrow(() -> new DeviceException("Unknown device: " + deviceId));
        device.revoke();
        deviceRepository.save(device);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{deviceId}/commands")
    public ResponseEntity<List<CommandResponse>> commandHistory(@PathVariable UUID deviceId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            commandService.history(deviceId).stream().map(CommandResponse::from).toList(),
            limit, offset, 50, 500);
    }

    /** Send a device a command directly, without a workflow run involved. */
    @PostMapping("/{deviceId}/commands")
    public CommandResponse enqueue(@PathVariable UUID deviceId, @Valid @RequestBody EnqueueCommandRequest request) {
        DeviceCommand command = commandService.enqueue(deviceId, request.commandType(), request.payload(), null);
        return CommandResponse.from(command);
    }
}
