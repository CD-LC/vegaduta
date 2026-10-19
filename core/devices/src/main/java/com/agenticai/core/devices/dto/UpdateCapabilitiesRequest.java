package com.agenticai.core.devices.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Device-declared capability keys (device-side endpoint, X-Device-Token).
 * The server stores the list verbatim after normalizing; it is the client's
 * honest statement of what it can do, not a grant - command types the device
 * does not recognize are still answered with a FAILED result by the client.
 */
public record UpdateCapabilitiesRequest(
    @Size(max = 32) List<@Size(max = 64) String> capabilities
) {
}
