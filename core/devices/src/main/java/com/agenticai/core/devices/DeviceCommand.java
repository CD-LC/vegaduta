package com.agenticai.core.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One queued instruction for a paired {@link MobileDevice} - e.g. {@code
 * commandType="TAP"} with a JSON payload of coordinates, or {@code
 * commandType="TYPE_TEXT"} with the text to enter. The Android client (an
 * AccessibilityService) interprets {@code commandType}; this module is
 * deliberately agnostic about what the string means, exactly the way {@code
 * EmailOutputService}/{@code TelegramOutputService} don't know or care what
 * an Output node's body text says.
 *
 * <p>{@code workflowRunId} is nullable and only ever set by {@code
 * WorkflowExecutionEngine#executeDeviceControlNode} - a command can also be
 * queued directly via the authenticated {@code DeviceController} without any
 * workflow involved at all (a "send this device a one-off command" utility
 * action), so the workflow link is informational/correlation-only, not a
 * foreign key this entity depends on existing.
 */
@Entity
@Table(name = "device_command")
public class DeviceCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "command_type", nullable = false, length = 64)
    private String commandType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceCommandStatus status;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DeviceCommand() {
        // JPA
    }

    public DeviceCommand(UUID deviceId, String commandType, String payload, UUID workflowRunId) {
        this.deviceId = deviceId;
        this.commandType = commandType;
        this.payload = payload;
        this.workflowRunId = workflowRunId;
        this.status = DeviceCommandStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markDelivered() {
        this.status = DeviceCommandStatus.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    public void complete(DeviceCommandStatus status, String result) {
        this.status = status;
        this.result = result;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getCommandType() {
        return commandType;
    }

    public String getPayload() {
        return payload;
    }

    public DeviceCommandStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
