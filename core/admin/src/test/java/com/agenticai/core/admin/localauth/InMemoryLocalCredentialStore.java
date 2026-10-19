package com.agenticai.core.admin.localauth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double for {@link LocalCredentialStore} with the same atomicity guarantees as the SQL. */
class InMemoryLocalCredentialStore implements LocalCredentialStore {

    final Map<String, PasskeyRecord> passkeys = new LinkedHashMap<>(); // by id
    final Map<String, DeviceRecord> devices = new LinkedHashMap<>(); // by id
    final List<CodeRow> codes = new ArrayList<>();

    record CodeRow(String accountId, String hash, Instant createdAt, Instant usedAt) {}

    @Override
    public synchronized void insertPasskey(PasskeyRecord p) {
        if (passkeys.values().stream().anyMatch(x -> x.credentialId().equals(p.credentialId()))) {
            throw new DuplicateCredentialException("duplicate", null);
        }
        passkeys.put(p.id(), p);
    }

    @Override
    public synchronized Optional<PasskeyRecord> findPasskeyByCredentialId(String credentialId) {
        return passkeys.values().stream().filter(p -> p.credentialId().equals(credentialId)).findFirst();
    }

    @Override
    public synchronized List<PasskeyRecord> listPasskeys(String accountId) {
        return passkeys.values().stream().filter(p -> p.accountId().equals(accountId)).toList();
    }

    @Override
    public synchronized boolean recordPasskeyUse(String id, long signCount, boolean backupState, Instant at) {
        PasskeyRecord p = passkeys.get(id);
        if (p == null || p.signCount() > signCount) {
            return false;
        }
        passkeys.put(
                id,
                new PasskeyRecord(
                        p.id(), p.accountId(), p.credentialId(), p.attestedCredentialData(), signCount, p.uvInitialized(),
                        p.backupEligible(), backupState, p.transports(), p.rpId(), p.label(), p.createdAt(), at));
        return true;
    }

    @Override
    public synchronized boolean deletePasskey(String accountId, String id) {
        PasskeyRecord p = passkeys.get(id);
        if (p == null || !p.accountId().equals(accountId)) {
            return false;
        }
        passkeys.remove(id);
        return true;
    }

    @Override
    public synchronized void insertDevice(DeviceRecord d) {
        devices.put(d.id(), d);
    }

    @Override
    public synchronized Optional<DeviceRecord> findDevice(String id) {
        return Optional.ofNullable(id == null ? null : devices.get(id));
    }

    @Override
    public synchronized List<DeviceRecord> listActiveDevices(String accountId) {
        return devices.values().stream().filter(d -> d.accountId().equals(accountId) && d.revokedAt() == null).toList();
    }

    @Override
    public synchronized boolean revokeDevice(String accountId, String id, Instant at) {
        DeviceRecord d = devices.get(id);
        if (d == null || !d.accountId().equals(accountId) || d.revokedAt() != null) {
            return false;
        }
        devices.put(id, new DeviceRecord(d.id(), d.accountId(), d.label(), d.pinHash(), d.failedAttempts(), d.createdAt(), d.lastUsedAt(), at));
        return true;
    }

    @Override
    public synchronized int recordDeviceFailure(String id, int maxFailures, Instant at) {
        DeviceRecord d = devices.get(id);
        if (d == null || d.revokedAt() != null) {
            return 0;
        }
        int failed = d.failedAttempts() + 1;
        devices.put(id, new DeviceRecord(d.id(), d.accountId(), d.label(), d.pinHash(), failed, d.createdAt(), d.lastUsedAt(), failed >= maxFailures ? at : null));
        return failed;
    }

    @Override
    public synchronized boolean recordDeviceSuccess(String id, Instant at) {
        DeviceRecord d = devices.get(id);
        if (d != null && d.revokedAt() == null) {
            devices.put(id, new DeviceRecord(d.id(), d.accountId(), d.label(), d.pinHash(), 0, d.createdAt(), at, null));
            return true;
        }
        return false;
    }

    @Override
    public synchronized void replaceRecoveryCodes(String accountId, List<String> codeHashes, Instant at) {
        codes.removeIf(c -> c.accountId().equals(accountId));
        codeHashes.forEach(h -> codes.add(new CodeRow(accountId, h, at, null)));
    }

    @Override
    public synchronized Optional<String> consumeRecoveryCode(String codeHash, Instant at) {
        for (int i = 0; i < codes.size(); i++) {
            CodeRow c = codes.get(i);
            if (c.hash().equals(codeHash) && c.usedAt() == null) {
                codes.set(i, new CodeRow(c.accountId(), c.hash(), c.createdAt(), at));
                return Optional.of(c.accountId());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized int countUnusedRecoveryCodes(String accountId) {
        return (int) codes.stream().filter(c -> c.accountId().equals(accountId) && c.usedAt() == null).count();
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
