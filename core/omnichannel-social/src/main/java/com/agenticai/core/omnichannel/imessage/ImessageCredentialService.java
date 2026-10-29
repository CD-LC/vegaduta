package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BYOK activation of a tenant's BlueBubbles server. Activation proves the address and password
 * with a real call (the server must answer {@code /api/v1/server/info}) before anything is
 * stored, then mints a random public webhook id (linked to the tenant, so the unauthenticated
 * webhook can find its schema) and a random webhook secret (encrypted at rest, shown to the tenant
 * admin so it can be pasted into BlueBubbles).
 *
 * <p>What the operator does to get these values: install BlueBubbles on a Mac that stays awake and
 * signed in to Messages, set its Server Password, and give it a URL this platform can reach (LAN
 * address, tailnet name or its own tunnel). A private address needs an entry in {@code
 * app.security.outbound.allowed-private-hosts} (see {@link ImessageApiClient}).
 */
@Service
public class ImessageCredentialService {

    static final String WEBHOOK_PATH_PREFIX = "/webhook/imessage/";

    private final ImessageCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final ImessageApiClient apiClient;
    private final ObjectProvider<ImessagePollingService> pollingService;
    private final SecureRandom random = new SecureRandom();

    public ImessageCredentialService(
            ImessageCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            ImessageApiClient apiClient,
            ObjectProvider<ImessagePollingService> pollingService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.apiClient = apiClient;
        this.pollingService = pollingService;
    }

    @Transactional
    public ImessageCredentialResponse activate(ImessageActivationRequest request) {
        String serverUrl = request.serverUrl().strip();
        apiClient.fetchServerInfo(serverUrl, request.password());

        String webhookId = randomHex(16);
        String webhookSecret = randomHex(24);
        String encryptedPassword = encryptionService.encrypt(request.password());
        String encryptedSecret = encryptionService.encrypt(webhookSecret);
        ImessageInboundMode mode =
                request.inboundMode() == null ? ImessageInboundMode.WEBHOOK : request.inboundMode();

        Optional<ImessageCredential> existing = repository.findFirstByOrderByCreatedAtAsc();
        String previousWebhookId = existing.map(ImessageCredential::getWebhookId).orElse(null);
        ImessageCredential credential =
                existing
                        .map(
                                c -> {
                                    c.reactivate(webhookId, serverUrl, encryptedPassword, encryptedSecret, mode);
                                    return c;
                                })
                        .orElseGet(
                                () ->
                                        new ImessageCredential(
                                                webhookId, serverUrl, encryptedPassword, encryptedSecret, mode));
        ImessageCredential saved = repository.save(credential);

        String schema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
        if (previousWebhookId != null && !previousWebhookId.equals(webhookId)) {
            tenantResolutionService.unlink(ExternalAccountProvider.IMESSAGE, previousWebhookId);
            pollingService.ifAvailable(p -> p.stopPolling(previousWebhookId));
        }
        tenantResolutionService.link(ExternalAccountProvider.IMESSAGE, webhookId, schema);
        startPollingAfterCommit(mode, webhookId, schema);
        return toResponse(saved, webhookSecret);
    }

    @Transactional(readOnly = true)
    public Optional<ImessageCredentialResponse> current() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .map(
                        c ->
                                toResponse(
                                        c,
                                        c.getStatus() == ImessageCredentialStatus.ACTIVE
                                                ? maskSecret(encryptionService.decrypt(c.getEncryptedWebhookSecret()))
                                                : null));
    }

    /**
     * The full webhook token, only on explicit request. Reads carry a masked preview (the token is
     * the sole authenticator of the unauthenticated inbound webhook, so it should not ride every
     * credential-read response); the console calls {@code GET .../webhook-token} when the operator
     * asks to reveal or copy the URL. The only other place the full value is returned is {@link
     * #activate}'s one-time response.
     */
    @Transactional(readOnly = true)
    public String revealWebhookToken() {
        ImessageCredential credential =
                repository.findFirstByOrderByCreatedAtAsc().orElseThrow(ImessageNotConfiguredException::new);
        return encryptionService.decrypt(credential.getEncryptedWebhookSecret());
    }

    /** The server address of an ACTIVE connection, for status lines. Decrypts nothing. */
    @Transactional(readOnly = true)
    public Optional<String> activeServerUrl() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(c -> c.getStatus() == ImessageCredentialStatus.ACTIVE)
                .map(ImessageCredential::getServerUrl);
    }

    /** Allow-list, group and unknown-sender policy plus the inbound mode. Needs an existing credential. */
    @Transactional
    public ImessageCredentialResponse updateSettings(ImessageSettingsRequest request) {
        ImessageCredential credential =
                repository.findFirstByOrderByCreatedAtAsc().orElseThrow(ImessageNotConfiguredException::new);
        Set<String> allowed = new LinkedHashSet<>();
        for (String raw : request.allowedSenders()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = ImessageAddress.normalize(raw);
            if (normalized == null) {
                throw new IllegalArgumentException(
                        "Not a phone number or email address: '" + raw.strip() + "'. "
                                + "Use +15550100100 or name@example.com.");
            }
            allowed.add(normalized);
        }
        if (allowed.size() > ImessageAddress.MAX_ALLOWED_SENDERS) {
            throw new IllegalArgumentException(
                    "At most " + ImessageAddress.MAX_ALLOWED_SENDERS + " allowed senders.");
        }
        credential.updateSettings(
                request.inboundMode(), allowed, request.allowGroups(), request.unknownSenderPolicy());
        ImessageCredential saved = repository.save(credential);
        if (credential.getStatus() == ImessageCredentialStatus.ACTIVE) {
            String webhookId = credential.getWebhookId();
            if (request.inboundMode() == ImessageInboundMode.POLLING) {
                startPollingAfterCommit(
                        ImessageInboundMode.POLLING,
                        webhookId,
                        tenantSchemaResolver.resolveCurrentTenantIdentifier());
            } else {
                pollingService.ifAvailable(p -> p.stopPolling(webhookId));
            }
        }
        return toResponse(saved, maskSecret(encryptionService.decrypt(saved.getEncryptedWebhookSecret())));
    }

    /**
     * Tenant-initiated disconnect: marks the credential DISCONNECTED, drops the webhook link so
     * inbound calls stop resolving this tenant, and stops polling. Idempotent. The BlueBubbles
     * server is not touched (it keeps its own webhook list; the operator removes ours there).
     */
    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            // "Log out" means the stored password and webhook secret stop existing.
                            credential.disconnect(
                                    encryptionService.encrypt(""),
                                    encryptionService.encrypt(randomHex(24)));
                            repository.save(credential);
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.IMESSAGE, credential.getWebhookId());
                            pollingService.ifAvailable(p -> p.stopPolling(credential.getWebhookId()));
                        });
    }

    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        ImessageCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == ImessageCredentialStatus.ACTIVE)
                        .orElseThrow(ImessageNotConfiguredException::new);
        return new DecryptedCredential(
                credential.getServerUrl(), encryptionService.decrypt(credential.getEncryptedPassword()));
    }

    /**
     * Constant-time check of a webhook token against the tenant's stored secret. False when no
     * ACTIVE credential exists, when the credential belongs to another webhook id, or when the
     * token is missing. The caller has set the tenant context from the (public) webhook id.
     */
    @Transactional(readOnly = true)
    public boolean webhookTokenMatches(String webhookId, String presentedToken) {
        if (presentedToken == null || presentedToken.isEmpty()) {
            return false;
        }
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(c -> c.getStatus() == ImessageCredentialStatus.ACTIVE)
                .filter(c -> c.getWebhookId().equals(webhookId))
                .map(
                        c ->
                                MessageDigest.isEqual(
                                        encryptionService
                                                .decrypt(c.getEncryptedWebhookSecret())
                                                .getBytes(StandardCharsets.UTF_8),
                                        presentedToken.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    /** Who may talk to the agent, read fresh for every inbound message. Empty when not ACTIVE. */
    @Transactional(readOnly = true)
    public Optional<InboundSettings> inboundSettings() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(c -> c.getStatus() == ImessageCredentialStatus.ACTIVE)
                .map(
                        c ->
                                new InboundSettings(
                                        c.allowedSenders(), c.isAllowGroups(), c.getUnknownSenderPolicy()));
    }

    /** What a poller needs for one round; empty once the credential is gone, disconnected or not polling. */
    @Transactional(readOnly = true)
    public Optional<PollTarget> pollTarget(String webhookId) {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(c -> c.getStatus() == ImessageCredentialStatus.ACTIVE)
                .filter(c -> c.getInboundMode() == ImessageInboundMode.POLLING)
                .filter(c -> c.getWebhookId().equals(webhookId))
                .map(
                        c ->
                                new PollTarget(
                                        c.getServerUrl(),
                                        encryptionService.decrypt(c.getEncryptedPassword()),
                                        c.getPollCursorMs()));
    }

    @Transactional
    public void savePollCursor(long cursorMs) {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        c -> {
                            c.advancePollCursor(cursorMs);
                            repository.save(c);
                        });
    }

    private void startPollingAfterCommit(ImessageInboundMode mode, String webhookId, String schema) {
        if (mode != ImessageInboundMode.POLLING) {
            return;
        }
        pollingService.ifAvailable(
                polling -> {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        // After commit: the poller reads the credential from its own thread and must see it.
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        polling.ensurePolling(webhookId, schema);
                                    }
                                });
                    } else {
                        polling.ensurePolling(webhookId, schema);
                    }
                });
    }

    private ImessageCredentialResponse toResponse(ImessageCredential c, String webhookSecret) {
        List<String> allowed = List.copyOf(c.allowedSenders());
        return new ImessageCredentialResponse(
                c.getId(),
                c.getServerUrl(),
                c.getStatus(),
                c.getInboundMode(),
                allowed,
                c.isAllowGroups(),
                c.getUnknownSenderPolicy(),
                WEBHOOK_PATH_PREFIX + c.getWebhookId(),
                webhookSecret,
                c.getCreatedAt(),
                c.getLastValidatedAt());
    }

    /** Masked preview of the webhook secret: last 4 hex chars only, enough to confirm which token is configured. */
    private static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }
        return "••••" + secret.substring(Math.max(0, secret.length() - 4));
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public record InboundSettings(
            Set<String> allowedSenders, boolean allowGroups, UnknownSenderPolicy policy) {}

    public record PollTarget(String serverUrl, String password, Long cursorMs) {
        @Override
        public String toString() {
            return "PollTarget[serverUrl=" + serverUrl + ", password=***, cursorMs=" + cursorMs + "]";
        }
    }

    /** The server address and password, decrypted just for one call. Never log or serialise this. */
    public record DecryptedCredential(String serverUrl, String password) {
        @Override
        public String toString() {
            return "DecryptedCredential[serverUrl=" + serverUrl + ", password=***]";
        }
    }
}
