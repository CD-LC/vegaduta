package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalCredentialStore.DeviceRecord;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Enrolling, listing and removing the device bindings that make a PIN usable. Enrolment returns the
 * device secret ONCE; only a hash of {@code pin + secret} is stored (see {@link LocalPins}).
 */
@Component
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalDevices {

    public static final int MAX_PER_ACCOUNT = 20;

    /** What the browser must keep: the device id and the secret that goes with every PIN entry. */
    public record Enrolled(String deviceId, String deviceSecret) {}

    private final LocalCredentialStore store;
    private final LocalPins pins;
    private final Clock clock;

    @Autowired
    public LocalDevices(LocalCredentialStore store, LocalPins pins) {
        this(store, pins, Clock.systemUTC());
    }

    LocalDevices(LocalCredentialStore store, LocalPins pins, Clock clock) {
        this.store = store;
        this.pins = pins;
        this.clock = clock;
    }

    /** @throws LocalAuthException {@code weak_pin} (400) or {@code limit_reached} (409) */
    public Enrolled enrol(String accountId, String pin, String label) {
        String violation = LocalPins.violation(pin);
        if (violation != null) {
            throw LocalAuthException.weakPin(violation);
        }
        if (store.listActiveDevices(accountId).size() >= MAX_PER_ACCOUNT) {
            throw LocalAuthException.limitReached("This account already has the maximum number of devices.");
        }
        String secret = pins.newDeviceSecret();
        String id = UUID.randomUUID().toString();
        store.insertDevice(
                new DeviceRecord(
                        id,
                        accountId,
                        LocalPasskeyService.cleanLabel(label, "This device"),
                        pins.hash(pin, secret),
                        0,
                        clock.instant(),
                        null,
                        null));
        return new Enrolled(id, secret);
    }

    public List<DeviceRecord> active(String accountId) {
        return store.listActiveDevices(accountId);
    }

    /** True when the device existed, belonged to the account and was active. */
    public boolean revoke(String accountId, String deviceId) {
        return store.revokeDevice(accountId, deviceId, clock.instant());
    }
}
