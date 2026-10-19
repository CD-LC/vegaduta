package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalCredentialStore.DeviceRecord;
import com.agenticai.core.security.localauth.LocalCredentialVerifier;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Device-PIN sign-in. The identifier is the device id; the credentials are {@code pin} and {@code
 * deviceSecret}. Always does one hash verification (against a dummy for an unknown device), so the
 * answer time does not say whether the device exists.
 *
 * <p>A wrong PIN counts against the DEVICE; the tenth consecutive one revokes it, and a correct one
 * resets the count. A revoked device answers {@code invalid_credentials} like an unknown one, except
 * when the PIN and secret were both correct: then the caller provably holds the device, and is told
 * {@code device_revoked} so the browser can drop its binding instead of retrying.
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalPinVerifier implements LocalCredentialVerifier {

    public static final String TYPE = "pin";

    private final LocalCredentialStore credentials;
    private final LocalAccountStore accounts;
    private final LocalPins pins;
    private final Clock clock;

    @Autowired
    public LocalPinVerifier(LocalCredentialStore credentials, LocalAccountStore accounts, LocalPins pins) {
        this(credentials, accounts, pins, Clock.systemUTC());
    }

    LocalPinVerifier(LocalCredentialStore credentials, LocalAccountStore accounts, LocalPins pins, Clock clock) {
        this.credentials = credentials;
        this.accounts = accounts;
        this.pins = pins;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String authenticationMethod() {
        return "pin";
    }

    @Override
    public Optional<String> verify(String identifier, Map<String, String> submitted) {
        String pin = submitted == null ? null : submitted.get("pin");
        String secret = submitted == null ? null : submitted.get("deviceSecret");
        DeviceRecord device = credentials.findDevice(identifier).orElse(null);
        boolean correct = pins.matches(pin, secret, device == null ? null : device.pinHash());
        if (device == null) {
            return Optional.empty();
        }
        if (!correct) {
            if (device.revokedAt() == null) {
                credentials.recordDeviceFailure(device.id(), LocalPins.MAX_FAILURES, clock.instant());
            }
            return Optional.empty();
        }
        if (device.revokedAt() != null) {
            throw LocalAuthException.deviceRevoked();
        }
        LocalAccount account = accounts.findById(device.accountId()).orElse(null);
        if (account == null || !account.enabled() || account.setupPending()) {
            return Optional.empty();
        }
        if (!credentials.recordDeviceSuccess(device.id(), clock.instant())) {
            // The device row was read before a concurrent request spent its last allowed failure and
            // revoked it. The conditional update is the authority, not that earlier snapshot.
            throw LocalAuthException.deviceRevoked();
        }
        return Optional.of(account.id());
    }
}
