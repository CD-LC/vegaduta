package com.agenticai.core.agents.tools.device;

import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.devices.DeviceNotification;
import com.agenticai.core.devices.MobileDevice;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #157 follow-up - recent notifications the Mobile SDK's NotificationListenerService reported for a
 * paired phone.
 */
public class ReadDeviceNotificationsToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(ReadDeviceNotificationsToolCallback.class);

    private final DeviceDataIngestionService ingestionService;

    public ReadDeviceNotificationsToolCallback(DeviceDataIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("read_device_notifications")
                .description(
                        "Reads recent notifications received on this tenant's paired phone, newest first - app name, "
                                + "title, and text. Use this to answer questions like 'what notifications have come in' or "
                                + "'did I get any messages on my phone'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "device": {"type": "string", "description": "Optional device name or id, if more than one phone is paired."},
                        "limit": {"type": "integer", "description": "Max notifications to return, newest first (default 20, max 100)"}
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
            List<DeviceNotification> notifications =
                    ingestionService.recentNotifications(device.get().getId(), limit);
            List<Map<String, Object>> items =
                    notifications.stream()
                            .map(
                                    n ->
                                            (Map<String, Object>)
                                                    Map.<String, Object>of(
                                                            "packageName", n.getPackageName(),
                                                            "title",
                                                                    n.getTitle() == null
                                                                            ? ""
                                                                            : n.getTitle(),
                                                            "text",
                                                                    n.getText() == null
                                                                            ? ""
                                                                            : n.getText(),
                                                            "postedAt",
                                                                    String.valueOf(n.getPostedAt()),
                                                            "receivedAt",
                                                                    String.valueOf(
                                                                            n.getReceivedAt())))
                            .toList();
            return DeviceToolSupport.toJson(
                    Map.of(
                            "device",
                            device.get().getDeviceName(),
                            "notifications",
                            items,
                            "note",
                            items.isEmpty() ? "No notifications have been reported yet." : ""));
        } catch (Exception e) {
            log.warn("read_device_notifications tool call failed", e);
            return DeviceToolSupport.errorJson(
                    "Could not read device notifications: " + e.getMessage());
        }
    }
}
