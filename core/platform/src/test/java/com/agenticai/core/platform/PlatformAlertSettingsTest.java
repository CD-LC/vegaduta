package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The singleton alert-settings row: fixed id, the V84 default threshold, and the audit stamp every
 * update leaves behind.
 */
class PlatformAlertSettingsTest {

    @Test
    void theSingletonRowHasTheFixedIdAndDefaultThreshold() {
        PlatformAlertSettings settings =
                new PlatformAlertSettings(PlatformAlertSettings.DEFAULT_FALLBACK_FAILURE_THRESHOLD);

        assertEquals(PlatformAlertSettings.SINGLETON_ID, settings.getId());
        assertEquals(10, PlatformAlertSettings.DEFAULT_FALLBACK_FAILURE_THRESHOLD);
        assertEquals(10, settings.getFallbackFailureThreshold());
        assertNotNull(settings.getUpdatedAt());
        assertNull(settings.getUpdatedBy());
    }

    @Test
    void updatingTheThresholdRestampsWhoAndWhen() {
        PlatformAlertSettings settings = new PlatformAlertSettings(10);

        settings.setFallbackFailureThreshold(25, "ops@example.com");

        assertEquals(25, settings.getFallbackFailureThreshold());
        assertEquals("ops@example.com", settings.getUpdatedBy());
        assertNotNull(settings.getUpdatedAt());
    }
}
