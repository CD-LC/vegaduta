package com.agenticai.core.devices;

/**
 * Any device-pairing or device-command failure a caller can act on (unknown/
 * expired pairing code, unknown device token, revoked device, unknown
 * command id) - deliberately one exception type with a message, not a
 * hierarchy, since every case here maps to the same HTTP response shape
 * (400/401/404 handled by whichever controller catches it) and none of them
 * need to be caught differently from one another the way, say, {@code
 * ChannelConfigurationException} vs {@code ChannelDeliveryException} do for
 * output-node SKIPPED-vs-fail handling.
 */
public class DeviceException extends RuntimeException {

    public DeviceException(String message) {
        super(message);
    }
}
