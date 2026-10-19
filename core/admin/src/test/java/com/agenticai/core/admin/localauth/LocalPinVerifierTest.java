package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.LocalCredentialStore.DeviceRecord;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalPinVerifierTest {

    static final String PIN = "482916";

    LocalAuthServiceTest.MutableClock clock = new LocalAuthServiceTest.MutableClock();
    InMemoryLocalAccountStore accounts = new InMemoryLocalAccountStore();
    InMemoryLocalCredentialStore credentials = new InMemoryLocalCredentialStore();
    LocalPasswords passwords = new LocalPasswords();
    LocalPins pins = new LocalPins(passwords);
    LocalDevices devices = new LocalDevices(credentials, pins, clock);
    LocalPinVerifier verifier = new LocalPinVerifier(credentials, accounts, pins, clock);
    LocalAccount owner;
    LocalDevices.Enrolled device;

    @BeforeEach
    void setUp() {
        owner = InMemoryLocalAccountStore.account("owner@example.test", null, passwords);
        accounts.insert(owner);
        device = devices.enrol(owner.id(), PIN, "Kitchen laptop");
    }

    private Optional<String> verify(String pin, String secret) {
        return verifier.verify(device.deviceId(), Map.of("pin", pin, "deviceSecret", secret));
    }

    private DeviceRecord row() {
        return credentials.devices.get(device.deviceId());
    }

    @Test
    void theRightPinWithTheRightSecretSignsTheAccountIn() {
        assertThat(verify(PIN, device.deviceSecret())).contains(owner.id());
        assertThat(row().lastUsedAt()).isNotNull();
        assertThat(verifier.type()).isEqualTo("pin");
        assertThat(verifier.authenticationMethod()).isEqualTo("pin");
    }

    @Test
    void enrolmentStoresOnlyAHashAndANamedDevice() {
        assertThat(row().pinHash()).doesNotContain(PIN).doesNotContain(device.deviceSecret());
        assertThat(row().label()).isEqualTo("Kitchen laptop");
        assertThat(device.deviceSecret()).hasSize(43);
        assertThat(devices.enrol(owner.id(), PIN, "  ").deviceId()).isNotEqualTo(device.deviceId());
        assertThat(credentials.devices.values()).anyMatch(d -> d.label().equals("This device"));
    }

    @Test
    void aWrongPinIsRefusedAndCounted() {
        assertThat(verify("111112", device.deviceSecret())).isEmpty();
        assertThat(verify("111113", device.deviceSecret())).isEmpty();

        assertThat(row().failedAttempts()).isEqualTo(2);
        assertThat(row().revokedAt()).isNull();
    }

    @Test
    void aWrongSecretWithTheRightPinIsRefusedAndCounted() {
        assertThat(verify(PIN, pins.newDeviceSecret())).isEmpty();

        assertThat(row().failedAttempts()).isEqualTo(1);
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        for (int i = 0; i < 9; i++) {
            assertThat(verify("111112", device.deviceSecret())).isEmpty();
        }
        assertThat(row().failedAttempts()).isEqualTo(9);

        assertThat(verify(PIN, device.deviceSecret())).contains(owner.id());
        assertThat(row().failedAttempts()).isZero();

        for (int i = 0; i < 9; i++) {
            assertThat(verify("111112", device.deviceSecret())).isEmpty();
        }
        assertThat(row().revokedAt()).as("nine more is still below the limit").isNull();
    }

    @Test
    void theTenthConsecutiveFailureRevokesTheDevice() {
        for (int i = 0; i < 9; i++) {
            assertThat(verify("111112", device.deviceSecret())).isEmpty();
        }
        assertThat(row().revokedAt()).isNull();

        assertThat(verify("111112", device.deviceSecret())).as("the tenth is an ordinary refusal").isEmpty();

        assertThat(row().revokedAt()).isNotNull();
        assertThat(devices.active(owner.id())).isEmpty();
    }

    @Test
    void aRevokedDeviceIsTreatedLikeAnUnknownOneUnlessTheCallerProvesItHoldsIt() {
        for (int i = 0; i < 10; i++) {
            verify("111112", device.deviceSecret());
        }
        int failedBefore = row().failedAttempts();

        assertThat(verify("111112", device.deviceSecret())).as("wrong pin on a revoked device").isEmpty();
        assertThat(verify(PIN, pins.newDeviceSecret())).as("wrong secret on a revoked device").isEmpty();
        assertThat(row().failedAttempts()).as("a revoked device stops counting").isEqualTo(failedBefore);

        assertThatThrownBy(() -> verify(PIN, device.deviceSecret()))
                .as("pin AND secret verified, so the holder is told to drop the binding")
                .isInstanceOfSatisfying(
                        LocalAuthException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(401);
                            assertThat(e.code()).isEqualTo("device_revoked");
                        });
    }

    @Test
    void aGuessThatReadTheDeviceBeforeItWasRevokedDoesNotSignIn() {
        // The row is read (active), the slow hash runs, and meanwhile a concurrent request spends the
        // tenth failure and revokes the device. The success path must re-check atomically.
        InMemoryLocalCredentialStore racing =
                new InMemoryLocalCredentialStore() {
                    @Override
                    public synchronized Optional<DeviceRecord> findDevice(String id) {
                        Optional<DeviceRecord> snapshot = super.findDevice(id);
                        super.revokeDevice(snapshot.orElseThrow().accountId(), id, clock.instant());
                        return snapshot;
                    }
                };
        racing.insertDevice(row());
        LocalPinVerifier racingVerifier = new LocalPinVerifier(racing, accounts, pins, clock);

        assertThatThrownBy(() -> racingVerifier.verify(device.deviceId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret())))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("device_revoked"));
    }

    @Test
    void aRemovedDeviceCannotSignInEvenWithTheRightPinAndSecret() {
        devices.revoke(owner.id(), device.deviceId());

        assertThatThrownBy(() -> verify(PIN, device.deviceSecret())).isInstanceOf(LocalAuthException.class);
    }

    @Test
    void unknownMalformedAndMissingInputsAreAllJustEmpty() {
        assertThat(verifier.verify(InMemoryLocalCredentialStore.newId(), Map.of("pin", PIN, "deviceSecret", device.deviceSecret()))).isEmpty();
        assertThat(verifier.verify("not-a-uuid", Map.of("pin", PIN, "deviceSecret", device.deviceSecret()))).isEmpty();
        assertThat(verifier.verify(device.deviceId(), Map.of("pin", PIN))).isEmpty();
        assertThat(verifier.verify(device.deviceId(), Map.of("deviceSecret", device.deviceSecret()))).isEmpty();
        assertThat(verifier.verify(device.deviceId(), null)).isEmpty();
    }

    @Test
    void aDisabledOrPendingAccountCannotUseItsDevice() {
        accounts.update(owner.withEnabled(false));
        assertThat(verify(PIN, device.deviceSecret())).isEmpty();

        accounts.update(owner.withPassword(null, false, true));
        assertThat(verify(PIN, device.deviceSecret())).isEmpty();
    }

    @Test
    void enrolmentEnforcesThePinPolicyAndALimit() {
        assertThatThrownBy(() -> devices.enrol(owner.id(), "123456", "x"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("weak_pin"));
        for (int i = credentials.listActiveDevices(owner.id()).size(); i < LocalDevices.MAX_PER_ACCOUNT; i++) {
            devices.enrol(owner.id(), PIN, "d" + i);
        }
        assertThatThrownBy(() -> devices.enrol(owner.id(), PIN, "one too many"))
                .isInstanceOfSatisfying(LocalAuthException.class, e -> assertThat(e.code()).isEqualTo("limit_reached"));
    }
}
