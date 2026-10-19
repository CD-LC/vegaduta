package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BYOK activation for a tenant's own Matrix bot. The tenant brings a homeserver URL and the access
 * token of a bot user (created on the homeserver, for example with Element's "Settings > Help &
 * About > Access Token" or the homeserver's admin API); activation proves it with {@code whoami}
 * before anything is stored, then starts the sync loop when this process runs one.
 *
 * <p>Mirrors {@code TelegramCredentialService}, minus the webhook: Matrix has none, the platform
 * pulls with {@code /sync}.
 */
@Service
public class MatrixCredentialService {

    private static final Logger log = LoggerFactory.getLogger(MatrixCredentialService.class);
    private static final int MAX_EXTERNAL_ACCOUNT_ID = 128;

    private final MatrixCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final MatrixApiClient apiClient;
    private final ObjectProvider<MatrixSyncService> syncService;

    public MatrixCredentialService(
            MatrixCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            MatrixApiClient apiClient,
            ObjectProvider<MatrixSyncService> syncService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.apiClient = apiClient;
        this.syncService = syncService;
    }

    /** What a sync loop or a sender needs: where to call, who the bot is, and the policy to apply. */
    public record ActiveConnection(
            MatrixApiClient.Endpoint endpoint,
            String botUserId,
            String sinceToken,
            MatrixSenderPolicy policy) {}

    @Transactional
    public MatrixCredentialResponse activate(MatrixActivationRequest request) {
        String homeserver = normalizeHomeserver(request.homeserverUrl());
        String token = request.accessToken().strip();
        MatrixApiClient.WhoAmI who;
        try {
            who = apiClient.whoAmI(new MatrixApiClient.Endpoint(homeserver, token));
        } catch (MatrixApiException e) {
            throw new ChannelConfigurationException("matrix", "Matrix rejected this homeserver or token: " + e.getMessage());
        }
        if (who.userId().length() > MAX_EXTERNAL_ACCOUNT_ID) {
            throw new ChannelConfigurationException("matrix", "The bot user id is too long to register");
        }
        // whoami is answered by the homeserver the tenant typed, so the MXID is only as trustworthy as
        // that server. A bot id already linked to another tenant must never be repointed here: that
        // would silently take its sync loop (and its inbound messages) away from the other tenant.
        String currentSchema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
        tenantResolutionService
                .resolveTenantSchema(ExternalAccountProvider.MATRIX, who.userId())
                .filter(owner -> !owner.equals(currentSchema))
                .ifPresent(owner -> {
                    throw new ChannelConfigurationException(
                            "matrix", "This Matrix bot user is already connected to another workspace");
                });
        MatrixJoinPolicy joinPolicy = request.joinPolicy() == null ? MatrixJoinPolicy.OFF : request.joinPolicy();
        MatrixSenderMode senderMode = request.senderMode() == null ? MatrixSenderMode.PAIRING : request.senderMode();
        String encrypted = encryptionService.encrypt(token);

        // The previous bot's link must not keep resolving to this tenant once it is replaced.
        Optional<MatrixCredential> existing = repository.findFirstByOrderByCreatedAtAsc();
        existing.filter(c -> !c.getBotUserId().equals(who.userId())).ifPresent(c -> {
            tenantResolutionService.unlink(ExternalAccountProvider.MATRIX, c.getBotUserId());
            syncService.ifAvailable(sync -> sync.stopSync(c.getBotUserId()));
        });
        MatrixCredential credential = existing
            .map(c -> {
                c.reconnect(homeserver, who.userId(), who.deviceId(), encrypted, joinPolicy, senderMode, request.allowedSenders());
                return c;
            })
            .orElseGet(() -> new MatrixCredential(
                homeserver, who.userId(), who.deviceId(), encrypted, joinPolicy, senderMode, request.allowedSenders()));
        MatrixCredential saved = repository.save(credential);

        String schema = currentSchema;
        tenantResolutionService.link(ExternalAccountProvider.MATRIX, who.userId(), schema);

        // After commit: the sync thread reads the credential row from its own transaction.
        syncService.ifAvailable(sync -> TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync.restartSync(who.userId(), schema);
                }
            }));
        return MatrixCredentialResponse.from(saved, syncService.getIfAvailable() != null);
    }

    @Transactional
    public MatrixCredentialResponse updatePolicy(MatrixPolicyRequest request) {
        MatrixCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .orElseThrow(MatrixNotConfiguredException::new);
        credential.updatePolicy(request.joinPolicy(), request.senderMode(), request.allowedSenders());
        return MatrixCredentialResponse.from(repository.save(credential), syncService.getIfAvailable() != null);
    }

    @Transactional(readOnly = true)
    public Optional<MatrixCredentialResponse> current() {
        boolean inbound = syncService.getIfAvailable() != null;
        return repository.findFirstByOrderByCreatedAtAsc().map(c -> MatrixCredentialResponse.from(c, inbound));
    }

    /** Tenant-initiated disconnect: stops the sync loop and drops the bot-to-tenant link. */
    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.disconnect();
            repository.save(credential);
            tenantResolutionService.unlink(ExternalAccountProvider.MATRIX, credential.getBotUserId());
            syncService.ifAvailable(sync -> sync.stopSync(credential.getBotUserId()));
            log.info("Matrix bot disconnected");
        });
    }

    @Transactional(readOnly = true)
    public ActiveConnection resolveActive() {
        MatrixCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == MatrixCredentialStatus.ACTIVE)
            .orElseThrow(MatrixNotConfiguredException::new);
        return toConnection(credential);
    }

    /** The active connection for exactly this bot, or empty when it was replaced or disconnected. */
    @Transactional(readOnly = true)
    public Optional<ActiveConnection> activeFor(String botUserId) {
        return repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == MatrixCredentialStatus.ACTIVE && c.getBotUserId().equals(botUserId))
            .map(this::toConnection);
    }

    @Transactional
    public void saveSinceToken(String botUserId, String sinceToken) {
        repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getBotUserId().equals(botUserId))
            .ifPresent(c -> {
                c.updateSinceToken(sinceToken);
                repository.save(c);
            });
    }

    /** The homeserver refused the token: stop retrying until the tenant reconnects with a new one. */
    @Transactional
    public void markInvalid(String botUserId) {
        repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getBotUserId().equals(botUserId))
            .ifPresent(c -> {
                c.markInvalid();
                repository.save(c);
            });
    }

    private ActiveConnection toConnection(MatrixCredential c) {
        return new ActiveConnection(
            new MatrixApiClient.Endpoint(c.getHomeserverUrl(), encryptionService.decrypt(c.getEncryptedAccessToken())),
            c.getBotUserId(),
            c.getSinceToken(),
            MatrixSenderPolicy.of(c));
    }

    /**
     * Accepts {@code https://matrix.example.org} (a bare host gets https). Rejects credentials in the
     * URL, a query or fragment, and non-http(s) schemes; the SSRF guard checks the address itself on
     * every request.
     */
    static String normalizeHomeserver(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new ChannelConfigurationException("matrix", "Enter the homeserver URL, for example https://matrix.example.org");
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new ChannelConfigurationException("matrix", "The homeserver URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null) {
            throw new ChannelConfigurationException("matrix", "The homeserver URL must be an http(s) address");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ChannelConfigurationException("matrix", "The homeserver URL must not contain credentials, a query or a fragment");
        }
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
