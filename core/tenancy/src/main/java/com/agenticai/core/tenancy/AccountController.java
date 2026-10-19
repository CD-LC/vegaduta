package com.agenticai.core.tenancy;

import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.dto.AccountResponse;
import com.agenticai.core.tenancy.dto.UpdatePhoneNumberRequest;
import jakarta.validation.Valid;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.multipart.MultipartFile;

/**
 * The logged-in user's own self-service account details - phone number
 * (Simple mode's Quick Task feature delivers WhatsApp/SMS/call results to
 * it) and, as of the avatar endpoints below, their profile picture. Every
 * other user-management endpoint in this codebase ({@code
 * UserAdminController}) is about an admin managing OTHER users; this one is
 * a user managing themselves, so it resolves identity from the request's
 * own {@link Principal} rather than a path variable - there is no "whose
 * account" question to ask.
 *
 * <p>Not profile-gated ({@code @Profile("security")}) - same reasoning as
 * {@code SystemStatusController}: it works identically whether "security" is
 * active or not (falls through to {@code anyRequest().authenticated()} when
 * it is; {@code NoSecurityConfig}'s {@code permitAll()} when it isn't). With
 * security off there's no {@link Principal}, so {@link #currentUser}
 * resolves nothing and throws - equivalent to every other authenticated
 * endpoint's behavior in that mode.
 *
 * <p>Avatar storage follows the same "persist the object key, stream the
 * bytes back through our own endpoint" pattern as {@code
 * GeneratedImageController}/{@code SarvamGeneratedAudioController}: the
 * uploaded picture lands in {@link FileStorageService} (MinIO locally,
 * S3/R2 in production - see that interface's javadoc) under a key fixed per
 * user ({@code "avatars/<appUserId>"}), never under a fresh
 * random id each time, so re-uploading simply overwrites the previous
 * picture instead of accumulating orphaned objects. Only that key is
 * persisted on {@link AppUser#getAvatarUrl()}; the raw bytes never touch
 * Postgres.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    // AppShell displays this at a small circular size (32-40px) - 5MB is a
    // generous ceiling for a client-side-cropped 256x256 PNG/JPEG (which is
    // typically well under 500KB), just guarding against something wildly
    // oversized being posted directly against the API rather than through
    // the app's own crop-before-upload flow.
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    /**
     * Name the avatar stream's close-callback is registered under for the current
     * request - see {@link #closedAtRequestCompletion}. A fixed name is fine because a
     * single request can only ever open one avatar stream.
     */
    private static final String AVATAR_STREAM_CLEANUP_ATTRIBUTE =
        AccountController.class.getName() + ".avatarStream";

    private final AppUserRepository appUserRepository;
    private final FileStorageService fileStorageService;

    private final AppUserProvisioningService provisioningService;

    public AccountController(AppUserRepository appUserRepository, FileStorageService fileStorageService) {
        this(appUserRepository, fileStorageService, null);
    }

    @Autowired
    public AccountController(
        AppUserRepository appUserRepository, FileStorageService fileStorageService,
        AppUserProvisioningService provisioningService
    ) {
        this.provisioningService = provisioningService;
        this.appUserRepository = appUserRepository;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public AccountResponse get(Principal principal) {
        return toResponse(currentUser(principal));
    }

    @PutMapping("/phone")
    public AccountResponse setPhoneNumber(Principal principal, @Valid @RequestBody UpdatePhoneNumberRequest request) {
        AppUser user = currentUser(principal);
        user.setPhoneNumber(request.phoneNumber());
        appUserRepository.save(user);
        return toResponse(user);
    }

    /**
     * Stores {@code file} as this user's avatar and returns the refreshed
     * account (so the frontend's upload-modal confirm handler doesn't need a
     * separate follow-up {@link #get} call). Rejects non-image content types
     * and anything over {@link #MAX_AVATAR_BYTES} the same "client-actionable
     * 400, not a server bug" way {@code ChatAttachmentService} rejects an
     * oversized/wrong-type chat attachment - both fall through {@code
     * GlobalExceptionHandler}'s existing {@code IllegalArgumentException} ->
     * 400 mapping, no new exception type needed for this small a surface.
     */
    @PostMapping("/avatar")
    public AccountResponse uploadAvatar(Principal principal, @RequestParam("file") MultipartFile file) {
        AppUser user = currentUser(principal);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded avatar file is empty.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Avatar must be an image file (received " + contentType + ").");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException(
                "Avatar is too large - limit is " + (MAX_AVATAR_BYTES / 1024 / 1024) + "MB.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read uploaded avatar: " + e.getMessage());
        }

        // Fixed per-user key (no UUID/timestamp component, unlike
        // GeneratedImageService's per-upload random key) - re-uploading a new
        // picture overwrites the same object rather than leaving the old one
        // behind in storage forever. The extension rides along in the key
        // (same convention as GeneratedImageService#store) since that's the
        // only place the original content type is recorded - getAvatar below
        // reads it back off the key rather than needing its own DB column.
        String extension = contentType.contains("png") ? ".png" : ".jpg";
        String objectKey = "avatars/" + user.getId() + extension;
        String previousObjectKey = user.getAvatarUrl();
        fileStorageService.put(objectKey, content, contentType);
        // Only reachable if a PREVIOUS upload used the other extension (e.g.
        // switched from a .jpg avatar to a .png one) - the common case
        // (re-uploading the same format) just overwrites objectKey above and
        // this is a same-key no-op delete-then-nothing... guarded so it's a
        // true no-op, not a delete of the file we just wrote.
        if (previousObjectKey != null && !previousObjectKey.equals(objectKey)) {
            fileStorageService.delete(previousObjectKey);
        }
        user.setAvatarUrl(objectKey);
        appUserRepository.save(user);
        return toResponse(user);
    }

    /**
     * Streams the logged-in user's own avatar bytes back - what {@link
     * AccountResponse#avatarUrl()}'s fixed {@code /api/account/avatar} path
     * resolves to. Mirrors {@code GeneratedImageController#download}: an
     * ordinary authenticated GET, no bespoke access check needed since it
     * always resolves the CALLING user's own row, never a path-variable id.
     * 404 if this user has never uploaded a picture - AppShell's fallback to
     * initials is the expected behavior in that case, not an error state.
     *
     * <p>Returns an {@link InputStreamResource} rather than {@code byte[]} so
     * Spring's {@code ResourceHttpMessageConverter} copies the object storage
     * stream to the response in bounded chunks instead of this controller
     * first buffering the whole avatar into its own {@code byte[]} (on top of
     * whatever {@link FileStorageService#get} already buffered) just to hand
     * it to {@code ResponseEntity.body(byte[])} - one fewer full-size heap
     * copy per request, same as {@code file.getBytes()} at {@link
     * #uploadAvatar} needing no equivalent change: that read is already
     * bounded by the {@link #MAX_AVATAR_BYTES} check one line above it, run
     * BEFORE the read happens, whereas this GET had no such bound at all.
     *
     * <p><b>Why the stream is wrapped in {@link #closedAtRequestCompletion}.</b> Since
     * {@code S3FileStorageService#get} became genuinely lazy (JVM memory audit,
     * 2026-07-29) the value returned here is an SDK {@code ResponseInputStream} holding
     * an OPEN HTTP connection from the SDK's pool, not a detached
     * {@code ByteArrayInputStream} the GC can simply reclaim. Spring's {@code
     * ResourceHttpMessageConverter} does close the body in a {@code finally} once it
     * starts writing - including when the client disconnects mid-copy - but there are
     * dispatch paths where it never starts writing at all and the stream would leak:
     * most concretely a request whose {@code Accept} header cannot match the image
     * content type set below, which makes {@code writeWithMessageConverters} raise
     * {@code HttpMediaTypeNotAcceptableException} (406) with the body untouched. That
     * is client-triggerable, so repeating it would exhaust the SDK connection pool and
     * wedge every download in the app. Registering a request-completion callback closes
     * the stream on EVERY path (the callback runs from {@code
     * FrameworkServlet.processRequest}'s own finally block), and is a no-op on the
     * normal path because the wrapper only closes once.
     */
    @GetMapping("/avatar")
    public ResponseEntity<InputStreamResource> getAvatar(Principal principal) {
        AppUser user = currentUser(principal);
        String objectKey = user.getAvatarUrl();
        if (objectKey == null) {
            return ResponseEntity.notFound().build();
        }
        InputStream in;
        try {
            in = fileStorageService.get(objectKey);
        } catch (FileStorageException e) {
            // The DB row points at an object that's gone missing from storage
            // (e.g. a bucket wipe) - same "treat as no avatar" fallback as
            // objectKey == null above, rather than surfacing a confusing 5xx
            // for something the user can trivially fix by re-uploading.
            return ResponseEntity.notFound().build();
        }
        InputStream guarded = closedAtRequestCompletion(in);
        MediaType contentType = objectKey.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(contentType).body(new InputStreamResource(guarded));
    }

    /**
     * Wraps {@code in} so it closes at most once, and schedules that close for the end
     * of the current request - see {@link #getAvatar}'s javadoc for why. Falls back to
     * returning just the close-once wrapper when there is no bound request (a direct
     * unit-test call to the controller method), which is exactly the pre-existing
     * behavior for those callers.
     */
    private static InputStream closedAtRequestCompletion(InputStream in) {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream guarded = new FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                if (closed.compareAndSet(false, true)) {
                    super.close();
                }
            }
        };
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.registerDestructionCallback(AVATAR_STREAM_CLEANUP_ATTRIBUTE, () -> {
                try {
                    guarded.close();
                } catch (IOException ignored) {
                    // Nothing useful to do with a failure to close a response
                    // stream after the request is already over.
                }
            }, RequestAttributes.SCOPE_REQUEST);
        }
        return guarded;
    }

    private AccountResponse toResponse(AppUser user) {
        String avatarUrl = user.getAvatarUrl() != null ? "/api/account/avatar" : null;
        return new AccountResponse(user.getEmail(), user.getPhoneNumber(), avatarUrl);
    }

    private AppUser currentUser(Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new IllegalStateException("No authenticated principal for an /api/account request.");
        }
        Optional<AppUser> existing = appUserRepository.findByKeycloakUserId(principal.getName());
        if (existing.isPresent()) {
            return existing.get();
        }
        // B10: members created through POST /api/admin/users (or before that path created the row)
        // have no app_user row. Provision it from the verified JWT claims instead of failing every
        // page load with a 500. Idempotent and race-safe - see AppUserProvisioningService.
        if (provisioningService == null) {
            throw new IllegalStateException(
                "No AppUser row for authenticated principal '" + principal.getName() + "'.");
        }
        String email = principal instanceof JwtAuthenticationToken jwt
            ? jwt.getToken().getClaimAsString("email") : null;
        String schema = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        boolean tenantAdmin = TenantContext.current().map(c -> c.hasRole("ROLE_tenant-admin")).orElse(false);
        return provisioningService.ensure(schema, principal.getName(), email, tenantAdmin);
    }
}
