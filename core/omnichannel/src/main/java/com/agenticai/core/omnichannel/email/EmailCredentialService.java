package com.agenticai.core.omnichannel.email;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.email.dto.EmailActivationRequest;
import com.agenticai.core.omnichannel.email.dto.EmailCredentialResponse;
import com.agenticai.core.security.LogMasking;
import com.agenticai.core.security.net.SsrfGuard;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for a tenant's own IMAP mailbox - same "prove it before persisting" pattern as
 * every other credential here, except the proof is a real IMAP {@code connect()} + inbox-folder
 * open/close instead of an API call, since IMAP has no separate "who am I" endpoint the way REST
 * APIs do.
 */
@Service
public class EmailCredentialService {

    private static final Logger log = LoggerFactory.getLogger(EmailCredentialService.class);

    private final EmailCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final Supplier<SsrfGuard> guard;

    @Autowired
    public EmailCredentialService(
            EmailCredentialRepository repository, EncryptionService encryptionService) {
        this(repository, encryptionService, SsrfGuard::defaultGuard);
    }

    /**
     * The guard is looked up per connection (the process-wide one is installed from configuration
     * at startup); tests pass their own.
     */
    EmailCredentialService(
            EmailCredentialRepository repository,
            EncryptionService encryptionService,
            Supplier<SsrfGuard> guard) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.guard = guard;
    }

    @Transactional
    public EmailCredentialResponse activate(EmailActivationRequest request) {
        boolean useSsl = request.useSsl() == null || request.useSsl();
        verifyConnection(
                request.imapHost(),
                request.imapPort(),
                request.imapUsername(),
                request.imapPassword(),
                useSsl);

        String encryptedPassword = encryptionService.encrypt(request.imapPassword());
        EmailCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(
                                            request.imapHost(),
                                            request.imapPort(),
                                            request.imapUsername(),
                                            encryptedPassword,
                                            useSsl);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new EmailCredential(
                                                request.imapHost(),
                                                request.imapPort(),
                                                request.imapUsername(),
                                                encryptedPassword,
                                                useSsl));

        return EmailCredentialResponse.from(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public Optional<EmailCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(EmailCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of this mailbox" - marks the credential {@link
     * EmailCredentialStatus#DISCONNECTED} (same contract as {@code
     * TwilioCredentialService#disconnect}). IMAP has no webhook and no external-account link to
     * drop: {@code EmailInboundPollingJob} only polls an ACTIVE credential, so this alone stops
     * inbound, and every outbound send resolves ACTIVE-only the same way.
     */
    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            credential.disconnect();
                            repository.save(credential);
                        });
    }

    /**
     * Live re-validation of the stored credential - the same {@link #verifyConnection} probe
     * activation uses, against the stored (decrypted) values. For the channels health-check
     * endpoint.
     *
     * @throws EmailNotConfiguredException when nothing is connected
     * @throws ChannelConfigurationException when the probe fails
     */
    @Transactional(readOnly = true)
    public void verifyActive() {
        EmailCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == EmailCredentialStatus.ACTIVE)
                        .orElseThrow(EmailNotConfiguredException::new);
        verifyConnection(
                credential.getImapHost(),
                credential.getImapPort(),
                credential.getImapUsername(),
                encryptionService.decrypt(credential.getEncryptedImapPassword()),
                credential.isUseSsl());
    }

    /**
     * Opens a real IMAP connection and immediately closes it - "prove it before persisting", same
     * reasoning as every credential here.
     */
    private void verifyConnection(
            String host, int port, String username, String password, boolean useSsl) {
        String protocol = useSsl ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "10000");
        // Refused hosts (loopback, internal ranges, metadata) fail here with a policy message,
        // before any socket opens.
        ImapEndpoint.apply(guard.get(), props, protocol, host, port);

        Session session = Session.getInstance(props);
        try (Store store = session.getStore(protocol)) {
            store.connect(host, port, username, password);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            inbox.close(false);
        } catch (Exception e) {
            // The mail client's message can reveal what answered on the other end (timeouts,
            // banners, refused ports) and usually names the tenant-typed host, so neither the
            // caller nor the log gets it: the log carries a fixed text and the exception class.
            log.warn(
                    "IMAP connect probe failed for {}: {}",
                    LogMasking.email(username),
                    e.getClass().getSimpleName());
            throw new ChannelConfigurationException(
                    "email",
                    "Could not connect to this IMAP mailbox. Check the host, port, SSL setting,"
                            + " username and password.");
        }
    }
}
