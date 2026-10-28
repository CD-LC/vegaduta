package com.agenticai.core.knowledge;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-collection access control for the knowledge base, built on the same {@link
 * ResourceAccessService} grants and {@link PermissionLevel}s that agents and workflows use
 * ({@link ResourceType#KNOWLEDGE_COLLECTION}).
 *
 * <p><b>The model.</b> A collection has an owner ({@code created_by}) and a {@link
 * KnowledgeVisibility}:
 *
 * <ul>
 *   <li>{@code TENANT} (the default, and every pre-existing collection): every tenant member can
 *       read. Writing stays as it always was - governed by the endpoint authority
 *       ({@code PERM_MANAGE_AGENTS}, see {@code ToolChannelWorkflowRules}) - so an upgrade changes
 *       nothing for anyone.
 *   <li>{@code SHARED}: the owner, tenant/platform admins and grantees. A {@link
 *       PermissionLevel#VIEWER} grant lets the grantee search and download, {@link
 *       PermissionLevel#EDITOR} also ingest and delete documents. {@link PermissionLevel#RUNNER}
 *       grants nothing on a collection.
 *   <li>{@code PRIVATE}: owner and tenant/platform admins only; grants are dormant.
 * </ul>
 *
 * <p>The owner always has full access ({@link Access#MANAGE}: also change visibility and delete the
 * collection), and so do {@code ROLE_tenant-admin} and {@code ROLE_platform-admin} - the broad
 * agent-builder permission does <em>not</em> override a private collection. A legacy collection
 * (no owner) can therefore only be restricted by an administrator.
 *
 * <p><b>Who is "the caller".</b> The principal is the {@link TenantContext} subject on the calling
 * thread, so an interactive agent turn, a workflow run and a REST call all resolve to the user who
 * started them. No caller (no tenant context, blank subject) means no access - it fails closed.
 *
 * <p><b>Agent and workflow retrieval.</b> An agent's {@code allowedKnowledgeCollectionIds} is a
 * scoping list the agent's builder chose; it never <em>grants</em> access. What an agent turn can
 * retrieve is the allow-list intersected with the invoking user's access (the intersection falls
 * out of {@link KnowledgeSearchService} filtering the allow-listed ids to the readable set). A
 * background / unattended run has no user: its context carries a synthetic subject that owns
 * nothing and holds no grants, so it sees TENANT collections only, unless the run is wired to act
 * as the agent's owner ({@link Principal#ofOwner}, via {@link
 * KnowledgeSearchToolCallback#asOwner}), in which case it gets the owner's access (never admin
 * override). MCP API keys and public share links carry no user either and behave the same way: they
 * can never reach a restricted collection.
 *
 * <p>Denials are audited ({@code knowledge.collection.access.denied}, outcome {@code DENIED}) and
 * surface to REST callers as 404, like every other {@code core/sharing} denial.
 */
@Service
@Profile("knowledge")
public class KnowledgeAccessService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAccessService.class);

    /** Audit action for a refused read / write / manage attempt on a collection. */
    public static final String ACTION_DENIED = "knowledge.collection.access.denied";

    /** Effective access to one collection, lowest to highest. */
    public enum Access {
        NONE,
        /** Search and download. */
        READ,
        /** Read, plus ingest, reembed and delete documents. */
        WRITE,
        /** Write, plus change visibility, delete the collection, manage grants. */
        MANAGE
    }

    /**
     * The identity retrieval is evaluated for. {@code subject == null} is "unresolved" and has no
     * access. {@code admin} is the tenant/platform administrator override.
     */
    public record Principal(String subject, boolean admin) {

        /** No caller could be resolved: sees nothing. */
        public static final Principal NONE = new Principal(null, false);

        /**
         * Explicit system / platform scope for platform-internal code that has no user (it sees
         * everything). Never derive this from request input.
         */
        public static final Principal SYSTEM = new Principal("system", true);

        /**
         * An agent's owner, for unattended runs: the owner's own collections and grants plus
         * tenant-visible ones, but no administrator override even if the owner is an admin.
         */
        public static Principal ofOwner(String ownerSubject) {
            return ownerSubject == null || ownerSubject.isBlank() ? NONE : new Principal(ownerSubject, false);
        }

        boolean resolved() {
            return subject != null && !subject.isBlank();
        }
    }

    private final KnowledgeCollectionRepository collections;
    private final KnowledgeDocumentRepository documents;
    private final ResourceAccessService sharing;
    private final AuditLogService audit;

    public KnowledgeAccessService(
        KnowledgeCollectionRepository collections,
        KnowledgeDocumentRepository documents,
        ResourceAccessService sharing,
        AuditLogService audit
    ) {
        this.collections = collections;
        this.documents = documents;
        this.sharing = sharing;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- principal

    /** The calling thread's user, or {@link Principal#NONE} when none can be resolved. */
    public Principal currentPrincipal() {
        TenantContext context = TenantContext.current().orElse(null);
        if (context == null || context.getSubject() == null || context.getSubject().isBlank()) {
            return Principal.NONE;
        }
        return new Principal(context.getSubject(), sharing.isAdminFor(ResourceType.KNOWLEDGE_COLLECTION, context));
    }

    /** The subject to record as a new collection's owner; null when the caller is unresolved. */
    public String currentSubject() {
        return currentPrincipal().subject();
    }

    // ---------------------------------------------------------------- evaluation

    /** Effective access of {@code principal} to {@code collection}. Does not audit. */
    public Access accessOf(KnowledgeCollection collection, Principal principal) {
        return evaluate(collection, principal, () -> grantsOf(principal));
    }

    private Access evaluate(
        KnowledgeCollection collection, Principal principal, Supplier<Map<UUID, PermissionLevel>> grants
    ) {
        if (principal == null || !principal.resolved()) {
            return Access.NONE;
        }
        if (principal.admin()) {
            return Access.MANAGE;
        }
        String owner = collection.getCreatedBy();
        if (owner != null && owner.equals(principal.subject())) {
            return Access.MANAGE;
        }
        KnowledgeVisibility visibility = collection.getVisibility();
        Access best = visibility == KnowledgeVisibility.TENANT ? Access.WRITE : Access.NONE;
        if (visibility != KnowledgeVisibility.PRIVATE) {
            PermissionLevel level = grants.get().get(collection.getId());
            if (level == PermissionLevel.EDITOR && best.compareTo(Access.WRITE) < 0) {
                best = Access.WRITE;
            } else if (level == PermissionLevel.VIEWER && best.compareTo(Access.READ) < 0) {
                best = Access.READ;
            }
        }
        return best;
    }

    private Map<UUID, PermissionLevel> grantsOf(Principal principal) {
        return sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, principal.subject());
    }

    /**
     * The collections {@code principal} may read, in input order. {@code explicitRequest} marks a
     * caller that named the collections (so a dropped id is a refused request worth auditing)
     * rather than an unscoped "everything I can see" search, where dropping is the normal outcome.
     */
    public List<KnowledgeCollection> filterReadable(
        List<KnowledgeCollection> candidates, Principal principal, boolean explicitRequest
    ) {
        Principal who = principal == null ? Principal.NONE : principal;
        // Grants are one query per call, and only needed once a non-TENANT collection is in play.
        Map<UUID, PermissionLevel>[] cache = newCache();
        Supplier<Map<UUID, PermissionLevel>> grants = () -> {
            if (cache[0] == null) {
                cache[0] = grantsOf(who);
            }
            return cache[0];
        };
        List<KnowledgeCollection> readable = new ArrayList<>(candidates.size());
        for (KnowledgeCollection candidate : candidates) {
            if (evaluate(candidate, who, grants).compareTo(Access.READ) >= 0) {
                readable.add(candidate);
            } else if (explicitRequest) {
                deny(who, "read", candidate.getId(), "search");
            }
        }
        return readable;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, PermissionLevel>[] newCache() {
        return (Map<UUID, PermissionLevel>[]) new Map[1];
    }

    // ---------------------------------------------------------------- guards (throw 404-shaped)

    @Transactional(readOnly = true)
    public void requireRead(UUID collectionId) {
        require(load(collectionId), Access.READ, "read");
    }

    /** Ingest, reembed, delete documents. */
    @Transactional(readOnly = true)
    public void requireWrite(UUID collectionId) {
        require(load(collectionId), Access.WRITE, "write");
    }

    public void requireWrite(KnowledgeCollection collection) {
        require(collection, Access.WRITE, "write");
    }

    /**
     * Rename / edit / delete a collection. A TENANT collection keeps its historic rule (anyone who
     * may write); a restricted one needs the owner or an administrator.
     */
    public void requireModify(KnowledgeCollection collection) {
        boolean open = collection.getVisibility() == KnowledgeVisibility.TENANT;
        require(collection, open ? Access.WRITE : Access.MANAGE, "modify");
    }

    /** Change visibility, manage grants: owner or administrator only (admin only for legacy rows). */
    public void requireManage(KnowledgeCollection collection) {
        require(collection, Access.MANAGE, "manage");
    }

    /** Every id must exist and be readable by the caller, else 404 for the first that is not. */
    @Transactional(readOnly = true)
    public void requireReadAll(Collection<UUID> collectionIds) {
        if (collectionIds == null) {
            return;
        }
        for (UUID id : collectionIds) {
            requireRead(id);
        }
    }

    @Transactional(readOnly = true)
    public void requireReadDocument(UUID documentId) {
        requireRead(collectionOfDocument(documentId));
    }

    @Transactional(readOnly = true)
    public void requireWriteDocument(UUID documentId) {
        requireWrite(collectionOfDocument(documentId));
    }

    private UUID collectionOfDocument(UUID documentId) {
        return documents
            .findById(documentId)
            .orElseThrow(() -> new KnowledgeDocumentNotFoundException(documentId))
            .getCollectionId();
    }

    private KnowledgeCollection load(UUID collectionId) {
        return collections
            .findById(collectionId)
            .orElseThrow(() -> new KnowledgeCollectionNotFoundException(collectionId));
    }

    private void require(KnowledgeCollection collection, Access needed, String operation) {
        Principal principal = currentPrincipal();
        if (accessOf(collection, principal).compareTo(needed) < 0) {
            deny(principal, operation, collection.getId(), "request");
            // 404-shaped on purpose: a caller learns nothing about a collection they cannot see.
            throw new KnowledgeCollectionNotFoundException(collection.getId());
        }
    }

    // ---------------------------------------------------------------- audit

    private void deny(Principal principal, String operation, UUID collectionId, String via) {
        String actor = principal.resolved() ? principal.subject() : "unresolved";
        try {
            audit.record(
                actor,
                ACTION_DENIED,
                "knowledge_collection",
                String.valueOf(collectionId),
                AuditOutcome.DENIED,
                "Denied " + operation + " on knowledge collection (" + via + ")"
            );
        } catch (RuntimeException e) {
            // Never let the audit trail turn a clean denial into a 500, and never fail open.
            log.warn("Could not audit denied knowledge access to {}: {}", collectionId, e.toString());
        }
    }
}
