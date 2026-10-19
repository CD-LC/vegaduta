package com.agenticai.core.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The latest known screen content for a paired {@link MobileDevice} - one
 * row per device, always overwritten (see {@code
 * DeviceDataIngestionService#recordScreenState}'s upsert), not a history.
 * {@code screenText} is the Android accessibility tree flattened to text
 * (see {@code AutomationAccessibilityService#dumpScreenText} in the Mobile
 * SDK) - the same node-graph TalkBack/UiAutomator read, not a screenshot
 * image, so it's directly usable as LLM tool-call context without an image
 * model in the loop.
 *
 * <p>An agent asking "what's on this person's screen right now" wants the
 * current state, not a log - unlike {@link DeviceNotification}/{@link
 * DeviceAppUsageEvent}/{@link DeviceSmsMessage}, which are append-only, this
 * table is capped at exactly one row per device by the unique constraint on
 * {@code device_id} (see V55's migration comment).
 */
@Entity
@Table(name = "device_screen_state")
public class DeviceScreenState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private UUID deviceId;

    @Column(name = "screen_text", columnDefinition = "TEXT")
    private String screenText;

    @Column(name = "foreground_app", length = 255)
    private String foregroundApp;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeviceScreenState() {
        // JPA
    }

    public DeviceScreenState(UUID deviceId, String screenText, String foregroundApp, Instant capturedAt) {
        this.deviceId = deviceId;
        this.screenText = screenText;
        this.foregroundApp = foregroundApp;
        this.capturedAt = capturedAt;
        this.updatedAt = Instant.now();
    }

    public void update(String screenText, String foregroundApp, Instant capturedAt) {
        this.screenText = screenText;
        this.foregroundApp = foregroundApp;
        this.capturedAt = capturedAt;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getScreenText() {
        return screenText;
    }

    public String getForegroundApp() {
        return foregroundApp;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
