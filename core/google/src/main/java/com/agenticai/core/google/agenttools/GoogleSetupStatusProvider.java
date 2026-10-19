package com.agenticai.core.google.agenttools;

import com.agenticai.core.agentspi.SetupStatusProvider;
import com.agenticai.core.google.GoogleOAuthService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Tells the Assistant's setup guide whether a Google account is linked (which is what turns on the Google tools). */
@Component
public class GoogleSetupStatusProvider implements SetupStatusProvider {

    private final GoogleOAuthService google;

    public GoogleSetupStatusProvider(GoogleOAuthService google) {
        this.google = google;
    }

    @Override
    public Map<String, Status> statuses() {
        boolean connected = google.status().isPresent();
        return Map.of("google", connected
                ? new Status(State.READY, "A Google account is connected; Calendar, Gmail, Drive and Sheets tools are available.")
                : new Status(State.NOT_SET_UP, "No Google account is connected."));
    }
}
