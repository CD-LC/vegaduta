package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.CreateProfileRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.ProfileView;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.UpdateProfileRequest;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Browser profiles (app.browser.profiles.enabled, default OFF): named, domain-scoped logins a
 * browser agent reuses across runs - Playwright {@code storageState} (cookies + localStorage)
 * encrypted at rest with the platform's {@link EncryptionService} (AES-256-GCM, the same helper
 * every provider key and channel secret uses), size-capped, TTL-able, and never returned by any
 * API.
 *
 * <p>Two halves. The management half ({@code list/create/update/delete/attach/detach}) backs {@link
 * BrowserProfileController}; its rules: TENANT-scoped profiles are managed by a manager of agents
 * ({@code PERM_MANAGE_AGENTS}), USER-scoped ones only by their owner (and are invisible to everyone
 * else), and a USER-scoped profile may only be attached to an agent its owner created (otherwise
 * any caller of a shared agent would act with the owner's login). The runtime half ({@link
 * #loadForSession}, {@link #saveState}) is called by {@link BrowserProfileSessionLifecycle} when a
 * headless session starts and ends.
 *
 * <p>Scoping. The allowed domains are recorded at creation; saved state is filtered to them on save
 * AND on load ({@link BrowserStorageState#filterToDomains}) and the session guard refuses top-level
 * navigation outside them while a profile is attached. Everything is behind the flag: when off,
 * every management call answers 404 and the runtime half returns nothing, so no profile row is ever
 * read or written.
 */
@Service
public class BrowserProfileService {

    private static final Logger log = LoggerFactory.getLogger(BrowserProfileService.class);

    private static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";
    private static final int MAX_NAME_LENGTH = 120;

    /** What {@link #saveState} did, for the lifecycle's log line. */
    public enum SaveResult {
        SAVED,
        EMPTY,
        TOO_LARGE,
        NOT_FOUND,
        DISABLED
    }

    /**
     * A profile resolved for a starting session: its id, scope and (possibly absent) decrypted,
     * domain-filtered state.
     */
    public record LoadedProfile(
            UUID profileId, String name, List<String> allowedDomains, String storageStateJson) {

        @Override
        public String toString() {
            return "LoadedProfile[profileId="
                    + profileId
                    + ", hasState="
                    + (storageStateJson != null)
                    + "]";
        }
    }

    private final BrowserProfileRepository profiles;
    private final BrowserProfileAgentLinkRepository links;
    private final EncryptionService encryption;
    private final ObjectProvider<AgentService> agentService;
    private final boolean enabled;
    private final int maxStateBytes;
    private final int maxPerTenant;
    private final int defaultTtlDays;
    private final int maxTtlDays;
    private final boolean restrictNavigation;
    private final Clock clock;

    @Autowired
    public BrowserProfileService(
            BrowserProfileRepository profiles,
            BrowserProfileAgentLinkRepository links,
            EncryptionService encryption,
            ObjectProvider<AgentService> agentService,
            @Value("${app.browser.profiles.enabled:false}") boolean enabled,
            @Value("${app.browser.profiles.max-state-bytes:262144}") int maxStateBytes,
            @Value("${app.browser.profiles.max-per-tenant:20}") int maxPerTenant,
            @Value("${app.browser.profiles.default-ttl-days:30}") int defaultTtlDays,
            @Value("${app.browser.profiles.max-ttl-days:365}") int maxTtlDays,
            @Value("${app.browser.profiles.restrict-navigation:true}") boolean restrictNavigation) {
        this(
                profiles,
                links,
                encryption,
                agentService,
                enabled,
                maxStateBytes,
                maxPerTenant,
                defaultTtlDays,
                maxTtlDays,
                restrictNavigation,
                Clock.systemUTC());
    }

    /** Test seam: fixed clock. */
    BrowserProfileService(
            BrowserProfileRepository profiles,
            BrowserProfileAgentLinkRepository links,
            EncryptionService encryption,
            ObjectProvider<AgentService> agentService,
            boolean enabled,
            int maxStateBytes,
            int maxPerTenant,
            int defaultTtlDays,
            int maxTtlDays,
            boolean restrictNavigation,
            Clock clock) {
        this.profiles = profiles;
        this.links = links;
        this.encryption = encryption;
        this.agentService = agentService;
        this.enabled = enabled;
        this.maxStateBytes = Math.max(1_024, maxStateBytes);
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.defaultTtlDays = Math.max(1, defaultTtlDays);
        this.maxTtlDays = Math.max(this.defaultTtlDays, maxTtlDays);
        this.restrictNavigation = restrictNavigation;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when top-level navigation outside a profile's domains is refused while it is attached.
     */
    public boolean restrictNavigation() {
        return restrictNavigation;
    }

    int maxStateBytes() {
        return maxStateBytes;
    }

    // ---- management -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProfileView> list() {
        requireEnabled();
        String subject = subject();
        Map<UUID, List<UUID>> attachments =
                links.findAll().stream()
                        .collect(
                                Collectors.groupingBy(
                                        BrowserProfileAgentLink::getProfileId,
                                        Collectors.mapping(
                                                BrowserProfileAgentLink::getAgentId,
                                                Collectors.toList())));
        Instant now = clock.instant();
        // A manager of agents sees every agent anyway; anyone else only learns the ids of agents
        // they can see.
        Set<UUID> visibleAgents = attachments.isEmpty() || isManager() ? null : visibleAgentIds();
        return profiles.findAllByOrderByNameAsc().stream()
                .filter(p -> canSee(p, subject))
                .map(
                        p ->
                                view(
                                        p,
                                        subject,
                                        now,
                                        visibleAttachments(
                                                p,
                                                attachments.getOrDefault(p.getId(), List.of()),
                                                visibleAgents)))
                .toList();
    }

    /**
     * Attached agent ids the caller may see; {@code visibleAgents == null} means "no filtering
     * needed".
     */
    private static List<UUID> visibleAttachments(
            BrowserProfile profile, List<UUID> attached, Set<UUID> visibleAgents) {
        // A USER profile can only be attached to an agent its owner created, so its owner sees all
        // of them.
        if (visibleAgents == null || BrowserProfile.SCOPE_USER.equals(profile.getScope())) {
            return attached;
        }
        return attached.stream().filter(visibleAgents::contains).toList();
    }

    private Set<UUID> visibleAgentIds() {
        AgentService agents = agentService.getIfAvailable();
        if (agents == null) {
            return Set.of();
        }
        return agents.listAll().stream().map(AgentResponse::id).collect(Collectors.toSet());
    }

    @Transactional
    public ProfileView create(CreateProfileRequest request) {
        requireEnabled();
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        String name = cleanName(request.name());
        List<String> domains = BrowserStorageState.normalizeDomains(request.allowedDomains());
        String scope = parseScope(request.scope());
        String subject = subject();
        if (BrowserProfile.SCOPE_USER.equals(scope)) {
            if (subject == null) {
                throw new IllegalArgumentException("a personal profile needs a signed-in user");
            }
        } else if (!isManager()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Managing shared browser profiles needs the manage-agents permission.");
        }
        int ttl = ttlOrDefault(request.ttlDays());
        String owner = BrowserProfile.SCOPE_USER.equals(scope) ? subject : null;
        boolean duplicate =
                owner == null
                        ? profiles.existsByOwnerSubjectIsNullAndNameIgnoreCase(name)
                        : profiles.existsByOwnerSubjectAndNameIgnoreCase(owner, name);
        if (duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A browser profile named '" + name + "' already exists.");
        }
        if (profiles.count() >= maxPerTenant) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This workspace already has the maximum of "
                            + maxPerTenant
                            + " browser profiles - delete one first.");
        }
        BrowserProfile saved =
                profiles.save(
                        new BrowserProfile(
                                name, scope, owner, domains, ttl, subject, clock.instant()));
        return view(saved, subject, clock.instant(), List.of());
    }

    @Transactional
    public ProfileView update(UUID id, UpdateProfileRequest request) {
        BrowserProfile profile = requireManageable(id);
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        Instant now = clock.instant();
        if (request.name() != null) {
            String name = cleanName(request.name());
            if (!name.equalsIgnoreCase(profile.getName())) {
                boolean duplicate =
                        profile.getOwnerSubject() == null
                                ? profiles.existsByOwnerSubjectIsNullAndNameIgnoreCase(name)
                                : profiles.existsByOwnerSubjectAndNameIgnoreCase(
                                        profile.getOwnerSubject(), name);
                if (duplicate) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "A browser profile named '" + name + "' already exists.");
                }
            }
            profile.rename(name, now);
        }
        if (request.allowedDomains() != null) {
            List<String> domains = BrowserStorageState.normalizeDomains(request.allowedDomains());
            profile.setAllowedDomains(domains, now);
            // Narrowing the scope must not leave state for a domain that is no longer allowed.
            if (profile.hasState()) {
                refilterStoredState(profile, domains, now);
            }
        }
        if (request.ttlDays() != null) {
            profile.setTtlDays(ttlOrDefault(request.ttlDays()), now);
        }
        BrowserProfile saved = profiles.save(profile);
        return view(saved, subject(), now, attachedAgents(id));
    }

    @Transactional
    public void delete(UUID id) {
        BrowserProfile profile = requireManageable(id);
        // The agent_browser_profile FK is ON DELETE CASCADE; deleting the links first keeps the JPA
        // view consistent too.
        links.deleteAll(links.findByProfileId(id));
        profiles.delete(profile);
    }

    /**
     * Forgets the saved login but keeps the profile and its attachments (a "sign this profile
     * out").
     */
    @Transactional
    public void clearState(UUID id) {
        BrowserProfile profile = requireManageable(id);
        profile.clearState(clock.instant());
        profiles.save(profile);
    }

    @Transactional
    public ProfileView attach(UUID profileId, UUID agentId) {
        BrowserProfile profile = requireManageable(profileId);
        AgentService agents = agentService.getIfAvailable();
        if (agents == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Agents are not available.");
        }
        // EDITOR on the agent, answered 404 when the caller cannot even see it
        // (ResourceAccessDeniedException).
        Agent agent = agents.requireAccess(agentId, PermissionLevel.EDITOR);
        if (BrowserProfile.SCOPE_USER.equals(profile.getScope())
                && (agent.getCreatedBy() == null
                        || !agent.getCreatedBy().equals(profile.getOwnerSubject()))) {
            throw new IllegalArgumentException(
                    "A personal browser profile can only be attached to an agent you created - anyone who can run a shared agent would act with your login.");
        }
        Instant now = clock.instant();
        BrowserProfileAgentLink link =
                links.findById(agentId)
                        .map(
                                existing -> {
                                    existing.repoint(profileId, subject(), now);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new BrowserProfileAgentLink(
                                                agentId, profileId, subject(), now));
        links.save(link);
        return view(profile, subject(), now, attachedAgents(profileId));
    }

    @Transactional
    public void detach(UUID profileId, UUID agentId) {
        requireManageable(profileId);
        AgentService agents = agentService.getIfAvailable();
        if (agents != null) {
            agents.requireAccess(agentId, PermissionLevel.EDITOR);
        }
        links.findById(agentId)
                .filter(link -> link.getProfileId().equals(profileId))
                .ifPresent(links::delete);
    }

    // ---- runtime (called on the Playwright thread with TenantContext set) -----------

    /**
     * The profile attached to {@code agentId}, with its decrypted, domain-filtered state when it
     * has a live one. Empty when the feature is off, nothing is attached, or the row is gone. Never
     * throws: a profile that cannot be loaded (key mismatch, corrupt state) yields a profile with
     * no state, so the session simply starts clean.
     */
    @Transactional
    public Optional<LoadedProfile> loadForSession(UUID agentId) {
        if (!enabled || agentId == null) {
            return Optional.empty();
        }
        try {
            Optional<BrowserProfileAgentLink> link = links.findById(agentId);
            if (link.isEmpty()) {
                return Optional.empty();
            }
            BrowserProfile profile = profiles.findById(link.get().getProfileId()).orElse(null);
            if (profile == null) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<String> domains = profile.getAllowedDomainList();
            String state = null;
            if (profile.hasState() && !profile.isExpired(now)) {
                try {
                    state =
                            BrowserStorageState.filterToDomains(
                                    encryption.decrypt(profile.getStateEncrypted()), domains);
                } catch (RuntimeException e) {
                    // Almost always APP_ENCRYPTION_KEY drift between environments. No state content
                    // in the log.
                    log.error(
                            "Browser profile {} could not be loaded ({}); the session starts without it - check APP_ENCRYPTION_KEY",
                            profile.getId(),
                            e.getClass().getSimpleName());
                }
            }
            profile.markUsed(now);
            profiles.save(profile);
            return Optional.of(
                    new LoadedProfile(profile.getId(), profile.getName(), domains, state));
        } catch (RuntimeException e) {
            log.warn(
                    "Browser profile lookup for agent {} failed: {}",
                    agentId,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Persists {@code storageStateJson} (straight from Playwright) as the profile's new state:
     * filtered to the allowed domains, refused when it has no entries (so a session that never
     * logged in cannot wipe a good login) or exceeds the size cap, then encrypted. Never throws.
     */
    @Transactional
    public SaveResult saveState(UUID profileId, String storageStateJson) {
        if (!enabled) {
            return SaveResult.DISABLED;
        }
        try {
            BrowserProfile profile = profiles.findById(profileId).orElse(null);
            if (profile == null) {
                return SaveResult.NOT_FOUND;
            }
            String filtered =
                    BrowserStorageState.filterToDomains(
                            storageStateJson, profile.getAllowedDomainList());
            int entries = BrowserStorageState.entryCount(filtered);
            if (entries == 0) {
                return SaveResult.EMPTY;
            }
            int bytes = filtered.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxStateBytes) {
                log.warn(
                        "Browser profile {} state is {} bytes, over the {} byte cap; not saved",
                        profileId,
                        bytes,
                        maxStateBytes);
                return SaveResult.TOO_LARGE;
            }
            profile.storeState(encryption.encrypt(filtered), bytes, entries, clock.instant());
            profiles.save(profile);
            return SaveResult.SAVED;
        } catch (RuntimeException e) {
            log.warn(
                    "Browser profile {} state could not be saved: {}",
                    profileId,
                    e.getClass().getSimpleName());
            return SaveResult.NOT_FOUND;
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private void refilterStoredState(BrowserProfile profile, List<String> domains, Instant now) {
        try {
            String filtered =
                    BrowserStorageState.filterToDomains(
                            encryption.decrypt(profile.getStateEncrypted()), domains);
            int entries = BrowserStorageState.entryCount(filtered);
            if (entries == 0) {
                profile.clearState(now);
                return;
            }
            profile.storeState(
                    encryption.encrypt(filtered),
                    filtered.getBytes(StandardCharsets.UTF_8).length,
                    entries,
                    now);
        } catch (RuntimeException e) {
            // Cannot prove the old state fits the new scope: drop it rather than keep out-of-scope
            // cookies.
            profile.clearState(now);
        }
    }

    private BrowserProfile requireManageable(UUID id) {
        requireEnabled();
        String subject = subject();
        BrowserProfile profile =
                profiles.findById(id)
                        .filter(p -> canSee(p, subject))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Browser profile not found."));
        if (BrowserProfile.SCOPE_TENANT.equals(profile.getScope()) && !isManager()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Managing shared browser profiles needs the manage-agents permission.");
        }
        return profile;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Browser profiles are not enabled.");
        }
    }

    private static boolean canSee(BrowserProfile profile, String subject) {
        if (BrowserProfile.SCOPE_USER.equals(profile.getScope())) {
            return subject != null && subject.equals(profile.getOwnerSubject());
        }
        return true;
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static boolean isManager() {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole(PERM_MANAGE_AGENTS) || ctx.hasRole(ROLE_PLATFORM_ADMIN))
                .orElse(false);
    }

    private List<UUID> attachedAgents(UUID profileId) {
        return links.findByProfileId(profileId).stream()
                .map(BrowserProfileAgentLink::getAgentId)
                .toList();
    }

    private ProfileView view(BrowserProfile p, String subject, Instant now, List<UUID> attached) {
        boolean mine =
                BrowserProfile.SCOPE_USER.equals(p.getScope())
                        && subject != null
                        && subject.equals(p.getOwnerSubject());
        return new ProfileView(
                p.getId(),
                p.getName(),
                p.getScope(),
                mine,
                p.getAllowedDomainList(),
                p.hasState(),
                p.getStateBytes(),
                p.getStateEntries(),
                p.getTtlDays(),
                p.getExpiresAt(),
                p.isExpired(now),
                p.getLastSavedAt(),
                p.getLastUsedAt(),
                p.getCreatedAt(),
                List.copyOf(attached));
    }

    private int ttlOrDefault(Integer requested) {
        int ttl = requested == null ? defaultTtlDays : requested;
        if (ttl < 1 || ttl > maxTtlDays) {
            throw new IllegalArgumentException("ttlDays must be between 1 and " + maxTtlDays);
        }
        return ttl;
    }

    private static String parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return BrowserProfile.SCOPE_TENANT;
        }
        String scope = raw.strip().toUpperCase(Locale.ROOT);
        if (!BrowserProfile.SCOPE_TENANT.equals(scope)
                && !BrowserProfile.SCOPE_USER.equals(scope)) {
            throw new IllegalArgumentException("scope must be TENANT or USER");
        }
        return scope;
    }

    private static String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a profile name is required");
        }
        String name = raw.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "a profile name is at most " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException(
                        "a profile name must not contain control characters");
            }
        }
        return name;
    }
}
