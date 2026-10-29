package com.agenticai.core.tenancy.localauth;

import com.agenticai.core.admin.localauth.LocalAccount;
import com.agenticai.core.admin.localauth.LocalAccountStore;
import com.agenticai.core.admin.localauth.LocalAuthService;
import com.agenticai.core.admin.localauth.LocalFirstRun;
import com.agenticai.core.security.localauth.LocalAuthSettings;
import com.agenticai.core.security.publicurl.PublicWebUrl;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantProvisioningService;
import com.agenticai.core.tenancy.dto.SignupRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * First start of a local-mode install ({@code app.auth.mode=local}): creates the ONE owner account
 * and the default tenant, then prints how to set the owner's password.
 *
 * <p><b>Through the existing provisioning path.</b> The tenant, its schema, its migrations and its
 * first {@code AppUser} are created by {@link TenantProvisioningService#signup}, the same code the
 * hosted sign-up uses (with {@code LocalUserAdminClient} behind the identity port). Nothing about
 * tenant creation is duplicated here, so a local tenant is indistinguishable from any other.
 *
 * <p><b>No default password, ever.</b> The account is created with a random password nobody sees
 * and is flagged {@code setup_pending}, which refuses every sign-in. The owner sets a real password
 * by opening the one-time setup link this runner prints to the console and writes to {@code
 * <data-dir>/first-run-setup.txt} (mode 0600). The link expires ({@code
 * app.auth.local.setup-token-ttl}, default 24 h) and every restart while setup is still pending
 * issues a fresh one and retires the old, so a lost console never strands the install.
 *
 * <p><b>Idempotent.</b> If any account exists nothing is created. Several replicas starting
 * together are serialised by a Postgres advisory lock. A failure is logged loudly but does not stop
 * the boot: the API still serves, and the next start retries.
 */
@Component
@Profile("!insecure")
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class LocalFirstRunBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalFirstRunBootstrap.class);
    static final String LOCK_NAME = "local-auth-first-run";
    static final String SETUP_FILE = LocalAuthSettings.SETUP_FILE_NAME;

    private final LocalAccountStore store;
    private final LocalAuthService auth;
    private final ObjectProvider<TenantProvisioningService> provisioning;
    private final PgAdvisoryJobLock lock;
    private final LocalAuthSettings settings;
    private final String ownerEmail;
    private final String ownerFirstName;
    private final String ownerLastName;
    private final String tenantName;
    private final Set<String> extraRoles;
    private final String publicWebUrl;
    // app.auth.local.setup-link-in-logs: when false (log-aggregated deploys), the
    // one-use setup link is written only to the 0600 file, never to WARN output -
    // a link sitting in a log shipper is owner takeover until it is consumed.
    private boolean setupLinkInLogs = true;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSetupLinkInLogs(
            @Value("${app.auth.local.setup-link-in-logs:true}") boolean setupLinkInLogs) {
        this.setupLinkInLogs = setupLinkInLogs;
    }

    @Autowired
    public LocalFirstRunBootstrap(
            LocalAccountStore store,
            LocalAuthService auth,
            ObjectProvider<TenantProvisioningService> provisioning,
            PgAdvisoryJobLock lock,
            LocalAuthSettings settings,
            @Value("${app.auth.local.owner-email:owner@vegaduta.local}") String ownerEmail,
            @Value("${app.auth.local.owner-first-name:Owner}") String ownerFirstName,
            @Value("${app.auth.local.owner-last-name:Account}") String ownerLastName,
            @Value("${app.auth.local.tenant-name:Personal}") String tenantName,
            @Value("${app.auth.local.owner-extra-roles:}") String extraRoles,
            @Value("${app.web.public-url:}") String publicWebUrl) {
        this.store = store;
        this.auth = auth;
        this.provisioning = provisioning;
        this.lock = lock;
        this.settings = settings;
        this.ownerEmail = ownerEmail.trim().toLowerCase(Locale.ROOT);
        this.ownerFirstName = ownerFirstName;
        this.ownerLastName = ownerLastName;
        this.tenantName = tenantName;
        this.extraRoles = csv(extraRoles);
        this.publicWebUrl = publicWebUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            lock.tryRunExclusive(LOCK_NAME, this::bootstrapOnce);
        } catch (RuntimeException e) {
            log.error(
                    "Local auth first-run bootstrap FAILED - nobody can sign in until it succeeds."
                            + " Fix the cause and restart.",
                    e);
        }
    }

    /** One pass; safe to call repeatedly. */
    void bootstrapOnce() {
        if (store.countAccounts() == 0) {
            TenantProvisioningService service = provisioning.getIfAvailable();
            if (service == null) {
                log.error(
                        "app.auth.mode=local needs the 'security' Spring profile (it provides tenant"
                                + " provisioning). Add it to SPRING_PROFILES_ACTIVE.");
                return;
            }
            log.info(
                    "Local auth: first start, creating the default tenant '{}' and its owner",
                    tenantName);
            LocalFirstRun.asOwnerSignup(
                    () ->
                            service.signup(
                                    new SignupRequest(
                                            tenantName,
                                            ownerEmail,
                                            randomPassword(),
                                            ownerFirstName,
                                            ownerLastName,
                                            null,
                                            null,
                                            Boolean.FALSE,
                                            null)));
        }
        grantExtraRoles();
        Optional<LocalAccount> pending =
                store.findByEmail(ownerEmail).filter(LocalAccount::setupPending);
        if (pending.isPresent()) {
            announce(auth.issueSetupToken(pending.get()));
        } else {
            removeStaleSetupFile();
        }
    }

    /** Idempotent: tops the owner's roles up with {@code app.auth.local.owner-extra-roles}. */
    private void grantExtraRoles() {
        if (extraRoles.isEmpty()) {
            return;
        }
        store.findByEmail(ownerEmail)
                .filter(owner -> !owner.roles().containsAll(extraRoles))
                .ifPresent(
                        owner -> {
                            Set<String> roles = new LinkedHashSet<>(owner.roles());
                            roles.addAll(extraRoles);
                            store.update(owner.withRoles(roles));
                        });
    }

    // ------------------------------------------------------------------ announcing

    private void announce(String token) {
        String base = PublicWebUrl.normalize(publicWebUrl);
        String link = (base.isEmpty() ? "<your console URL>" : base) + "/login?setup=" + token;
        Path file = settings.dataDir().resolve(SETUP_FILE);
        String expiry = Instant.now().plus(settings.setupTokenTtl()).toString();
        log.warn("");
        log.warn(
                "=========================== VegaDuta: finish setting up ===========================");
        log.warn("  Open this link in your browser to choose the owner password (one use):");
        if (setupLinkInLogs) {
            log.warn("    {}", link);
        } else {
            log.warn(
                    "    <suppressed - app.auth.local.setup-link-in-logs=false; read {} instead>",
                    file);
        }
        if (base.isEmpty()) {
            log.warn("  (Set APP_WEB_PUBLIC_URL so this prints a full link.)");
        }
        log.warn("  Sign-in e-mail: {}    Link expires: {}", ownerEmail, expiry);
        log.warn("  The same link is saved, owner-readable only, in {}", file);
        log.warn(
                "====================================================================================");
        log.warn("");
        writeSetupFile(file, link, expiry);
    }

    private void writeSetupFile(Path file, String link, String expiry) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp =
                    file.getFileSystem().supportedFileAttributeViews().contains("posix")
                            ? Files.createTempFile(
                                    file.getParent(),
                                    "setup-",
                                    ".tmp",
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rw-------")))
                            : Files.createTempFile(file.getParent(), "setup-", ".tmp");
            Files.writeString(
                    tmp,
                    "Open this link to set the owner password (single use, expires "
                            + expiry
                            + "):\n"
                            + link
                            + "\n",
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not save the setup link to {}: {}", file, e.toString());
        }
    }

    private void removeStaleSetupFile() {
        try {
            Files.deleteIfExists(settings.dataDir().resolve(SETUP_FILE));
        } catch (IOException e) {
            log.debug("Could not remove stale setup file: {}", e.toString());
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[33];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
    }
}
