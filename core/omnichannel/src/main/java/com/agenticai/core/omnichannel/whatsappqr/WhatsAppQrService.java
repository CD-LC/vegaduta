package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * The tenant-facing lifecycle of a WhatsApp QR connection: "link my number" (start a bridge session
 * and hand the console its QR), "what is it now" (status + live QR while pairing), and "unlink"
 * (log out on the bridge and mark the row DISCONNECTED).
 *
 * <p>State split: this service owns the {@code whatsapp_qr_connection} row; the bridge owns the
 * session socket and its credentials. The row's PAIRING -&gt; ACTIVE transition is driven by what
 * the bridge reports - both here (a status read while the console is waiting) and in {@link
 * WhatsAppQrReceiveService} (so a scan works even with nobody watching the page).
 */
@Service
public class WhatsAppQrService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppQrService.class);

    private final WhatsAppQrConnectionRepository repository;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final WhatsAppQrClient client;
    private final ObjectProvider<WhatsAppQrReceiveService> receiveService;

    public WhatsAppQrService(
            WhatsAppQrConnectionRepository repository,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            WhatsAppQrClient client,
            ObjectProvider<WhatsAppQrReceiveService> receiveService) {
        this.repository = repository;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.client = client;
        this.receiveService = receiveService;
    }

    /**
     * The channel exists only when the operator configured a bridge - the console hides it else.
     */
    public boolean configured() {
        return client.configured();
    }

    /**
     * Starts (or restarts) pairing: a fresh session key, an idempotent ensure on the bridge, the
     * row at PAIRING, and the receive loop armed for when the scan lands. Calling it while a
     * session is live relinks: the old bridge session is logged out first, so only ever ONE bridge
     * session answers this tenant.
     */
    @Transactional
    public WhatsAppQrStatusResponse connect() {
        requireConfigured();
        String schema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
        String sessionKey = UUID.randomUUID().toString();

        WhatsAppQrConnection row =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .orElseGet(() -> new WhatsAppQrConnection(sessionKey));
        String previousKey = row.getId() == null ? null : row.getSessionKey();
        if (previousKey != null) {
            // Best effort: the old session may already be gone; the row update still proceeds.
            try {
                client.deleteSession(previousKey);
            } catch (RuntimeException e) {
                log.warn("Could not delete the previous WhatsApp bridge session: {}", brief(e));
            }
            tenantResolutionService.unlink(ExternalAccountProvider.WHATSAPP_QR, previousKey);
            receiveService.ifAvailable(r -> r.stopReceiving(previousKey));
        }
        row.markPairing(sessionKey);
        repository.save(row);
        tenantResolutionService.link(ExternalAccountProvider.WHATSAPP_QR, sessionKey, schema);

        JsonNode status = client.ensureSession(sessionKey);
        // After commit: the receiver resolves its own row on its own thread and must see it.
        String key = sessionKey;
        receiveService.ifAvailable(
                receive ->
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        receive.ensureReceiving(key, schema);
                                    }
                                }));
        return merge(row, status);
    }

    /**
     * Current state for the console: the row's durable status plus the bridge's live word and,
     * while pairing, the current QR payload. A bridge-reported CONNECTED flips the row to ACTIVE
     * (idempotent - the receiver does the same) and a LOGGED_OUT marks it DISCONNECTED.
     */
    @Transactional
    public Optional<WhatsAppQrStatusResponse> status() {
        Optional<WhatsAppQrConnection> found = repository.findFirstByOrderByCreatedAtAsc();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WhatsAppQrConnection row = found.get();
        if (row.getStatus() == WhatsAppQrStatus.DISCONNECTED) {
            return Optional.of(merge(row, null));
        }
        JsonNode bridge;
        try {
            bridge = client.sessionStatus(row.getSessionKey());
        } catch (WhatsAppQrApiException e) {
            // Whatever went wrong (404 after a wiped bridge, or unreachable), the page still
            // renders the row's durable status; the receiver reports liveness separately.
            return Optional.of(merge(row, null));
        }
        applyBridgeStatus(row, bridge);
        return Optional.of(merge(row, bridge));
    }

    /**
     * Tenant-initiated "unlink this number": logs the bridge session out (which also removes the
     * linked device inside WhatsApp), marks the row DISCONNECTED - kept, so the card can still say
     * what was connected - drops the tenant link and stops the receiver. Idempotent.
     */
    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        row -> {
                            if (row.getStatus() != WhatsAppQrStatus.DISCONNECTED) {
                                try {
                                    client.deleteSession(row.getSessionKey());
                                } catch (RuntimeException e) {
                                    // Unreachable bridge must not trap the row at ACTIVE.
                                    log.warn(
                                            "Could not log out the WhatsApp bridge session: {}",
                                            brief(e));
                                }
                            }
                            row.markDisconnected();
                            repository.save(row);
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.WHATSAPP_QR, row.getSessionKey());
                            receiveService.ifAvailable(r -> r.stopReceiving(row.getSessionKey()));
                        });
    }

    /** The ACTIVE connection the send/receive paths use; throws when there is none. */
    @Transactional(readOnly = true)
    public WhatsAppQrHandle resolveActive() {
        WhatsAppQrConnection row = activeRow().orElseThrow(WhatsAppQrNotConfiguredException::new);
        return new WhatsAppQrHandle(row.getSessionKey(), row.getPhoneNumber());
    }

    /** Like {@link #resolveActive} but non-throwing for "is this channel live" checks. */
    @Transactional(readOnly = true)
    public Optional<WhatsAppQrHandle> active() {
        return activeRow()
                .map(row -> new WhatsAppQrHandle(row.getSessionKey(), row.getPhoneNumber()));
    }

    /** Receiver-side transition, keyed by session key: the bridge says the scan completed. */
    @Transactional
    public void markConnected(String sessionKey, String phoneNumber) {
        repository
                .findBySessionKey(sessionKey)
                .filter(row -> row.getStatus() == WhatsAppQrStatus.PAIRING)
                .ifPresent(
                        row -> {
                            row.markActive(phoneNumber);
                            repository.save(row);
                            log.info("WhatsApp QR linked: {}", WhatsAppQrAddress.mask(phoneNumber));
                        });
    }

    /** Receiver-side transition: the phone unlinked us. The row keeps what it last knew. */
    @Transactional
    public void markLoggedOut(String sessionKey) {
        repository
                .findBySessionKey(sessionKey)
                .ifPresent(
                        row -> {
                            row.markDisconnected();
                            repository.save(row);
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.WHATSAPP_QR, sessionKey);
                        });
    }

    private Optional<WhatsAppQrConnection> activeRow() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(row -> row.getStatus() == WhatsAppQrStatus.ACTIVE);
    }

    private void applyBridgeStatus(WhatsAppQrConnection row, JsonNode bridge) {
        String bridgeStatus = bridge.path("status").asString("");
        if ("CONNECTED".equals(bridgeStatus) && row.getStatus() == WhatsAppQrStatus.PAIRING) {
            row.markActive(bridge.path("phoneNumber").asString(null));
            repository.save(row);
        } else if ("LOGGED_OUT".equals(bridgeStatus)) {
            row.markDisconnected();
            repository.save(row);
            tenantResolutionService.unlink(
                    ExternalAccountProvider.WHATSAPP_QR, row.getSessionKey());
        }
    }

    private WhatsAppQrStatusResponse merge(WhatsAppQrConnection row, JsonNode bridge) {
        String bridgeStatus = bridge == null ? null : bridge.path("status").asString(null);
        String qr = bridge == null ? null : bridge.path("qr").asString(null);
        return new WhatsAppQrStatusResponse(
                row.getStatus().name(),
                bridgeStatus,
                qr,
                row.getPhoneNumber(),
                client.configured());
    }

    private void requireConfigured() {
        if (!client.configured()) {
            throw new ChannelConfigurationException(
                    "whatsapp-qr",
                    "No WhatsApp bridge is configured for this deployment - the operator sets"
                            + " app.whatsappqr.bridge-url and app.whatsappqr.bridge-secret.");
        }
    }

    private static String brief(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
