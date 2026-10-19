package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserConflictException;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalUserAdminClientTest {

    InMemoryLocalAccountStore store = new InMemoryLocalAccountStore();
    LocalPasswords passwords = new LocalPasswords();
    LocalAuthService auth = mock(LocalAuthService.class);
    LocalSessionService sessions = mock(LocalSessionService.class);
    LocalUserAdminClient client;

    @BeforeEach
    void setUp() {
        client = new LocalUserAdminClient(store, passwords, auth, sessions, "https://app.example.test");
    }

    private KeycloakUserRepresentation signup(String email, String tenant) {
        return client.createUserForSignup(
                email, email, "Ada", "Lovelace", "a long enough password", Map.of("tenant_id", tenant, "organization_name", "Acme"));
    }

    @Test
    void signupCreatesAnAccountWithTheTenantAttributeInKeycloaksShape() {
        KeycloakUserRepresentation user = signup("a@example.test", "t_one");

        assertThat(user.attributes()).containsEntry("tenant_id", List.of("t_one")).containsEntry("organization_name", List.of("Acme"));
        assertThat(user.enabled()).isTrue();
        assertThat(client.findByEmail("A@Example.Test")).isPresent();
        assertThat(client.getUserById(user.id()).email()).isEqualTo("a@example.test");
        assertThat(store.findById(user.id()).orElseThrow().passwordHash()).startsWith("{").doesNotContain("long enough");
    }

    @Test
    void aDuplicateEmailIsAConflict() {
        signup("a@example.test", "t_one");

        assertThatThrownBy(() -> signup("A@example.test", "t_two")).isInstanceOf(KeycloakUserConflictException.class);
    }

    @Test
    void weakPasswordsAndBadAddressesAreValidationErrors() {
        assertThatThrownBy(() -> client.createUserForSignup("x", "a@example.test", "A", "B", "short", Map.of()))
                .isInstanceOf(KeycloakUserValidationException.class);
        assertThatThrownBy(() -> client.createUserForSignup("x", "not-an-email", "A", "B", "a long enough password", Map.of()))
                .isInstanceOf(KeycloakUserValidationException.class);
    }

    @Test
    void theFirstRunOwnerSignupIsCreatedSetupPendingAndOnlyThatOne() {
        KeycloakUserRepresentation owner =
                LocalFirstRun.asOwnerSignup(() -> signup("owner@example.test", "t_one"));
        KeycloakUserRepresentation other = signup("other@example.test", "t_one");

        assertThat(store.findById(owner.id()).orElseThrow().setupPending()).isTrue();
        assertThat(owner.requiredActions()).containsExactly("UPDATE_PASSWORD");
        assertThat(store.findById(other.id()).orElseThrow().setupPending()).isFalse();
    }

    @Test
    void aTemporaryPasswordMustBeChangedAndAnInviteHasNoPasswordYet() {
        KeycloakUserRepresentation temp = client.createUser("u", "temp@example.test", "A", "B", "temporary password 1", "t_one");
        KeycloakUserRepresentation invite = client.createUserForInvite("u", "invite@example.test", "A", "B", "t_one");

        assertThat(store.findById(temp.id()).orElseThrow().mustChangePassword()).isTrue();
        LocalAccount invited = store.findById(invite.id()).orElseThrow();
        assertThat(invited.passwordHash()).isNull();
        assertThat(invited.setupPending()).isTrue();
    }

    @Test
    void sendingTheInviteMintsASetupTokenForThatAccount() {
        KeycloakUserRepresentation invite = client.createUserForInvite("u", "invite@example.test", "A", "B", "t_one");

        client.sendActionsEmail(invite.id(), List.of("UPDATE_PASSWORD"));

        verify(auth).issueSetupToken(store.findById(invite.id()).orElseThrow());
    }

    @Test
    void anInviteLinkIsHandedBackForSomeoneWhoHasNotJoinedYet() {
        KeycloakUserRepresentation invite = client.createUserForInvite("u", "invite@example.test", "A", "B", "t_one");
        org.mockito.Mockito.when(auth.issueSetupToken(store.findById(invite.id()).orElseThrow())).thenReturn("tok123");

        assertThat(client.issueInviteLink(invite.id())).contains("https://app.example.test/login?setup=tok123");
    }

    @Test
    void anInviteLinkIsRefusedOnceThePersonHasAPassword() {
        KeycloakUserRepresentation member = signup("member@example.test", "t_one");

        assertThatThrownBy(() -> client.issueInviteLink(member.id())).isInstanceOf(KeycloakUserValidationException.class);
        org.mockito.Mockito.verifyNoInteractions(auth);
    }

    @Test
    void withoutAPublicUrlTheInviteLinkIsThePathAlone() {
        LocalUserAdminClient noUrl = new LocalUserAdminClient(store, passwords, auth, sessions, "");
        KeycloakUserRepresentation invite = noUrl.createUserForInvite("u", "invite@example.test", "A", "B", "t_one");
        org.mockito.Mockito.when(auth.issueSetupToken(org.mockito.ArgumentMatchers.any())).thenReturn("tok");

        assertThat(noUrl.issueInviteLink(invite.id())).contains("/login?setup=tok");
    }

    @Test
    void theTenantListsAreScopedToTheTenant() {
        signup("a@example.test", "t_one");
        signup("b@example.test", "t_one");
        signup("c@example.test", "t_two");

        assertThat(client.listUsersByTenant("t_one")).extracting(KeycloakUserRepresentation::email).containsExactlyInAnyOrder("a@example.test", "b@example.test");
        assertThat(client.countUsersByTenant("t_two")).isEqualTo(1);
        assertThat(client.listUsersGroupedByTenant()).containsOnlyKeys("t_one", "t_two");
        assertThat(client.listUsers()).hasSize(3);
    }

    @Test
    void rolesAreReplacedButPlatformAdminCanNeverBeAssignedThroughThePort() {
        KeycloakUserRepresentation user = signup("a@example.test", "t_one");

        client.replaceUserRealmRoles(user.id(), Set.of("tenant-admin", "agent-builder"));
        assertThat(client.getUserRealmRoleNames(user.id())).containsExactly("agent-builder", "tenant-admin");

        assertThatThrownBy(() -> client.replaceUserRealmRoles(user.id(), Set.of("platform-admin")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.getUserRealmRoleNames(user.id())).containsExactly("agent-builder", "tenant-admin");
    }

    @Test
    void attributesMergeAndTheTenantMoves() {
        KeycloakUserRepresentation user = signup("a@example.test", "t_one");

        client.setUserAttributes(user.id(), Map.of("tenant_id", "t_two", "organization_name", "Other"));

        KeycloakUserRepresentation after = client.getUserById(user.id());
        assertThat(after.attributes()).containsEntry("tenant_id", List.of("t_two")).containsEntry("organization_name", List.of("Other"));
    }

    @Test
    void disablingAndLoggingOutRevokeSessionsAndDeleteRemovesTheUser() {
        KeycloakUserRepresentation user = signup("a@example.test", "t_one");

        client.setUserEnabled(user.id(), false);
        client.logoutUser(user.id());
        verify(sessions, org.mockito.Mockito.times(2)).revokeAll(user.id());
        assertThat(client.getUserById(user.id()).enabled()).isFalse();

        client.deleteUser(user.id());
        assertThatThrownBy(() -> client.getUserById(user.id())).isInstanceOf(KeycloakAdminException.class);
        assertThatThrownBy(() -> client.getUserById("not-a-uuid")).isInstanceOf(KeycloakAdminException.class);
    }
}
