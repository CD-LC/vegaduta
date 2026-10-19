package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Regression coverage for {@link AccountController#getAvatar} - the
 * whole-payload-into-heap fix (JVM memory audit, 2026-07-29) that replaced
 * {@code fileStorageService.get(key).readAllBytes()} + {@code
 * ResponseEntity.body(byte[])} with a streamed {@link InputStreamResource}
 * so the controller no longer holds a second full-size copy of the avatar on
 * top of whatever {@link FileStorageService#get} itself buffered. Plain
 * unit tests calling the controller method directly (no MockMvc/web layer -
 * see {@code CompGrantControllerTest}'s javadoc for why that is this
 * codebase's established convention), so nothing here needs a network or a
 * live database.
 */
class AccountControllerTest {

    private AppUserRepository appUserRepository;
    private FileStorageService fileStorageService;
    private AccountController controller;
    private Principal principal;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        fileStorageService = mock(FileStorageService.class);
        controller = new AccountController(appUserRepository, fileStorageService);
        principal = () -> "keycloak-user-1";
    }

    @Test
    void getAvatarReturnsNotFoundWhenUserHasNeverUploadedOne() {
        AppUser user = new AppUser(UUID.randomUUID(), "keycloak-user-1", "user@example.com", UserRole.AGENT_BUILDER);
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1")).thenReturn(Optional.of(user));

        ResponseEntity<InputStreamResource> response = controller.getAvatar(principal);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getAvatarReturnsNotFoundWhenObjectStorageHasNoMatchingObject() {
        String objectKey = "avatars/" + UUID.randomUUID() + ".png";
        AppUser user = new AppUser(UUID.randomUUID(), "keycloak-user-1", "user@example.com", UserRole.AGENT_BUILDER);
        user.setAvatarUrl(objectKey);
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1")).thenReturn(Optional.of(user));
        when(fileStorageService.get(eq(objectKey)))
            .thenThrow(new FileStorageException("No object at key '" + objectKey + "'", null));

        ResponseEntity<InputStreamResource> response = controller.getAvatar(principal);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getAvatarStreamsStoredBytesWithPngContentType() throws IOException {
        byte[] storedBytes = {1, 2, 3, 4, 5};
        String objectKey = "avatars/" + UUID.randomUUID() + ".png";
        AppUser user = new AppUser(UUID.randomUUID(), "keycloak-user-1", "user@example.com", UserRole.AGENT_BUILDER);
        user.setAvatarUrl(objectKey);
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1")).thenReturn(Optional.of(user));
        when(fileStorageService.get(eq(objectKey))).thenReturn(new ByteArrayInputStream(storedBytes));

        ResponseEntity<InputStreamResource> response = controller.getAvatar(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        // The response body streams the SAME bytes object storage returned -
        // proof the conversion to InputStreamResource didn't drop or corrupt
        // any of the underlying stream's content.
        assertArrayEquals(storedBytes, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void getAvatarUsesJpegContentTypeForJpgKey() throws IOException {
        byte[] storedBytes = {9, 8, 7};
        String objectKey = "avatars/" + UUID.randomUUID() + ".jpg";
        AppUser user = new AppUser(UUID.randomUUID(), "keycloak-user-1", "user@example.com", UserRole.AGENT_BUILDER);
        user.setAvatarUrl(objectKey);
        when(appUserRepository.findByKeycloakUserId("keycloak-user-1")).thenReturn(Optional.of(user));
        when(fileStorageService.get(eq(objectKey))).thenReturn(new ByteArrayInputStream(storedBytes));

        ResponseEntity<InputStreamResource> response = controller.getAvatar(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertArrayEquals(storedBytes, response.getBody().getInputStream().readAllBytes());
    }
}
