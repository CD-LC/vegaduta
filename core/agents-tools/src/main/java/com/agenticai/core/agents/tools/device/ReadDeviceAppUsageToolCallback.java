package com.agenticai.core.agents.tools.device;

import com.agenticai.core.devices.DeviceAppUsageEvent;
import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.devices.MobileDevice;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #157 follow-up - recent app foreground/background events the Mobile SDK's AppUsageReporter
 * reported for a paired phone.
 */
public class ReadDeviceAppUsageToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ReadDeviceAppUsageToolCallback.class);

    private final DeviceDataIngestionService ingestionService;

    public ReadDeviceAppUsageToolCallback(DeviceDataIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("read_device_app_usage")
                .description(
                        "Reads recent app usage (which apps came to the foreground or went to the background, and when) "
                                + "on this tenant's paired phone, newest first. Use this to answer questions like 'what apps "
                                + "have I been using' or 'what was open on my phone recently'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "device": {"type": "string", "description": "Optional device name or id, if more than one phone is paired."},
                        "limit": {"type": "integer", "description": "Max events to return, newest first (default 20, max 100)"}
                    }
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = DeviceToolSupport.parseArgs(toolInput);
            String deviceArg =
                    args.get("device") == null ? null : String.valueOf(args.get("device"));
            Optional<MobileDevice> device =
                    DeviceToolSupport.resolveDevice(ingestionService, deviceArg);
            if (device.isEmpty()) {
                return DeviceToolSupport.noDeviceError();
            }
            Integer limit =
                    args.get("limit") == null
                            ? null
                            : Integer.parseInt(String.valueOf(args.get("limit")));
            List<DeviceAppUsageEvent> events =
                    ingestionService.recentAppUsage(device.get().getId(), limit);
            List<Map<String, Object>> items =
                    events.stream()
                            .map(
                                    e ->
                                            (Map<String, Object>)
                                                    Map.<String, Object>of(
                                                            "packageName", e.getPackageName(),
                                                            "eventType", e.getEventType().name(),
                                                            "eventTime",
                                                                    String.valueOf(
                                                                            e.getEventTime()),
                                                            "receivedAt",
                                                                    String.valueOf(
                                                                            e.getReceivedAt())))
                            .toList();
            return DeviceToolSupport.toJson(
                    Map.of(
                            "device",
                            device.get().getDeviceName(),
                            "events",
                            items,
                            "note",
                            items.isEmpty() ? "No app usage has been reported yet." : ""));
        } catch (Exception e) {
            log.warn("read_device_app_usage tool call failed", e);
            return DeviceToolSupport.errorJson(
                    "Could not read device app usage: " + e.getMessage());
        }
    }
}
