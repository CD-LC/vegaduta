package com.agenticai.core.devices;

/**
 * Lifecycle of one queued {@link DeviceCommand}. PENDING until the device's
 * next poll picks it up (-&gt; DELIVERED); the device itself later reports
 * COMPLETED or FAILED via {@code DeviceGatewayController}'s result endpoint.
 * There is no in-between "running" state - a poll interval of a few seconds
 * means "delivered" and "the device is now acting on it" are close enough in
 * practice that a fourth state would add bookkeeping without adding anything
 * a caller could usefully act on.
 */
public enum DeviceCommandStatus {
    PENDING,
    DELIVERED,
    COMPLETED,
    FAILED
}
