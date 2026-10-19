package com.agenticai.core.agents.tools.device;

import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.devices.DeviceScreenState;
import com.agenticai.core.devices.MobileDevice;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #157 follow-up - reads the latest screen content the Mobile SDK's accessibility service reported
 * for a paired phone (see {@link DeviceScreenState}'s javadoc: a flattened accessibility-tree text
 * dump, not a screenshot image). Lets an agent answer "what is this person currently looking at on
 * their phone" without a device-control command round trip - unlike the fire-and-forget {@code
 * device_control} workflow node (which queues a command the phone picks up on its next poll), this
 * reads whatever the phone already pushed up on its own periodic schedule.
 */
public class ReadDeviceScreenToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ReadDeviceScreenToolCallback.class);

    private final DeviceDataIngestionService ingestionService;

    public ReadDeviceScreenToolCallback(DeviceDataIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("read_device_screen")
                .description(
                        "Reads the most recently reported screen content of this tenant's paired phone - the foreground "
                                + "app and a text dump of what's currently on screen (not an image). Use this to answer "
                                + "questions like 'what's on my phone screen' or 'what app am I in right now'. The data is only "
                                + "as fresh as the last time the phone reported it - check the returned timestamp.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "device": {"type": "string", "description": "Optional device name or id, if more than one phone is paired. Defaults to the most recently active device."}
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
            Optional<DeviceScreenState> state =
                    ingestionService.latestScreenState(device.get().getId());
            if (state.isEmpty()) {
                return DeviceToolSupport.toJson(
                        Map.of(
                                "device",
                                device.get().getDeviceName(),
                                "note",
                                "No screen state has been reported by this device yet."));
            }
            DeviceScreenState s = state.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("device", device.get().getDeviceName());
            result.put("foregroundApp", s.getForegroundApp());
            result.put("screenText", s.getScreenText());
            result.put("capturedAt", s.getCapturedAt());
            result.put("reportedAt", s.getUpdatedAt());
            return DeviceToolSupport.toJson(result);
        } catch (Exception e) {
            log.warn("read_device_screen tool call failed", e);
            return DeviceToolSupport.errorJson("Could not read device screen: " + e.getMessage());
        }
    }
}
