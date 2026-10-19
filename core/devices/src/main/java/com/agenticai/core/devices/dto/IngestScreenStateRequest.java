package com.agenticai.core.devices.dto;

import java.time.Instant;

/**
 * {@code screenText} is intentionally unvalidated/nullable - a blank or null
 * screen dump (e.g. the accessibility tree was empty at capture time) is
 * still a meaningful "nothing readable right now" signal, not an error.
 */
public record IngestScreenStateRequest(String screenText, String foregroundApp, Instant capturedAt) {
}
