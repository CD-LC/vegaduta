package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.CreateProfileRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.ProfileView;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

/**
 * Browser profiles: the encrypted-at-rest round trip, domain scoping on save and load, the size
 * cap, TTL, who may see and manage what, and flag-off identity (no repository is touched when the
 * feature is off). Repositories are in-memory fakes; encryption is the REAL {@link
 * EncryptionService} (AES-256-GCM), so "stored ciphertext does not contain the cookie value" is a
 * genuine check.
 */
class BrowserProfileServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY =
            Base64.getEncoder()
                    .encodeToString(
                            new byte[] {
                                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
                                20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
                            });

    private static final String STATE =
            """
        {"cookies":[
          {"name":"sid","value":"SECRET-SESSION-VALUE","domain":"app.example.com","path":"/","expires":-1,"httpOnly":true,"secure":true,"sameSite":"Lax"},
          {"name":"evil","value":"LEAKED-COOKIE","domain":"evil.test","path":"/","expires":-1,"httpOnly":false,"secure":false,"sameSite":"None"}
        ],
        "origins":[{"origin":"https://app.example.com","localStorage":[{"name":"jwt","value":"SECRET-JWT"}]}]}
        """;

    /** A clock the test can move. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Map<UUID, BrowserProfile> profileStore = new HashMap<>();
    private final Map<UUID, BrowserProfileAgentLink> linkStore = new HashMap<>();
    private final MutableClock clock = new MutableClock();

    private BrowserProfileRepository profiles;
    private BrowserProfileAgentLinkRepository links;
    private EncryptionService encryption;
    private AgentService agentService;
    private BrowserProfileService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        profiles = mock(BrowserProfileRepository.class);
        links = mock(BrowserProfileAgentLinkRepository.class);
        agentService = mock(AgentService.class);
        encryption = realEncryption(KEY);
        wireInMemoryRepositories();
        service = newService(true, 262_144);
        asManager("alice");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static EncryptionService realEncryption(String key)
            throws ReflectiveOperationException {
        EncryptionService encryption = new EncryptionService(key, "");
        Method init = EncryptionService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(encryption);
        return encryption;
    }

    @SuppressWarnings("unchecked")
    private BrowserProfileService newService(boolean enabled, int maxStateBytes) {
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        when(agents.getIfAvailable()).thenReturn(agentService);
        return new BrowserProfileService(
                profiles,
                links,
                encryption,
                agents,
                enabled,
                maxStateBytes,
                20,
                30,
                365,
                true,
                clock);
    }

    private void wireInMemoryRepositories() {
        when(profiles.save(any(BrowserProfile.class)))
                .thenAnswer(
                        inv -> {
                            BrowserProfile p = inv.getArgument(0);
                            profileStore.put(p.getId(), p);
                            return p;
                        });
        when(profiles.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(profileStore.get(inv.<UUID>getArgument(0))));
        when(profiles.findAllByOrderByNameAsc())
                .thenAnswer(
                        inv ->
                                profileStore.values().stream()
                                        .sorted(Comparator.comparing(BrowserProfile::getName))
                                        .toList());
        when(profiles.count()).thenAnswer(inv -> (long) profileStore.size());
        when(profiles.existsByOwnerSubjectIsNullAndNameIgnoreCase(anyString()))
                .thenAnswer(
                        inv ->
                                profileStore.values().stream()
                                        .anyMatch(
                                                p ->
                                                        p.getOwnerSubject() == null
                                                                && p.getName()
                                                                        .equalsIgnoreCase(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        0))));
        when(profiles.existsByOwnerSubjectAndNameIgnoreCase(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                profileStore.values().stream()
                                        .anyMatch(
                                                p ->
                                                        inv.<String>getArgument(0)
                                                                        .equals(p.getOwnerSubject())
                                                                && p.getName()
                                                                        .equalsIgnoreCase(
                                                                                inv
                                                                                        .<String>
                                                                                                getArgument(
                                                                                                        1))));
        when(links.save(any(BrowserProfileAgentLink.class)))
                .thenAnswer(
                        inv -> {
                            BrowserProfileAgentLink l = inv.getArgument(0);
                            linkStore.put(l.getAgentId(), l);
                            return l;
                        });
        when(links.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(linkStore.get(inv.<UUID>getArgument(0))));
        when(links.findAll()).thenAnswer(inv -> new ArrayList<>(linkStore.values()));
        when(links.findByProfileId(any(UUID.class)))
                .thenAnswer(
                        inv ->
                                linkStore.values().stream()
                                        .filter(
                                                l ->
                                                        l.getProfileId()
                                                                .equals(inv.<UUID>getArgument(0)))
                                        .toList());
    }

    private static void asManager(String subject) {
        TenantContext.set(TenantContext.of("tenant-1", subject, Set.of("PERM_MANAGE_AGENTS")));
    }

    private static void asMember(String subject) {
        TenantContext.set(TenantContext.of("tenant-1", subject, Set.of()));
    }

    private ProfileView createShared(String name, String... domains) {
        return service.create(new CreateProfileRequest(name, List.of(domains), null, null));
    }

    private UUID attachAgent(UUID profileId, String agentCreator) {
        UUID agentId = UUID.randomUUID();
        Agent agent = mock(Agent.class);
        when(agent.getCreatedBy()).thenReturn(agentCreator);
        when(agentService.requireAccess(eq(agentId), any(PermissionLevel.class))).thenReturn(agent);
        service.attach(profileId, agentId);
        return agentId;
    }

    // ---- encryption round trip
    // -------------------------------------------------------------------

    @Test
    void savedStateIsEncryptedAtRestAndRoundTripsThroughLoad() throws ReflectiveOperationException {
        ProfileView created = createShared("Work login", "app.example.com");
        UUID agentId = attachAgent(created.id(), "alice");

        BrowserProfileService.SaveResult result = service.saveState(created.id(), STATE);

        assertThat(result).isEqualTo(BrowserProfileService.SaveResult.SAVED);
        String stored = profileStore.get(created.id()).getStateEncrypted();
        assertThat(stored)
                .isNotBlank()
                .doesNotContain("SECRET-SESSION-VALUE")
                .doesNotContain("SECRET-JWT")
                .doesNotContain("cookies");
        // and it is genuinely decryptable with the same key, but not with another
        assertThat(encryption.decrypt(stored)).contains("SECRET-SESSION-VALUE");
        assertThatThrownBy(() -> realEncryption(OTHER_KEY).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);

        Optional<BrowserProfileService.LoadedProfile> loaded = service.loadForSession(agentId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().storageStateJson())
                .contains("SECRET-SESSION-VALUE")
                .contains("SECRET-JWT");
        assertThat(loaded.get().allowedDomains()).containsExactly("app.example.com");
    }

    @Test
    void stateForADomainOutsideTheScopeIsNeverStoredOrLoaded() throws ReflectiveOperationException {
        ProfileView created = createShared("Work login", "app.example.com");
        UUID agentId = attachAgent(created.id(), "alice");

        service.saveState(created.id(), STATE);

        String plaintext = encryption.decrypt(profileStore.get(created.id()).getStateEncrypted());
        assertThat(plaintext).doesNotContain("LEAKED-COOKIE").doesNotContain("evil.test");
        // a row tampered with out of band (out-of-scope cookie encrypted straight into the column)
        // is still filtered on load
        profileStore
                .get(created.id())
                .storeState(encryption.encrypt(STATE), 10, 3, clock.instant());
        assertThat(service.loadForSession(agentId).orElseThrow().storageStateJson())
                .doesNotContain("LEAKED-COOKIE");
    }

    @Test
    void narrowingTheScopeDropsStateThatNoLongerFits() throws ReflectiveOperationException {
        ProfileView created = createShared("Work login", "app.example.com", "evil.test");
        service.saveState(created.id(), STATE);
        assertThat(encryption.decrypt(profileStore.get(created.id()).getStateEncrypted()))
                .contains("LEAKED-COOKIE");

        service.update(
                created.id(),
                new BrowserAutomationDtos.UpdateProfileRequest(
                        null, List.of("app.example.com"), null));

        assertThat(encryption.decrypt(profileStore.get(created.id()).getStateEncrypted()))
                .doesNotContain("LEAKED-COOKIE")
                .contains("SECRET-SESSION-VALUE");
    }

    @Test
    void aKeyMismatchLoadsAProfileWithNoStateInsteadOfFailing()
            throws ReflectiveOperationException {
        ProfileView created = createShared("Work login", "app.example.com");
        UUID agentId = attachAgent(created.id(), "alice");
        profileStore
                .get(created.id())
                .storeState(realEncryption(OTHER_KEY).encrypt(STATE), 10, 3, clock.instant());

        Optional<BrowserProfileService.LoadedProfile> loaded = service.loadForSession(agentId);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().storageStateJson()).isNull();
    }

    // ---- caps and TTL
    // ----------------------------------------------------------------------------

    @Test
    void stateOverTheSizeCapIsRefusedAndTheOldStateKept() {
        service = newService(true, 1_024);
        ProfileView created = createShared("Work login", "app.example.com");
        assertThat(service.saveState(created.id(), STATE))
                .isEqualTo(BrowserProfileService.SaveResult.SAVED);
        String before = profileStore.get(created.id()).getStateEncrypted();

        String big =
                "{\"cookies\":[{\"name\":\"big\",\"value\":\""
                        + "x".repeat(2_000)
                        + "\",\"domain\":\"app.example.com\",\"path\":\"/\",\"expires\":-1}],\"origins\":[]}";

        assertThat(service.saveState(created.id(), big))
                .isEqualTo(BrowserProfileService.SaveResult.TOO_LARGE);
        assertThat(profileStore.get(created.id()).getStateEncrypted()).isEqualTo(before);
    }

    @Test
    void aSessionThatNeverLoggedInCannotWipeAGoodLogin() {
        ProfileView created = createShared("Work login", "app.example.com");
        service.saveState(created.id(), STATE);
        String before = profileStore.get(created.id()).getStateEncrypted();

        String onlyOutOfScope =
                "{\"cookies\":[{\"name\":\"a\",\"value\":\"b\",\"domain\":\"evil.test\",\"path\":\"/\",\"expires\":-1}],\"origins\":[]}";

        assertThat(service.saveState(created.id(), onlyOutOfScope))
                .isEqualTo(BrowserProfileService.SaveResult.EMPTY);
        assertThat(profileStore.get(created.id()).getStateEncrypted()).isEqualTo(before);
    }

    @Test
    void theTtlSlidesOnSaveAndAnExpiredProfileLoadsNoState() {
        ProfileView created =
                service.create(
                        new CreateProfileRequest("Short", List.of("app.example.com"), null, 1));
        UUID agentId = attachAgent(created.id(), "alice");
        service.saveState(created.id(), STATE);
        assertThat(profileStore.get(created.id()).getExpiresAt())
                .isEqualTo(clock.instant().plus(Duration.ofDays(1)));

        assertThat(service.loadForSession(agentId).orElseThrow().storageStateJson())
                .contains("SECRET-SESSION-VALUE");

        clock.advance(Duration.ofDays(2));
        Optional<BrowserProfileService.LoadedProfile> expired = service.loadForSession(agentId);
        assertThat(expired).isPresent();
        assertThat(expired.get().storageStateJson()).isNull();
        assertThat(service.list().get(0).expired()).isTrue();
    }

    @Test
    void ttlAndNameAreValidated() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateProfileRequest(
                                                "x", List.of("app.example.com"), null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateProfileRequest(
                                                "x", List.of("app.example.com"), null, 9_999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateProfileRequest(
                                                "  ", List.of("app.example.com"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> service.create(new CreateProfileRequest("ok", List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new CreateProfileRequest(
                                                "ok", List.of("localhost"), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateNamesConflict() {
        createShared("Work login", "app.example.com");

        assertThatThrownBy(() -> createShared("work LOGIN", "app.example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    // ---- visibility and permissions
    // --------------------------------------------------------------

    @Test
    void aSharedProfileNeedsTheManageAgentsPermissionToCreateOrDelete() {
        ProfileView created = createShared("Work login", "app.example.com");
        asMember("bob");

        assertThatThrownBy(() -> createShared("Another", "app.example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> service.delete(created.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        // but everyone in the tenant can SEE it
        assertThat(service.list()).extracting(ProfileView::name).containsExactly("Work login");
    }

    @Test
    void aPersonalProfileIsInvisibleToEveryoneButItsOwner() {
        asMember("bob");
        ProfileView mine =
                service.create(
                        new CreateProfileRequest(
                                "Bob only", List.of("app.example.com"), "USER", null));
        assertThat(mine.ownedByMe()).isTrue();
        assertThat(service.list()).hasSize(1);

        asManager("alice");
        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.delete(mine.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void aPersonalProfileCanOnlyBeAttachedToAnAgentItsOwnerCreated() {
        asMember("bob");
        ProfileView mine =
                service.create(
                        new CreateProfileRequest(
                                "Bob only", List.of("app.example.com"), "USER", null));
        UUID sharedAgent = UUID.randomUUID();
        Agent someoneElses = mock(Agent.class);
        when(someoneElses.getCreatedBy()).thenReturn("alice");
        when(agentService.requireAccess(eq(sharedAgent), any(PermissionLevel.class)))
                .thenReturn(someoneElses);

        assertThatThrownBy(() -> service.attach(mine.id(), sharedAgent))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attachAgent(mine.id(), "bob")).isNotNull();
        assertThat(service.list().get(0).attachedAgentIds()).hasSize(1);
    }

    @Test
    void aMemberOnlyLearnsTheIdsOfAttachedAgentsTheyCanSee() {
        ProfileView shared = createShared("Team login", "app.example.com");
        UUID visible = attachAgent(shared.id(), "alice");
        UUID hidden = attachAgent(shared.id(), "alice");
        AgentResponse visibleAgent = mock(AgentResponse.class);
        when(visibleAgent.id()).thenReturn(visible);
        when(agentService.listAll()).thenReturn(List.of(visibleAgent));

        assertThat(service.list().get(0).attachedAgentIds())
                .containsExactlyInAnyOrder(visible, hidden);

        asMember("bob");
        assertThat(service.list().get(0).attachedAgentIds()).containsExactly(visible);
    }

    @Test
    void attachRequiresEditorAccessToTheAgent() {
        ProfileView created = createShared("Work login", "app.example.com");
        UUID agentId = UUID.randomUUID();
        when(agentService.requireAccess(eq(agentId), any(PermissionLevel.class)))
                .thenThrow(new com.agenticai.core.sharing.ResourceAccessDeniedException());

        assertThatThrownBy(() -> service.attach(created.id(), agentId))
                .isInstanceOf(com.agenticai.core.sharing.ResourceAccessDeniedException.class);
        assertThat(linkStore).isEmpty();
    }

    @Test
    void deletingAProfileDetachesItsAgents() {
        ProfileView created = createShared("Work login", "app.example.com");
        attachAgent(created.id(), "alice");
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            Iterable<BrowserProfileAgentLink> toDelete = inv.getArgument(0);
                            toDelete.forEach(l -> linkStore.remove(l.getAgentId()));
                            return null;
                        })
                .when(links)
                .deleteAll(any());
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            profileStore.remove(inv.<BrowserProfile>getArgument(0).getId());
                            return null;
                        })
                .when(profiles)
                .delete(any(BrowserProfile.class));

        service.delete(created.id());

        assertThat(profileStore).isEmpty();
        assertThat(linkStore).isEmpty();
    }

    @Test
    void theApiViewHasNoFieldThatCouldCarryTheSavedState() {
        List<String> components = new ArrayList<>();
        for (RecordComponent component : ProfileView.class.getRecordComponents()) {
            components.add(component.getName().toLowerCase());
        }

        assertThat(components)
                .doesNotContain(
                        "stateencrypted", "storagestate", "state", "cookies", "localstorage");
        ProfileView view = createShared("Work login", "app.example.com");
        service.saveState(view.id(), STATE);
        assertThat(service.list().get(0).toString())
                .doesNotContain("SECRET-SESSION-VALUE")
                .doesNotContain("SECRET-JWT");
    }

    // ---- flag-off identity
    // -----------------------------------------------------------------------

    @Test
    void withTheFlagOffNothingIsReadOrWritten() {
        BrowserProfileRepository untouchedProfiles = mock(BrowserProfileRepository.class);
        BrowserProfileAgentLinkRepository untouchedLinks =
                mock(BrowserProfileAgentLinkRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentService> agents = mock(ObjectProvider.class);
        BrowserProfileService off =
                new BrowserProfileService(
                        untouchedProfiles,
                        untouchedLinks,
                        encryption,
                        agents,
                        false,
                        262_144,
                        20,
                        30,
                        365,
                        true,
                        clock);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.loadForSession(UUID.randomUUID())).isEmpty();
        assertThat(off.saveState(UUID.randomUUID(), STATE))
                .isEqualTo(BrowserProfileService.SaveResult.DISABLED);
        assertThatThrownBy(off::list)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(
                        () ->
                                off.create(
                                        new CreateProfileRequest(
                                                "x", List.of("app.example.com"), null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> off.delete(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(untouchedProfiles, untouchedLinks);
    }
}
