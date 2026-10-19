package com.agenticai.core.devices;

import com.agenticai.core.devices.dto.IngestAppUsageRequest;
import com.agenticai.core.devices.dto.IngestNotificationsRequest;
import com.agenticai.core.devices.dto.IngestScreenStateRequest;
import com.agenticai.core.devices.dto.IngestSmsRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Mobile SDK app's own data-push endpoints - #157 follow-up ("send all
 * data into agentic platform"). Same trust posture as {@link
 * DeviceGatewayController} (no Keycloak JWT, authenticated instead by the
 * {@code X-Device-Token} header via {@link DeviceAuthResolver} - see that
 * controller's javadoc for the full reasoning) and deliberately kept as a
 * separate controller/class from it: {@code DeviceGatewayController} is the
 * command poll/result loop (#157's original scope), this is the newer,
 * independent "phone pushes its own observed data up" direction - the two
 * happen on different schedules from the phone's side (poll is a tight
 * request/response loop, these are periodic background pushes from
 * dedicated reporters) and keeping them in separate classes avoids one
 * bloated god-controller.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceDataIngestionController {

    private final DeviceAuthResolver authResolver;
    private final DeviceDataIngestionService ingestionService;

    public DeviceDataIngestionController(DeviceAuthResolver authResolver, DeviceDataIngestionService ingestionService) {
        this.authResolver = authResolver;
        this.ingestionService = ingestionService;
    }

    @PostMapping("/screen-state")
    public Map<String, Object> screenState(
        @RequestHeader("X-Device-Token") String deviceToken,
        @RequestBody IngestScreenStateRequest request
    ) {
        return authResolver.run(deviceToken, device -> {
            ingestionService.recordScreenState(device, request);
            return Map.of("status", "ok");
        });
    }

    @PostMapping("/notifications")
    public Map<String, Object> notifications(
        @RequestHeader("X-Device-Token") String deviceToken,
        @Valid @RequestBody IngestNotificationsRequest request
    ) {
        return authResolver.run(deviceToken, device -> {
            int count = ingestionService.recordNotifications(device, request).size();
            return Map.of("status", "ok", "received", count);
        });
    }

    @PostMapping("/app-usage")
    public Map<String, Object> appUsage(
        @RequestHeader("X-Device-Token") String deviceToken,
        @Valid @RequestBody IngestAppUsageRequest request
    ) {
        return authResolver.run(deviceToken, device -> {
            int count = ingestionService.recordAppUsageEvents(device, request).size();
            return Map.of("status", "ok", "received", count);
        });
    }

    @PostMapping("/sms")
    public Map<String, Object> sms(
        @RequestHeader("X-Device-Token") String deviceToken,
        @Valid @RequestBody IngestSmsRequest request
    ) {
        return authResolver.run(deviceToken, device -> {
            int count = ingestionService.recordSmsMessages(device, request).size();
            return Map.of("status", "ok", "received", count);
        });
    }
}
