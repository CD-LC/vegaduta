package com.agenticai.core.devices;

import com.agenticai.core.devices.dto.ClaimRequest;
import com.agenticai.core.devices.dto.ClaimResponse;
import com.agenticai.core.devices.dto.CommandResponse;
import com.agenticai.core.devices.dto.CommandResultRequest;
import com.agenticai.core.devices.dto.UpdateCapabilitiesRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Mobile SDK app's own endpoints - called with no Keycloak JWT at all
 * (a phone has no user session with this platform's identity provider),
 * exactly the same structural posture as {@code TwilioWebhookController} and
 * {@code WorkflowWebhookController}: {@code permitAll()} at the Spring
 * Security layer (see {@code SecurityConfig}'s {@code /api/devices/claim},
 * {@code /api/devices/poll}, {@code /api/devices/commands/*}/result
 * matchers), authenticated instead by possession of a bearer credential -
 * the pairing code for {@link #claim}, the device token (an {@code
 * X-Device-Token} header, resolved via {@link DeviceAuthResolver}) for
 * everything after.
 *
 * <p>{@link #poll} is short-interval HTTP polling, not a WebSocket push -
 * see the module's {@code pom.xml} description for why that is the honest,
 * durable choice for a phone whose OS can and will kill a backgrounded
 * persistent socket regardless of what the server offers.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceGatewayController {

    private final DevicePairingService pairingService;
    private final DeviceAuthResolver authResolver;
    private final DeviceCommandService commandService;
    private final DeviceClaimRateLimiter claimRateLimiter;

    public DeviceGatewayController(
        DevicePairingService pairingService,
        DeviceAuthResolver authResolver,
        DeviceCommandService commandService,
        DeviceClaimRateLimiter claimRateLimiter
    ) {
        this.pairingService = pairingService;
        this.authResolver = authResolver;
        this.commandService = commandService;
        this.claimRateLimiter = claimRateLimiter;
    }

    /**
     * SAST audit fix (2026-07-27) - see {@link DeviceClaimRateLimiter}'s
     * javadoc for why a rate limit belongs here: this is a
     * {@code permitAll()} endpoint guarded only by a six-digit code, and
     * without a limit that code is brute-forceable well inside its 10-minute
     * TTL. Checked before {@link DevicePairingService#claim} runs, so a
     * throttled caller never even reaches the pairing-code lookup.
     */
    @PostMapping("/claim")
    public ResponseEntity<?> claim(@Valid @RequestBody ClaimRequest request, HttpServletRequest servletRequest) {
        if (!claimRateLimiter.tryAcquire(clientIp(servletRequest), request.code())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "Too many pairing attempts. Wait a few minutes and try again."
            ));
        }
        DevicePairingService.ClaimResult result = pairingService.claim(request.code(), request.deviceName(), request.platform());
        return ResponseEntity.ok(new ClaimResponse(result.deviceId(), result.deviceToken()));
    }

    /**
     * {@code request.getRemoteAddr()} - deliberately NOT trusting
     * {@code X-Forwarded-For} directly at this layer, identical reasoning to
     * {@code DemoSessionController#clientIp}: Tomcat's {@code RemoteIpValve}
     * (see {@code application.yml}'s {@code server.tomcat.remoteip.*}) is
     * what turns this into the real visitor IP when the request actually
     * came through the trusted Caddy proxy - trusting the header directly
     * here would let an attacker present a fresh IP per guess and walk
     * straight through the per-IP half of the limiter.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** Returns the next queued command for this device, or 204 if none. */
    @PostMapping("/poll")
    public ResponseEntity<CommandResponse> poll(@RequestHeader("X-Device-Token") String deviceToken) {
        return authResolver.run(deviceToken, device ->
            commandService.nextPendingCommand(device)
                .map(command -> ResponseEntity.ok(CommandResponse.from(command)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NO_CONTENT).build())
        );
    }

    @PostMapping("/commands/{commandId}/result")
    public CommandResponse result(
        @RequestHeader("X-Device-Token") String deviceToken,
        @PathVariable UUID commandId,
        @Valid @RequestBody CommandResultRequest request
    ) {
        return authResolver.run(deviceToken, device ->
            CommandResponse.from(commandService.recordResult(device, commandId, request.status(), request.result()))
        );
    }

    /** The device declares what it can do (camera, location, ...); stored for the console's device list and diagnostics. */
    @PostMapping("/capabilities")
    public Map<String, Object> capabilities(
        @RequestHeader("X-Device-Token") String deviceToken,
        @Valid @RequestBody UpdateCapabilitiesRequest request
    ) {
        return authResolver.run(deviceToken, device -> {
            pairingService.updateCapabilities(device, request.capabilities());
            return Map.of("status", "ok");
        });
    }
}
