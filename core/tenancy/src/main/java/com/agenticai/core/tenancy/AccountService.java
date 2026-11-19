package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.dto.AccountResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The logged-in user's own account row behind {@code AccountController}: owns the {@link
 * AppUserRepository} access, the just-in-time {@link AppUser} provisioning fallback and the
 * transactions, and maps to {@link AccountResponse} so the controller never touches the entity.
 */
@Service
public class AccountService {

    /** Identity of the calling user, extracted from the authenticated principal by the controller. */
    public record Caller(String keycloakUserId, String email, String tenantSchema, boolean tenantAdmin) {}

    /** What the avatar upload needs to know about the caller's current avatar. */
    public record AvatarTarget(UUID userId, String currentObjectKey) {}

    private final AppUserRepository appUserRepository;
    private final AppUserProvisioningService provisioningService;

    public AccountService(AppUserRepository appUserRepository) {
        this(appUserRepository, null);
    }

    @Autowired
    public AccountService(AppUserRepository appUserRepository, AppUserProvisioningService provisioningService) {
        this.appUserRepository = appUserRepository;
        this.provisioningService = provisioningService;
    }

    public AccountResponse get(Caller caller) {
        return toResponse(currentUser(caller));
    }

    @Transactional
    public AccountResponse setPhoneNumber(Caller caller, String phoneNumber) {
        AppUser user = currentUser(caller);
        user.setPhoneNumber(phoneNumber);
        appUserRepository.save(user);
        return toResponse(user);
    }

    /** The caller's id and current avatar object key (null when none was ever uploaded). */
    public AvatarTarget avatarTarget(Caller caller) {
        AppUser user = currentUser(caller);
        return new AvatarTarget(user.getId(), user.getAvatarUrl());
    }

    /** The caller's avatar object key, or null when none was ever uploaded. */
    public String avatarObjectKey(Caller caller) {
        return currentUser(caller).getAvatarUrl();
    }

    @Transactional
    public AccountResponse setAvatarObjectKey(Caller caller, String objectKey) {
        AppUser user = currentUser(caller);
        user.setAvatarUrl(objectKey);
        appUserRepository.save(user);
        return toResponse(user);
    }

    private AccountResponse toResponse(AppUser user) {
        String avatarUrl = user.getAvatarUrl() != null ? "/api/account/avatar" : null;
        return new AccountResponse(user.getEmail(), user.getPhoneNumber(), avatarUrl);
    }

    private AppUser currentUser(Caller caller) {
        Optional<AppUser> existing = appUserRepository.findByKeycloakUserId(caller.keycloakUserId());
        if (existing.isPresent()) {
            return existing.get();
        }
        // B10: members created through POST /api/admin/users (or before that path created the row)
        // have no app_user row. Provision it from the verified JWT claims instead of failing every
        // page load with a 500. Idempotent and race-safe - see AppUserProvisioningService.
        if (provisioningService == null) {
            throw new IllegalStateException(
                "No AppUser row for authenticated principal '" + caller.keycloakUserId() + "'.");
        }
        return provisioningService.ensure(
            caller.tenantSchema(), caller.keycloakUserId(), caller.email(), caller.tenantAdmin());
    }
}
