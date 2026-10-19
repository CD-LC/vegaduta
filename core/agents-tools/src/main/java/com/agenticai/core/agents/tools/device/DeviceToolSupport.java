package com.agenticai.core.agents.tools.device;

import com.agenticai.core.devices.DeviceDataIngestionService;
import com.agenticai.core.devices.MobileDevice;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;

/**
 * Small shared helpers for the four {@code read_device_*} tool callbacks (#157 follow-up) - device
 * resolution from the optional {@code device} argument, and a consistent "no paired device" error
 * shape, so each callback's {@code call()} body only has to handle its own data type.
 */
final class DeviceToolSupport {

    static final ObjectMapper MAPPER = JsonMappers.plain();

    private DeviceToolSupport() {}

    /**
     * @param deviceArg the tool call's optional {@code device} argument (a device id or device
     *     name) - null/blank defaults to the tenant's most-recently-active non-revoked device, see
     *     {@code DeviceDataIngestionService#resolveDevice}'s javadoc.
     */
    static Optional<MobileDevice> resolveDevice(
            DeviceDataIngestionService service, String deviceArg) {
        return service.resolveDevice(deviceArg);
    }

    static String noDeviceError() {
        return toJson(
                Map.of(
                        "error",
                        "No paired device found. Pair a phone from the Devices page (or have it sign in via the "
                                + "Mobile SDK app), or pass a specific 'device' name/id if more than one is paired."));
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return errorJson("Failed to serialize device tool result: " + e.getMessage());
        }
    }

    /**
     * Direct feedback ("Tool output from 'search_knowledge_base' failed validation... output looked
     * like JSON but failed to parse") - same bug class fixed in KnowledgeSearchToolCallback (see
     * its errorJson() javadoc): the four read_device_* callbacks were hand-building "{\"error\":
     * \"...\"}" via string concatenation, which breaks if the interpolated exception message
     * contains a raw '"'. Centralized here since all four callbacks in this package already share
     * this class.
     */
    static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(toolInput, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
