package com.agenticai.core.secrets;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.crypto.EncryptionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vault's only door. Every write, and - importantly - every <i>read</i> of
 * a plaintext secret goes through this class so that it can be audited.
 *
 * <p>The platform already has ~21 bespoke credential entities and 27 encrypted
 * columns, and <b>not one of them records who read a secret</b>: {@code
 * EncryptionService.decrypt} is called freely on request threads with no
 * instrumentation. A consumer vault holding end users' actual passwords cannot
 * inherit that property, so {@link #resolveForRun} writes a SECRET_ACCESSED
 * audit row on every single resolution, including the ones that fail.
 *
 * <h2>What this class deliberately does not offer</h2>
 * There is no "get secret by id" that returns plaintext, and no read path a
 * controller can reach. A secret leaves the vault in exactly one direction:
 * {@link #resolveForRun(String, String)}, called by
 * {@link SecretReferenceResolver} from inside the tool-execution boundary.
 * That is what keeps plaintext out of agent configs, out of the model's
 * context window, and out of HTTP responses.
 */
@Service
public class SecretVaultService {

    private static final Logger log = LoggerFactory.getLogger(SecretVaultService.class);

    /**
     * Names are restricted to what is safe to interpolate everywhere a secret
     * can be injected without any escaping: inside the reference syntax, and
     * verbatim as a POSIX environment-variable name when an MCP stdio server
     * is launched. Allowing '.' or '}' here would make the reference parser
     * ambiguous; allowing lowercase would make two spellings of one name look
     * interchangeable when the unique index says they are not.
     */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /**
     * Below this length a "last 4 characters" preview leaks a meaningful
     * fraction of the secret (4 of 6 characters is not a hint, it is most of a
     * PIN), so short secrets get no preview at all.
     */
    private static final int MIN_LENGTH_FOR_PREVIEW = 8;

    /**
     * Shortest value the vault will store.
     *
     * <p>This exists because {@link SecretRedactor} refuses to scrub anything
     * below its own threshold - a 4-character value occurs inside ordinary
     * words and numbers, so blanking it would corrupt the tool output the
     * agent has to reason about. That refusal was documented as "such a secret
     * should not have been stored in the first place", but nothing enforced
     * it, so a 4-digit PIN or door code - a mainstream case for a CONSUMER
     * vault, not a hypothetical - could be stored and would then never be
     * redacted out of an error message on its way to the model.
     *
     * <p>Refusing at the door is the honest fix: a value this platform cannot
     * protect should not be accepted with an implied promise that it can.
     */
    static final int MIN_VALUE_LENGTH = 6;

    private final TenantSecretRepository repository;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;

    public SecretVaultService(
        TenantSecretRepository repository,
        EncryptionService encryptionService,
        AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.auditLogService = auditLogService;
    }

    // ---------------------------------------------------------------- writes

    /**
     * Stores a new secret, or rotates the existing one of the same name and
     * scope. Create-or-rotate rather than create-or-fail because the caller's
     * intent when re-submitting a name is always "this is the current value",
     * and a hard failure there pushes people toward deleting and recreating -
     * which would break every reference and lose the audit trail.
     */
    @Transactional
    public TenantSecret store(
        String name,
        SecretScope scope,
        String ownerUserId,
        SecretKind kind,
        String description,
        String plaintextValue,
        Instant expiresAt,
        boolean codeToolAccess,
        String actorUserId
    ) {
        return store(name, scope, ownerUserId, kind, description, plaintextValue, expiresAt, codeToolAccess,
            null, actorUserId);
    }

    /**
     * {@link #store(String, SecretScope, String, SecretKind, String, String, Instant, boolean, String)}
     * plus the destination allow-list (SEC-006). {@code allowedHosts} null =
     * leave an existing secret's list unchanged (empty on create); non-null
     * replaces it after {@link SecretHostPolicy#normalisePatterns} validation.
     */
    @Transactional
    public TenantSecret store(
        String name,
        SecretScope scope,
        String ownerUserId,
        SecretKind kind,
        String description,
        String plaintextValue,
        Instant expiresAt,
        boolean codeToolAccess,
        List<String> allowedHosts,
        String actorUserId
    ) {
        List<String> hostPatterns = allowedHosts == null ? null : SecretHostPolicy.normalisePatterns(allowedHosts);
        String normalized = requireValidName(name);
        if (plaintextValue == null || plaintextValue.isEmpty()) {
            throw new IllegalArgumentException("Secret value must not be empty");
        }
        if (plaintextValue.length() < MIN_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                "A secret must be at least " + MIN_VALUE_LENGTH + " characters. Shorter values cannot be "
                    + "reliably removed from tool output, so storing one would promise protection "
                    + "this platform cannot deliver.");
        }
        String owner = resolveOwner(scope, ownerUserId, actorUserId);

        String encrypted = encryptionService.encrypt(plaintextValue);
        String preview = previewOf(plaintextValue);

        Optional<TenantSecret> existing = scope == SecretScope.TENANT
            ? repository.findTenantScoped(normalized)
            : repository.findByNameAndScopeAndOwnerUserId(normalized, SecretScope.USER, owner);

        if (existing.isPresent()) {
            TenantSecret secret = existing.get();
            secret.rotate(encrypted, preview, expiresAt, description);
            // Widening code-tool reach is audited separately from the rotation,
            // because they are different decisions with very different
            // consequences and a combined line would hide the riskier one.
            if (secret.isCodeToolAccess() != codeToolAccess) {
                secret.setCodeToolAccess(codeToolAccess);
                audit(actorUserId, codeToolAccess ? "SECRET_CODE_ACCESS_GRANTED" : "SECRET_CODE_ACCESS_REVOKED",
                    secret, AuditOutcome.SUCCESS, null);
            }
            if (hostPatterns != null && !hostPatterns.equals(secret.getAllowedHosts())) {
                secret.setAllowedHosts(hostPatterns);
                audit(actorUserId, "SECRET_ALLOWED_HOSTS_CHANGED", secret, AuditOutcome.SUCCESS,
                    "hosts=" + String.join(",", hostPatterns));
            }
            audit(actorUserId, "SECRET_ROTATED", secret, AuditOutcome.SUCCESS, "scope=" + scope);
            return secret;
        }

        TenantSecret secret = new TenantSecret(
            normalized, scope, owner, kind, description, encrypted, preview, expiresAt,
            codeToolAccess, actorUserId);
        if (hostPatterns != null && !hostPatterns.isEmpty()) {
            secret.setAllowedHosts(hostPatterns);
        }
        TenantSecret saved = repository.save(secret);
        audit(actorUserId, "SECRET_CREATED", saved, AuditOutcome.SUCCESS,
            "scope=" + scope + ", kind=" + kind + (codeToolAccess ? ", CODE-TOOL-READABLE" : "")
                + (hostPatterns == null || hostPatterns.isEmpty() ? "" : ", hosts=" + String.join(",", hostPatterns)));
        if (codeToolAccess) {
            audit(actorUserId, "SECRET_CODE_ACCESS_GRANTED", saved, AuditOutcome.SUCCESS, "at creation");
        }
        return saved;
    }

    /**
     * Description/expiry only - there is no path here that touches the value.
     *
     * <p>Still gated like {@link #revoke} and {@link #purge}, despite only
     * writing metadata: {@code expiresAt} is load-bearing. Setting it to a past
     * instant makes {@link TenantSecret#isResolvable()} false, which stops every
     * agent and MCP server referencing that secret - the same effect as a
     * revoke, reachable by a caller who was never allowed to revoke.
     * {@link #requireVisible} alone does not cover it, because every member can
     * legitimately see a TENANT-scoped row.
     */
    @Transactional
    public TenantSecret updateMetadata(
        UUID id, String description, Instant expiresAt, String actorUserId, boolean actorIsWorkspaceAdmin) {
        TenantSecret secret = requireVisible(id, actorUserId);
        requireCanWrite(secret, actorUserId, actorIsWorkspaceAdmin);
        secret.updateMetadata(description, expiresAt);
        audit(actorUserId, "SECRET_UPDATED", secret, AuditOutcome.SUCCESS, null);
        return secret;
    }

    /**
     * Revokes rather than deletes: the row, its last_used_at and its audit
     * history survive, so "this key was compromised - what used it, and when
     * did we cut it off" is answerable after the fact.
     */
    @Transactional
    public void revoke(UUID id, String actorUserId, boolean actorIsWorkspaceAdmin) {
        TenantSecret secret = requireVisible(id, actorUserId);
        requireCanWrite(secret, actorUserId, actorIsWorkspaceAdmin);
        secret.revoke();
        audit(actorUserId, "SECRET_REVOKED", secret, AuditOutcome.SUCCESS, null);
    }

    /**
     * Irreversible removal, for a genuine erasure request. Separate from
     * {@link #revoke} and never the default, because it destroys the evidence
     * that the secret ever existed.
     */
    @Transactional
    public void purge(UUID id, String actorUserId, boolean actorIsWorkspaceAdmin) {
        TenantSecret secret = requireVisible(id, actorUserId);
        requireCanWrite(secret, actorUserId, actorIsWorkspaceAdmin);
        // Audited BEFORE the delete: after it, the id no longer resolves to a
        // name, and an audit row reading "deleted <uuid>" is not usable.
        audit(actorUserId, "SECRET_PURGED", secret, AuditOutcome.SUCCESS, "irreversible");
        repository.delete(secret);
    }

    // ----------------------------------------------------------------- reads

    /** Metadata only. No ciphertext, no plaintext - see the class javadoc. */
    @Transactional(readOnly = true)
    public List<TenantSecret> listVisibleTo(String userId) {
        return repository.findVisibleTo(userId);
    }

    /**
     * The one plaintext exit. Resolves {@code name} for a run initiated by
     * {@code userId}: their own USER-scoped secret wins, otherwise the
     * TENANT-scoped one.
     *
     * <p>Callers must not cache, log, or return the result, and must pass it
     * straight into the carrier (env var, header, request body). Every call -
     * hit, miss, expired, revoked - produces an audit row.
     *
     * @return empty when no resolvable secret of that name exists for this
     *         user. Empty rather than an exception because the resolver has to
     *         distinguish "unresolved reference" from "vault is broken", and
     *         because throwing here would put a secret name into a stack trace
     *         that may be surfaced to the model.
     */
    @Transactional
    public Optional<String> resolveForRun(String name, String userId) {
        String normalized;
        try {
            normalized = requireValidName(name);
        } catch (IllegalArgumentException e) {
            auditRaw(userId, "SECRET_ACCESSED", null, AuditOutcome.FAILURE, "invalid name");
            return Optional.empty();
        }

        List<TenantSecret> candidates = repository.findResolvableByName(normalized, userId);
        if (candidates.isEmpty()) {
            auditRaw(userId, "SECRET_ACCESSED", normalized, AuditOutcome.FAILURE, "no such secret");
            return Optional.empty();
        }

        // findResolvableByName orders USER-scoped first; take the first row
        // that is actually usable so that an expired personal secret does not
        // silently fall back to the shared workspace account. That fallback
        // would be a privilege escalation disguised as a convenience.
        TenantSecret secret = candidates.get(0);
        if (!secret.isResolvable()) {
            audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.FAILURE,
                secret.getStatus() == SecretStatus.REVOKED ? "revoked" : "expired");
            return Optional.empty();
        }

        String plaintext;
        try {
            plaintext = encryptionService.decrypt(secret.encryptedValue());
        } catch (RuntimeException e) {
            // Almost always an APP_ENCRYPTION_KEY mismatch between environments
            // (a known, repeatedly-hit failure mode in this platform). Log the
            // cause without the ciphertext.
            log.error("Failed to decrypt vault secret {} - check APP_ENCRYPTION_KEY", normalized, e);
            audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.FAILURE, "decrypt failed");
            return Optional.empty();
        }

        secret.markUsed();
        audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.SUCCESS, "scope=" + secret.getScope());
        return Optional.of(plaintext);
    }

    /**
     * The sandboxed-code exit. Resolves {@code name} only if it is a
     * TENANT-scoped secret that has been explicitly opted in to code-tool use
     * (V228 {@code code_tool_access}).
     *
     * <p><b>Why this is separate from {@link #resolveForRun}.</b> Handing a
     * value to tenant-authored code is not the same act as handing it to an
     * MCP server we launched. A program can return its own credential,
     * transformed past any redaction - codepoints, hex, reversed - so a grant
     * is equivalent to disclosing the plaintext to everyone who can author a
     * code tool in the workspace. Redaction is a guard against a program
     * ACCIDENTALLY echoing a value, never against one that wants to leak it,
     * and nothing at this layer can change that.
     *
     * <p>So the containment is the opt-in, not the scrubbing: by default no
     * vault secret is reachable from sandboxed code at all, and a stolen admin
     * session can only recover the ones somebody deliberately marked.
     *
     * <p>Audited under its own action type so that "a program read this
     * credential" is greppable, and never blends into the ordinary
     * SECRET_ACCESSED traffic from MCP and agent runs.
     */
    @Transactional
    public Optional<String> resolveForCodeTool(String name, String toolName) {
        String normalized;
        try {
            normalized = requireValidName(name);
        } catch (IllegalArgumentException e) {
            auditRaw(null, "SECRET_ACCESSED_BY_CODE", null, AuditOutcome.FAILURE, "invalid name");
            return Optional.empty();
        }

        Optional<TenantSecret> found = repository.findCodeToolReadable(normalized);
        if (found.isEmpty()) {
            // Deliberately one outcome for "no such secret" and "exists but not
            // opted in": a code tool must not be usable to probe which vault
            // names exist.
            auditRaw(null, "SECRET_ACCESSED_BY_CODE", normalized, AuditOutcome.FAILURE,
                "not code-tool-readable (tool=" + toolName + ")");
            return Optional.empty();
        }

        TenantSecret secret = found.get();
        if (!secret.isResolvable()) {
            audit(null, "SECRET_ACCESSED_BY_CODE", secret, AuditOutcome.FAILURE,
                secret.getStatus() == SecretStatus.REVOKED ? "revoked" : "expired");
            return Optional.empty();
        }

        String plaintext;
        try {
            plaintext = encryptionService.decrypt(secret.encryptedValue());
        } catch (RuntimeException e) {
            log.error("Failed to decrypt vault secret {} - check APP_ENCRYPTION_KEY", normalized, e);
            audit(null, "SECRET_ACCESSED_BY_CODE", secret, AuditOutcome.FAILURE, "decrypt failed");
            return Optional.empty();
        }

        secret.markUsed();
        audit(null, "SECRET_ACCESSED_BY_CODE", secret, AuditOutcome.SUCCESS, "tool=" + toolName);
        return Optional.of(plaintext);
    }

    /**
     * Replaces the destination allow-list of an existing secret (SEC-006).
     * Gated exactly like {@link #updateMetadata}: widening where a shared
     * credential may be sent is as consequential as rotating it.
     */
    @Transactional
    public TenantSecret updateAllowedHosts(
        UUID id, List<String> allowedHosts, String actorUserId, boolean actorIsWorkspaceAdmin) {
        List<String> hostPatterns = SecretHostPolicy.normalisePatterns(allowedHosts);
        TenantSecret secret = requireVisible(id, actorUserId);
        requireCanWrite(secret, actorUserId, actorIsWorkspaceAdmin);
        secret.setAllowedHosts(hostPatterns);
        audit(actorUserId, "SECRET_ALLOWED_HOSTS_CHANGED", secret, AuditOutcome.SUCCESS,
            "hosts=" + String.join(",", hostPatterns));
        return secret;
    }

    /**
     * The URL-bound plaintext exit (SEC-006): like {@link #resolveForRun} but
     * resolves ONLY when {@code destinationHost} matches the secret's
     * {@link TenantSecret#getAllowedHosts() allowed hosts}. A secret with no
     * allowed hosts never resolves here. Used by {@code http_request} and the
     * workflow HTTP node, where the destination is chosen by a model or by
     * templated run input.
     *
     * <p>Every outcome is audited; the reason ("destination not allowed") is
     * recorded in the audit row only, never returned - callers collapse every
     * failure into one generic message so the endpoint cannot be used to
     * enumerate secret names.
     */
    @Transactional
    public Optional<String> resolveForDestination(String name, String userId, String destinationHost) {
        String normalized;
        try {
            normalized = requireValidName(name);
        } catch (IllegalArgumentException e) {
            auditRaw(userId, "SECRET_ACCESSED", null, AuditOutcome.FAILURE, "invalid name");
            return Optional.empty();
        }
        List<TenantSecret> candidates = repository.findResolvableByName(normalized, userId);
        if (candidates.isEmpty()) {
            auditRaw(userId, "SECRET_ACCESSED", normalized, AuditOutcome.FAILURE, "no such secret");
            return Optional.empty();
        }
        TenantSecret secret = candidates.get(0);
        if (!secret.isResolvable()) {
            audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.FAILURE,
                secret.getStatus() == SecretStatus.REVOKED ? "revoked" : "expired");
            return Optional.empty();
        }
        if (!SecretHostPolicy.matches(secret.getAllowedHosts(), destinationHost)) {
            audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.FAILURE,
                "destination not allowed: " + (destinationHost == null ? "-" : destinationHost));
            return Optional.empty();
        }
        String plaintext;
        try {
            plaintext = encryptionService.decrypt(secret.encryptedValue());
        } catch (RuntimeException e) {
            log.error("Failed to decrypt vault secret {} - check APP_ENCRYPTION_KEY", normalized, e);
            audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.FAILURE, "decrypt failed");
            return Optional.empty();
        }
        secret.markUsed();
        audit(userId, "SECRET_ACCESSED", secret, AuditOutcome.SUCCESS,
            "scope=" + secret.getScope() + ", destination=" + destinationHost);
        return Optional.of(plaintext);
    }

    // ------------------------------------------------------------- internals

    private String requireValidName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "Secret name must be UPPER_SNAKE_CASE, start with a letter, and be at most 64 characters");
        }
        return normalized;
    }

    private String resolveOwner(SecretScope scope, String ownerUserId, String actorUserId) {
        if (scope == SecretScope.TENANT) {
            return null;
        }
        // A USER secret always belongs to the caller. Accepting an arbitrary
        // ownerUserId from a request would let an admin plant a secret that
        // then resolves under someone else's identity.
        String owner = ownerUserId != null && !ownerUserId.isBlank() ? ownerUserId : actorUserId;
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("A user-scoped secret requires an authenticated owner");
        }
        return owner;
    }

    /**
     * Removing a shared workspace secret is as consequential as creating one -
     * it breaks every agent referencing it - so it takes the same admin right.
     * A USER-scoped secret is the caller's own; {@link #requireVisible} has
     * already established they own it.
     */
    private void requireCanWrite(TenantSecret secret, String actorUserId, boolean actorIsWorkspaceAdmin) {
        if (secret.getScope() == SecretScope.TENANT && !actorIsWorkspaceAdmin) {
            auditRaw(actorUserId, "SECRET_ACCESS_DENIED", secret.getName(), AuditOutcome.FAILURE, "not workspace admin");
            throw new SecretWriteForbiddenException();
        }
    }

    private TenantSecret requireVisible(UUID id, String actorUserId) {
        TenantSecret secret = repository.findById(id)
            .orElseThrow(() -> new SecretNotFoundException());
        if (secret.getScope() == SecretScope.USER && !secret.getOwnerUserId().equals(actorUserId)) {
            // Same message as "not found" on purpose: distinguishing them would
            // let one user probe for another's secret names.
            auditRaw(actorUserId, "SECRET_ACCESS_DENIED", secret.getName(), AuditOutcome.FAILURE, "not owner");
            throw new SecretNotFoundException();
        }
        return secret;
    }

    private String previewOf(String plaintext) {
        if (plaintext.length() < MIN_LENGTH_FOR_PREVIEW) {
            return null;
        }
        return plaintext.substring(plaintext.length() - 4);
    }

    private void audit(String actorId, String action, TenantSecret secret, AuditOutcome outcome, String detail) {
        auditLogService.record(
            actorId, action, "SECRET",
            secret.getId() != null ? secret.getId().toString() : secret.getName(),
            outcome,
            detail == null ? secret.getName() : secret.getName() + " (" + detail + ")");
    }

    private void auditRaw(String actorId, String action, String name, AuditOutcome outcome, String detail) {
        auditLogService.record(actorId, action, "SECRET", name, outcome, detail);
    }
}
