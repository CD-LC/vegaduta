package com.agenticai.core.agents.tools.device;

import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.devices.DeviceSmsMessage;
import com.agenticai.core.devices.MobileDevice;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * #157 follow-up - recent SMS messages the Mobile SDK's SmsReader reported for a paired phone. Same
 * "native ToolCallback, tenant-scoped read" pattern as the other three {@code read_device_*} tools;
 * no extra approval gate beyond the platform's existing tool-call guardrails/logging (every call
 * still goes through {@code AgentToolProvider#guardAndLog} like every other tool), consistent with
 * how Gmail/Drive reads elsewhere in this platform aren't approval-gated either - the approval
 * policy list governs sends and deletes, not reads.
 */
public class ReadDeviceSmsToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ReadDeviceSmsToolCallback.class);

    private final DeviceDataIngestionService ingestionService;

    public ReadDeviceSmsToolCallback(DeviceDataIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("read_device_sms")
                .description(
                        "Reads recent SMS messages (sender/recipient number, body, direction) on this tenant's paired "
                                + "phone, newest first. Use this to answer questions like 'what texts have I gotten' or 'read "
                                + "my SMS messages'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "device": {"type": "string", "description": "Optional device name or id, if more than one phone is paired."},
                        "limit": {"type": "integer", "description": "Max messages to return, newest first (default 20, max 100)"}
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
            List<DeviceSmsMessage> messages =
                    ingestionService.recentSms(device.get().getId(), limit);
            List<Map<String, Object>> items =
                    messages.stream()
                            .map(
                                    m ->
                                            (Map<String, Object>)
                                                    Map.<String, Object>of(
                                                            "address",
                                                                    m.getAddress() == null
                                                                            ? ""
                                                                            : m.getAddress(),
                                                            "body",
                                                                    m.getBody() == null
                                                                            ? ""
                                                                            : m.getBody(),
                                                            "direction", m.getDirection().name(),
                                                            "smsTimestamp",
                                                                    String.valueOf(
                                                                            m.getSmsTimestamp()),
                                                            "receivedAt",
                                                                    String.valueOf(
                                                                            m.getReceivedAt())))
                            .toList();
            return DeviceToolSupport.toJson(
                    Map.of(
                            "device",
                            device.get().getDeviceName(),
                            "messages",
                            items,
                            "note",
                            items.isEmpty() ? "No SMS messages have been reported yet." : ""));
        } catch (Exception e) {
            log.warn("read_device_sms tool call failed", e);
            return DeviceToolSupport.errorJson("Could not read device SMS: " + e.getMessage());
        }
    }
}
