package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Connects a tenant to the signal-cli REST API its operator runs. Same "prove it before
 * persisting" posture as every channel credential here: activation calls the server, checks the
 * number is registered with it, and only then stores anything.
 *
 * <p>Unlike Telegram there is no webhook to register: Signal has no push to a public URL, so
 * {@link SignalReceiveService} pulls. Activation therefore only starts a receiver (after commit,
 * so its own thread sees the row).
 *
 * <p><b>What an operator does to get these values</b>: run {@code bbernhard/signal-cli-rest-api}
 * (see {@code deploy/compose/signal.yml}), register or link a number with it, and list its host
 * in {@code app.security.outbound.allowed-private-hosts} when it is on a private network.
 */
@Service
public class SignalCredentialService {

    private final SignalCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final SignalApiClient apiClient;
    private final ObjectProvider<SignalReceiveService> receiveService;

    public SignalCredentialService(
            SignalCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            SignalApiClient apiClient,
            ObjectProvider<SignalReceiveService> receiveService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.apiClient = apiClient;
        this.receiveService = receiveService;
    }

    @Transactional
    public SignalCredentialResponse activate(SignalActivationRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String number = SignalAddress.normalizeNumber(request.number());
        UnknownSenderPolicy unknownSenders =
                request.unknownSenders() == null ? UnknownSenderPolicy.PAIR : request.unknownSenders();
        SignalGroupMode groupMode =
                request.groupMode() == null ? SignalGroupMode.IGNORE : request.groupMode();
        String authToken =
                request.authToken() == null || request.authToken().isBlank() ? null : request.authToken().strip();
        requireHeaderSafe(authToken);

        verify(new SignalConnection(baseUrl, number, authToken, unknownSenders, groupMode));

        String schema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
        Optional<String> owner = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.SIGNAL, number);
        if (owner.isPresent() && !owner.get().equals(schema)) {
            // One number, one workspace: relinking would silently take its receiver from the other tenant.
            throw new ChannelConfigurationException(
                "signal", "This Signal number is already connected to another workspace.");
        }

        String encryptedToken = authToken == null ? null : encryptionService.encrypt(authToken);
        Optional<SignalCredential> existing = repository.findFirstByOrderByCreatedAtAsc();
        String previousNumber = existing.map(SignalCredential::getAccountNumber).orElse(null);
        SignalCredential credential = existing
            .map(row -> {
                row.reactivate(baseUrl, number, encryptedToken, unknownSenders, groupMode);
                return row;
            })
            .orElseGet(() -> new SignalCredential(baseUrl, number, encryptedToken, unknownSenders, groupMode));
        SignalCredentialResponse response = SignalCredentialResponse.from(repository.save(credential));

        if (previousNumber != null && !previousNumber.equals(number)) {
            tenantResolutionService.unlink(ExternalAccountProvider.SIGNAL, previousNumber);
            receiveService.ifAvailable(receive -> receive.stopReceiving(previousNumber));
        }
        tenantResolutionService.link(ExternalAccountProvider.SIGNAL, number, schema);
        // After commit: the receiver reads the credential from its own thread and must see it.
        receiveService.ifAvailable(receive ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    receive.ensureReceiving(number, schema);
                }
            }));
        return response;
    }

    @Transactional(readOnly = true)
    public Optional<SignalCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(SignalCredentialResponse::from);
    }

    /** Changes who the agent answers; the receiver picks it up on its next cycle. */
    @Transactional
    public SignalCredentialResponse updatePolicy(SignalPolicyRequest request) {
        SignalCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == SignalCredentialStatus.ACTIVE)
            .orElseThrow(SignalNotConfiguredException::new);
        credential.updatePolicy(request.unknownSenders(), request.groupMode());
        return SignalCredentialResponse.from(repository.save(credential));
    }

    /**
     * Tenant-initiated "log out of Signal": marks the credential DISCONNECTED, drops the number
     * link and stops the receiver. Nothing is called on signal-cli - the registration stays with
     * the operator, who may still be using the number for something else.
     */
    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.disconnect();
            repository.save(credential);
            tenantResolutionService.unlink(ExternalAccountProvider.SIGNAL, credential.getAccountNumber());
            receiveService.ifAvailable(receive -> receive.stopReceiving(credential.getAccountNumber()));
        });
    }

    @Transactional(readOnly = true)
    public SignalConnection resolveActive() {
        SignalCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == SignalCredentialStatus.ACTIVE)
            .orElseThrow(SignalNotConfiguredException::new);
        return connectionOf(credential);
    }

    SignalConnection connectionOf(SignalCredential credential) {
        String token = credential.getEncryptedAuthToken() == null
            ? null
            : encryptionService.decrypt(credential.getEncryptedAuthToken());
        return new SignalConnection(
            credential.getBaseUrl(),
            credential.getAccountNumber(),
            token,
            credential.getUnknownSenders(),
            credential.getGroupMode());
    }

    /** The server answers and the number is registered with it. */
    void verify(SignalConnection connection) {
        List<String> accounts;
        try {
            apiClient.about(connection);
            accounts = apiClient.accounts(connection);
        } catch (SignalApiException e) {
            throw new ChannelConfigurationException("signal", e.getMessage());
        }
        if (!accounts.contains(connection.accountNumber())) {
            throw new ChannelConfigurationException(
                "signal",
                "That signal-cli has no account for this number. Register or link it first"
                    + " (see the signal-cli-rest-api docs), then connect again.");
        }
    }

    static String normalizeBaseUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://")) || url.contains("?") || url.contains("#")) {
            throw new ChannelConfigurationException(
                "signal", "The base URL must look like http://signal-cli:8080 (http or https, no query).");
        }
        String userInfo;
        try {
            userInfo = java.net.URI.create(url).getRawUserInfo();
        } catch (IllegalArgumentException e) {
            throw new ChannelConfigurationException("signal", "The base URL is not a valid URL.");
        }
        if (userInfo != null) {
            // It would be stored and shown back in clear text; the token field is the place for a secret.
            throw new ChannelConfigurationException(
                "signal", "The base URL must not contain a user name or password; use the token field.");
        }
        return url;
    }

    /** The token goes into an Authorization header: a line break in it would let it inject headers. */
    private static void requireHeaderSafe(String token) {
        if (token == null) {
            return;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c == 0x7f || c > 0x7e) {
                throw new ChannelConfigurationException(
                    "signal", "The token may only contain printable ASCII characters.");
            }
        }
    }
}
