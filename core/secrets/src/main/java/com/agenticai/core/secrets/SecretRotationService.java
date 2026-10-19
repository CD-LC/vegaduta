package com.agenticai.core.secrets;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.crypto.EncryptionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-encrypts every vault row under the current {@code APP_ENCRYPTION_KEY}.
 *
 * <p>This is the second half of a key rotation, and without it the first half
 * is a trap. {@link EncryptionService} already supports two keys at once -
 * {@code decrypt} falls back to {@code APP_ENCRYPTION_KEY_PREVIOUS} when the
 * current one fails - so swapping keys appears to work immediately and
 * everything keeps decrypting. What has actually happened is that every stored
 * secret still needs the OLD key, indefinitely. The moment someone removes
 * {@code APP_ENCRYPTION_KEY_PREVIOUS}, believing the rotation finished months
 * ago, every credential in the vault becomes undecryptable at once.
 *
 * <p>{@code EncryptionService#reencrypt} existed for this and had no caller
 * anywhere in the codebase; its own javadoc admits nothing drives it across
 * tables. This drives it across one table - the vault.
 *
 * <h2>The operator procedure</h2>
 * <ol>
 *   <li>Set {@code APP_ENCRYPTION_KEY} to the NEW key and
 *       {@code APP_ENCRYPTION_KEY_PREVIOUS} to the old one. Restart. Nothing
 *       breaks: old ciphertext decrypts via the fallback.</li>
 *   <li>Run {@link #rotateAll()} <b>once per tenant schema</b> - see the
 *       warning below.</li>
 *   <li>Only once step 2 has run everywhere, remove
 *       {@code APP_ENCRYPTION_KEY_PREVIOUS}.</li>
 * </ol>
 *
 * <p><b>Per-tenant, not global.</b> {@code tenant_secret} is an unqualified
 * table name, so it resolves through the schema-per-tenant routing to whichever
 * tenant the calling thread is bound to. One invocation rotates ONE tenant. A
 * caller that wants to rotate the estate has to iterate tenants and establish
 * the context for each; this class deliberately does not do that itself,
 * because guessing at the tenant list from inside a leaf module is exactly how
 * a rotation silently skips half the estate and nobody notices until the
 * previous key is removed.
 *
 * <p>Idempotent and safe to re-run: re-encrypting an already-current row is
 * wasted work, not damage.
 */
@Service
public class SecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(SecretRotationService.class);

    private final TenantSecretRepository repository;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;

    public SecretRotationService(
        TenantSecretRepository repository,
        EncryptionService encryptionService,
        AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.auditLogService = auditLogService;
    }

    /**
     * Result of one tenant's rotation. {@code failed} is the number that could
     * not be decrypted with either key - those rows are left exactly as they
     * were rather than being overwritten with garbage, and they are the
     * signal that {@code APP_ENCRYPTION_KEY_PREVIOUS} is wrong or was removed
     * too early.
     */
    public record RotationResult(int total, int rotated, int failed) {

        public boolean isClean() {
            return failed == 0;
        }
    }

    /**
     * Re-encrypts every row in the calling tenant's vault under the current key.
     *
     * <p>Revoked and expired rows are rotated too, on purpose: they are kept
     * for the audit trail, and a row that cannot be decrypted after the
     * previous key is retired is a row whose history becomes unreadable.
     */
    @Transactional
    public RotationResult rotateAll(String actorId) {
        List<TenantSecret> all = repository.findAll();
        int rotated = 0;
        int failed = 0;

        for (TenantSecret secret : all) {
            try {
                secret.replaceCiphertext(encryptionService.reencrypt(secret.encryptedValue()));
                rotated++;
            } catch (RuntimeException e) {
                // Leave the row untouched. Overwriting it with anything would
                // destroy a secret that a correct previous key could still
                // recover.
                failed++;
                log.error("Could not re-encrypt vault secret {} - leaving it on the old key", secret.getName(), e);
            }
        }

        RotationResult result = new RotationResult(all.size(), rotated, failed);
        auditLogService.record(
            actorId,
            "SECRET_KEY_ROTATED",
            "SECRET",
            null,
            failed == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
            "rotated " + rotated + " of " + all.size() + " vault secrets"
                + (failed > 0 ? ", " + failed + " FAILED and remain on the previous key" : ""));

        if (failed > 0) {
            log.error("Vault key rotation incomplete: {} of {} secrets could not be re-encrypted. "
                + "Do NOT remove APP_ENCRYPTION_KEY_PREVIOUS.", failed, all.size());
        } else {
            log.info("Vault key rotation complete: {} secrets now on the current key", rotated);
        }
        return result;
    }
}
