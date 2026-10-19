package com.agenticai.core.admin.localauth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double for {@link LocalAccountStore} with the same atomicity guarantees as the SQL. */
class InMemoryLocalAccountStore implements LocalAccountStore {

    final Map<String, LocalAccount> accounts = new LinkedHashMap<>();
    final Map<String, RefreshRecord> refresh = new LinkedHashMap<>(); // by id
    final List<SetupRow> setup = new ArrayList<>();

    record SetupRow(String accountId, String hash, Instant expiresAt, boolean consumed) {}

    @Override
    public synchronized long countAccounts() {
        return accounts.size();
    }

    @Override
    public synchronized boolean anySetupPending() {
        return accounts.values().stream().anyMatch(LocalAccount::setupPending);
    }

    @Override
    public synchronized Optional<LocalAccount> findById(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public synchronized Optional<LocalAccount> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        return accounts.values().stream().filter(a -> a.email().toLowerCase(Locale.ROOT).equals(key)).findFirst();
    }

    @Override
    public synchronized List<LocalAccount> listAll() {
        return new ArrayList<>(accounts.values());
    }

    @Override
    public synchronized List<LocalAccount> listByTenant(String tenantId) {
        return accounts.values().stream().filter(a -> tenantId.equals(a.tenantId())).toList();
    }

    @Override
    public synchronized void insert(LocalAccount account) {
        if (findByEmail(account.email()).isPresent()) {
            throw new DuplicateAccountException("duplicate", null);
        }
        accounts.put(account.id(), account);
    }

    @Override
    public synchronized void update(LocalAccount account) {
        accounts.put(account.id(), account);
    }

    @Override
    public synchronized void delete(String id) {
        accounts.remove(id);
        refresh.values().removeIf(r -> r.accountId().equals(id));
    }

    @Override
    public synchronized void touchLogin(String id, Instant at) {}

    @Override
    public synchronized void insertRefresh(RefreshRecord r) {
        refresh.put(r.id(), r);
    }

    @Override
    public synchronized Optional<RefreshRecord> findRefreshByHash(String tokenHash) {
        return refresh.values().stream().filter(r -> r.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public synchronized boolean claimRefresh(String id, Instant at) {
        RefreshRecord r = refresh.get(id);
        if (r == null || r.usedAt() != null || r.revokedAt() != null) {
            return false;
        }
        refresh.put(id, with(r, at, r.revokedAt()));
        return true;
    }

    @Override
    public synchronized void revokeFamily(String familyId, Instant at) {
        refresh.replaceAll((id, r) -> r.familyId().equals(familyId) && r.revokedAt() == null ? with(r, r.usedAt(), at) : r);
    }

    @Override
    public synchronized void revokeAllForAccount(String accountId, Instant at) {
        refresh.replaceAll((id, r) -> r.accountId().equals(accountId) && r.revokedAt() == null ? with(r, r.usedAt(), at) : r);
    }

    @Override
    public synchronized void revokeAllForAccountExcept(String accountId, String keepFamilyId, Instant at) {
        refresh.replaceAll(
                (id, r) -> r.accountId().equals(accountId) && !r.familyId().equals(keepFamilyId) && r.revokedAt() == null ? with(r, r.usedAt(), at) : r);
    }

    @Override
    public synchronized int purgeRefreshExpiredBefore(Instant cutoff) {
        int before = refresh.size();
        refresh.values().removeIf(r -> r.familyExpiresAt().isBefore(cutoff));
        return before - refresh.size();
    }

    @Override
    public synchronized void insertSetupToken(String accountId, String tokenHash, Instant createdAt, Instant expiresAt) {
        setup.replaceAll(r -> r.accountId().equals(accountId) ? new SetupRow(r.accountId(), r.hash(), r.expiresAt(), true) : r);
        setup.add(new SetupRow(accountId, tokenHash, expiresAt, false));
    }

    @Override
    public synchronized Optional<String> findSetupAccount(String tokenHash, Instant now) {
        return setup.stream()
                .filter(r -> r.hash().equals(tokenHash) && !r.consumed() && r.expiresAt().isAfter(now))
                .map(SetupRow::accountId)
                .findFirst();
    }

    @Override
    public synchronized Optional<String> consumeSetupToken(String tokenHash, Instant now) {
        for (int i = 0; i < setup.size(); i++) {
            SetupRow r = setup.get(i);
            if (r.hash().equals(tokenHash) && !r.consumed() && r.expiresAt().isAfter(now)) {
                setup.set(i, new SetupRow(r.accountId(), r.hash(), r.expiresAt(), true));
                return Optional.of(r.accountId());
            }
        }
        return Optional.empty();
    }

    private static RefreshRecord with(RefreshRecord r, Instant usedAt, Instant revokedAt) {
        return new RefreshRecord(
                r.id(), r.accountId(), r.familyId(), r.tokenHash(), r.authMethod(), r.authTime(), r.issuedAt(),
                r.expiresAt(), r.familyExpiresAt(), usedAt, revokedAt);
    }

    static LocalAccount account(String email, String password, LocalPasswords passwords) {
        return new LocalAccount(
                UUID.randomUUID().toString(), email, "Ada", "Lovelace",
                password == null ? null : passwords.hash(password),
                false, true, false, "t_abc", java.util.Set.of("tenant-admin"), Map.of(), Instant.now(), null);
    }
}
