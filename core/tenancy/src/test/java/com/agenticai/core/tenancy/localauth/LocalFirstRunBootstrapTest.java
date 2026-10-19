package com.agenticai.core.tenancy.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.localauth.LocalAccount;
import com.agenticai.core.admin.localauth.LocalAccountStore;
import com.agenticai.core.admin.localauth.LocalAuthService;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantProvisioningService;
import com.agenticai.core.tenancy.dto.SignupRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class LocalFirstRunBootstrapTest {

    @TempDir Path dir;

    LocalAccountStore store = mock(LocalAccountStore.class);
    LocalAuthService auth = mock(LocalAuthService.class);
    TenantProvisioningService provisioning = mock(TenantProvisioningService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<TenantProvisioningService> provider = mock(ObjectProvider.class);
    PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
    LocalAuthSettings settings;

    @BeforeEach
    void setUp() {
        settings = new LocalAuthSettings(null, null, dir, null, null, null, null, null, null, null, null);
        when(provider.getIfAvailable()).thenReturn(provisioning);
        when(auth.issueSetupToken(any())).thenReturn("vdls_TESTTOKEN");
    }

    private LocalFirstRunBootstrap bootstrap(String extraRoles, String publicUrl) {
        return new LocalFirstRunBootstrap(
                store, auth, provider, lock, settings, "Owner@Vegaduta.Local", "Owner", "Account", "Personal", extraRoles, publicUrl);
    }

    private static LocalAccount owner(boolean pending, Set<String> roles) {
        return new LocalAccount(
                "11111111-1111-1111-1111-111111111111", "owner@vegaduta.local", "Owner", "Account", "{bcrypt}x",
                false, true, pending, "t_abc", roles, Map.of(), Instant.now(), null);
    }

    @Test
    void firstStartCreatesTheTenantThroughTheExistingSignupPathAndPrintsTheLink() throws IOException {
        when(store.countAccounts()).thenReturn(0L);
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(owner(true, Set.of("tenant-admin"))));

        bootstrap("", "https://app.example.test/").bootstrapOnce();

        ArgumentCaptor<SignupRequest> request = ArgumentCaptor.forClass(SignupRequest.class);
        verify(provisioning).signup(request.capture());
        assertThat(request.getValue().orgName()).isEqualTo("Personal");
        assertThat(request.getValue().adminEmail()).isEqualTo("owner@vegaduta.local");
        assertThat(request.getValue().adminPassword()).hasSizeGreaterThanOrEqualTo(12);
        verify(auth).issueSetupToken(any());

        Path file = dir.resolve(LocalFirstRunBootstrap.SETUP_FILE);
        assertThat(Files.readString(file)).contains("https://app.example.test/login?setup=vdls_TESTTOKEN");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }

    @Test
    void theRandomOwnerPasswordIsDifferentEveryTime() {
        when(store.countAccounts()).thenReturn(0L);
        when(store.findByEmail(any())).thenReturn(Optional.of(owner(true, Set.of())));

        bootstrap("", "").bootstrapOnce();
        bootstrap("", "").bootstrapOnce();

        ArgumentCaptor<SignupRequest> request = ArgumentCaptor.forClass(SignupRequest.class);
        verify(provisioning, times(2)).signup(request.capture());
        assertThat(request.getAllValues().get(0).adminPassword()).isNotEqualTo(request.getAllValues().get(1).adminPassword());
    }

    @Test
    void itIsIdempotentOnceAnAccountExists() {
        when(store.countAccounts()).thenReturn(1L);
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(owner(false, Set.of("tenant-admin"))));

        bootstrap("", "").bootstrapOnce();
        bootstrap("", "").bootstrapOnce();

        verify(provisioning, never()).signup(any());
        verify(auth, never()).issueSetupToken(any());
    }

    @Test
    void aRestartWhileSetupIsStillPendingIssuesAFreshLinkWithoutCreatingAnything() throws IOException {
        when(store.countAccounts()).thenReturn(1L);
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(owner(true, Set.of("tenant-admin"))));

        bootstrap("", "https://app.example.test").bootstrapOnce();

        verify(provisioning, never()).signup(any());
        verify(auth).issueSetupToken(any());
        assertThat(Files.readString(dir.resolve(LocalFirstRunBootstrap.SETUP_FILE))).contains("vdls_TESTTOKEN");
    }

    @Test
    void onceSetupIsDoneTheStaleLinkFileIsRemoved() throws IOException {
        Files.writeString(dir.resolve(LocalFirstRunBootstrap.SETUP_FILE), "old link");
        when(store.countAccounts()).thenReturn(1L);
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(owner(false, Set.of("tenant-admin"))));

        bootstrap("", "").bootstrapOnce();

        assertThat(dir.resolve(LocalFirstRunBootstrap.SETUP_FILE)).doesNotExist();
    }

    @Test
    void extraRolesAreTopedUpOnceAndOnlyOnce() {
        when(store.countAccounts()).thenReturn(1L);
        LocalAccount without = owner(false, Set.of("tenant-admin"));
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(without));

        bootstrap("platform-admin", "").bootstrapOnce();

        ArgumentCaptor<LocalAccount> updated = ArgumentCaptor.forClass(LocalAccount.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().roles()).containsExactlyInAnyOrder("tenant-admin", "platform-admin");

        LocalAccount with = owner(false, Set.of("tenant-admin", "platform-admin"));
        when(store.findByEmail("owner@vegaduta.local")).thenReturn(Optional.of(with));
        bootstrap("platform-admin", "").bootstrapOnce();
        verify(store, times(1)).update(any());
    }

    @Test
    void withoutTheSecurityProfileItExplainsAndDoesNotThrow() {
        when(store.countAccounts()).thenReturn(0L);
        when(provider.getIfAvailable()).thenReturn(null);

        bootstrap("", "").bootstrapOnce();

        verify(provisioning, never()).signup(any());
    }

    @Test
    void aFailureIsLoggedNotThrownSoTheApiStillBoots() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(lock).tryRunExclusive(eq(LocalFirstRunBootstrap.LOCK_NAME), any());

        bootstrap("", "").run(null);
    }

    @Test
    void theWorkRunsUnderTheClusterLock() {
        bootstrap("", "").run(null);

        verify(lock).tryRunExclusive(eq(LocalFirstRunBootstrap.LOCK_NAME), any());
    }
}
