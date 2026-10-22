package com.agenticai.core.workflow.comment;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Canvas comments (phase 4). Threads anchor to a node ({@code nodeId}) or a canvas point ({@code
 * x}/{@code y}); replies are one level deep (a reply to a reply joins the root's thread).
 *
 * <p><b>Who may do what.</b> {@link com.agenticai.core.sharing.PermissionLevel} ranks RUNNER &lt;
 * VIEWER &lt; EDITOR, and RUNNER cannot see the graph at all, so "RUNNER or above" collapses to
 * VIEWER: anyone who can see the canvas can read AND write comments - reviewers are exactly the
 * people who view but do not edit. Only the author edits or deletes a comment; the thread's author
 * or any EDITOR resolves / reopens it.
 *
 * <p><b>Orphans.</b> A thread whose node is no longer in the saved graph is returned with {@code
 * orphaned: true} - never deleted.
 *
 * <p><b>Deletes</b> are hard unless the comment has live replies, in which case it is blanked
 * ({@code deleted: true}, body null) so the thread stays readable.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code context()} probes EDITOR access by catching
 * the access exception, which inside a surrounding transaction could mark it rollback-only. Every
 * write here is a single-row repository call (resolve touches the root only), so nothing needs
 * atomicity.
 *
 * <p><b>Mentions</b> are user subject ids. Each newly mentioned user (not the author) gets a
 * WORKFLOW notification in their bell linking to {@code
 * /workflows/{workflowId}?comment={commentId}}. Display names: the tenant schema has no user
 * directory, so a mention resolves to the name that user last commented under here, else null (the
 * client shows the id).
 */
@Service
public class WorkflowCommentService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCommentService.class);

    static final int MAX_BODY_CHARS = 4000;
    static final int MAX_MENTIONS = 20;

    private final WorkflowCommentRepository repository;
    private final WorkflowService workflowService;
    private final ObjectProvider<NotificationService> notifications;
    private final ObjectMapper objectMapper;

    public WorkflowCommentService(
            WorkflowCommentRepository repository,
            WorkflowService workflowService,
            ObjectProvider<NotificationService> notifications,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.workflowService = workflowService;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    public record Person(String id, String displayName) {}

    public record Anchor(Double x, Double y) {}

    public record CommentView(
            UUID id,
            UUID parentId,
            String nodeId,
            Anchor anchor,
            String body,
            List<Person> mentions,
            Person author,
            boolean resolved,
            String resolvedBy,
            Instant resolvedAt,
            boolean deleted,
            boolean orphaned,
            boolean canEdit,
            boolean canResolve,
            Instant createdAt,
            Instant updatedAt,
            List<CommentView> replies) {}

    public record CreateRequest(
            String body, String nodeId, Double x, Double y, UUID parentId, List<String> mentions) {}

    public record UpdateRequest(String body, List<String> mentions, Boolean resolved) {}

    // ------------------------------------------------------------------

    public List<CommentView> list(UUID workflowId) {
        WorkflowDefinition definition = workflowService.requireViewerAccess(workflowId);
        Context ctx = context(workflowId);
        List<WorkflowComment> all =
                repository.findByWorkflowIdOrderByCreatedAtAsc(definition.getId());
        Map<UUID, List<WorkflowComment>> replies = new LinkedHashMap<>();
        List<WorkflowComment> roots = new ArrayList<>();
        for (WorkflowComment c : all) {
            if (c.getParentId() == null) {
                roots.add(c);
            } else {
                replies.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, String> names = new HashMap<>();
        List<CommentView> out = new ArrayList<>();
        for (WorkflowComment root : roots) {
            List<CommentView> children = new ArrayList<>();
            for (WorkflowComment reply : replies.getOrDefault(root.getId(), List.of())) {
                children.add(view(reply, root, ctx, names, List.of()));
            }
            out.add(view(root, root, ctx, names, children));
        }
        return out;
    }

    public CommentView create(UUID workflowId, CreateRequest request) {
        workflowService.requireViewerAccess(workflowId);
        Context ctx = context(workflowId);
        String body = requireBody(request == null ? null : request.body());
        List<String> mentions = cleanMentions(request.mentions());

        UUID parentId = null;
        String nodeId = null;
        Double x = null;
        Double y = null;
        if (request.parentId() != null) {
            WorkflowComment parent = load(workflowId, request.parentId());
            parentId = parent.getParentId() != null ? parent.getParentId() : parent.getId();
        } else {
            nodeId =
                    request.nodeId() == null || request.nodeId().isBlank()
                            ? null
                            : request.nodeId().strip();
            if (nodeId != null && nodeId.length() > 128) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeId is too long.");
            }
            if (nodeId == null) {
                if (request.x() == null
                        || request.y() == null
                        || !Double.isFinite(request.x())
                        || !Double.isFinite(request.y())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "A new comment needs a nodeId or both x and y.");
                }
                x = request.x();
                y = request.y();
            }
        }

        WorkflowComment saved =
                repository.save(
                        new WorkflowComment(
                                workflowId,
                                parentId,
                                nodeId,
                                x,
                                y,
                                body,
                                writeMentions(mentions),
                                ctx.userId,
                                currentDisplayName()));
        notifyMentions(workflowId, saved, mentions, Set.of());
        WorkflowComment root = parentId == null ? saved : load(workflowId, parentId);
        return view(saved, root, ctx, new HashMap<>(), List.of());
    }

    public CommentView update(UUID workflowId, UUID commentId, UpdateRequest request) {
        workflowService.requireViewerAccess(workflowId);
        Context ctx = context(workflowId);
        WorkflowComment comment = load(workflowId, commentId);
        if (comment.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "This comment was deleted.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to update.");
        }
        if (request.body() != null || request.mentions() != null) {
            if (!ctx.userId.equals(comment.getAuthorId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Only the author can edit a comment.");
            }
            List<String> before = readMentions(comment.getMentions());
            List<String> mentions =
                    request.mentions() == null ? before : cleanMentions(request.mentions());
            String body = request.body() == null ? comment.getBody() : requireBody(request.body());
            comment.edit(body, writeMentions(mentions));
            notifyMentions(workflowId, comment, mentions, new HashSet<>(before));
        }
        if (request.resolved() != null) {
            WorkflowComment root =
                    comment.getParentId() == null
                            ? comment
                            : load(workflowId, comment.getParentId());
            if (!canResolve(root, ctx)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only the thread's author or an editor can resolve it.");
            }
            root.setResolved(request.resolved(), ctx.userId);
            repository.save(root);
        }
        repository.save(comment);
        WorkflowComment root =
                comment.getParentId() == null ? comment : load(workflowId, comment.getParentId());
        return view(comment, root, ctx, new HashMap<>(), List.of());
    }

    public void delete(UUID workflowId, UUID commentId) {
        workflowService.requireViewerAccess(workflowId);
        Context ctx = context(workflowId);
        WorkflowComment comment = load(workflowId, commentId);
        if (!ctx.userId.equals(comment.getAuthorId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only the author can delete a comment.");
        }
        if (comment.getParentId() == null
                && repository.existsByParentIdAndDeletedAtIsNull(comment.getId())) {
            comment.markDeleted();
            repository.save(comment);
        } else {
            repository.delete(comment);
        }
    }

    // ------------------------------------------------------------------

    /** Per-request facts: who is calling, can they edit the workflow, which nodes exist. */
    private record Context(String userId, boolean editor, Set<String> nodeIds) {}

    private Context context(UUID workflowId) {
        String userId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use comments.");
        }
        boolean editor;
        try {
            workflowService.requireEditorAccess(workflowId);
            editor = true;
        } catch (RuntimeException e) {
            editor = false;
        }
        Set<String> nodeIds = new HashSet<>();
        try {
            for (WorkflowNodeDto node : workflowService.graph(workflowId).nodes()) {
                nodeIds.add(node.id());
            }
        } catch (RuntimeException e) {
            log.debug(
                    "Could not read graph of workflow {} for orphan detection: {}",
                    workflowId,
                    e.getMessage());
        }
        return new Context(userId, editor, nodeIds);
    }

    private boolean canResolve(WorkflowComment root, Context ctx) {
        return ctx.editor || ctx.userId.equals(root.getAuthorId());
    }

    private WorkflowComment load(UUID workflowId, UUID commentId) {
        return repository
                .findByIdAndWorkflowId(commentId, workflowId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Comment not found."));
    }

    private CommentView view(
            WorkflowComment c,
            WorkflowComment root,
            Context ctx,
            Map<String, String> names,
            List<CommentView> replies) {
        boolean deleted = c.getDeletedAt() != null;
        boolean orphaned =
                root.getNodeId() != null
                        && !ctx.nodeIds().isEmpty()
                        && !ctx.nodeIds().contains(root.getNodeId());
        List<Person> mentioned = new ArrayList<>();
        for (String id : readMentions(c.getMentions())) {
            mentioned.add(new Person(id, nameOf(id, names)));
        }
        Anchor anchor = c.getAnchorX() == null ? null : new Anchor(c.getAnchorX(), c.getAnchorY());
        return new CommentView(
                c.getId(),
                c.getParentId(),
                c.getParentId() == null ? c.getNodeId() : root.getNodeId(),
                anchor,
                c.getBody(),
                mentioned,
                new Person(c.getAuthorId(), c.getAuthorName()),
                root.isResolved(),
                root.getResolvedBy(),
                root.getResolvedAt(),
                deleted,
                orphaned,
                !deleted && ctx.userId().equals(c.getAuthorId()),
                canResolve(root, ctx),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                replies);
    }

    private String nameOf(String userId, Map<String, String> cache) {
        return cache.computeIfAbsent(
                userId,
                id ->
                        repository
                                .findFirstByAuthorIdAndAuthorNameIsNotNullOrderByCreatedAtDesc(id)
                                .map(WorkflowComment::getAuthorName)
                                .orElse(null));
    }

    private void notifyMentions(
            UUID workflowId,
            WorkflowComment comment,
            List<String> mentions,
            Set<String> alreadyNotified) {
        NotificationService service = notifications.getIfAvailable();
        if (service == null) {
            return;
        }
        String who = comment.getAuthorName() != null ? comment.getAuthorName() : "Someone";
        String preview = comment.getBody() == null ? "" : comment.getBody();
        if (preview.length() > 200) {
            preview = preview.substring(0, 200) + "...";
        }
        for (String userId : mentions) {
            if (userId.equals(comment.getAuthorId()) || alreadyNotified.contains(userId)) {
                continue;
            }
            try {
                service.create(
                        userId,
                        NotificationCategory.WORKFLOW,
                        who + " mentioned you on a workflow",
                        preview,
                        NotificationSeverity.INFO,
                        "/workflows/" + workflowId + "?comment=" + comment.getId(),
                        null);
            } catch (RuntimeException e) {
                // Best-effort, same posture as every other notification call site.
                log.warn(
                        "Failed to notify {} of a workflow comment mention: {}",
                        userId,
                        e.getMessage());
            }
        }
    }

    static String requireBody(String body) {
        String b = body == null ? "" : body.strip();
        if (b.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment can't be empty.");
        }
        if (b.length() > MAX_BODY_CHARS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A comment can be at most " + MAX_BODY_CHARS + " characters.");
        }
        return b;
    }

    static List<String> cleanMentions(List<String> mentions) {
        if (mentions == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String m : mentions) {
            if (m != null && !m.isBlank() && m.length() <= 255) {
                out.add(m.strip());
            }
        }
        if (out.size() > MAX_MENTIONS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At most " + MAX_MENTIONS + " mentions per comment.");
        }
        return new ArrayList<>(out);
    }

    private String writeMentions(List<String> mentions) {
        if (mentions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(mentions);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readMentions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * The caller's display name from their token (name, then preferred_username, then email), or
     * null.
     */
    static String currentDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            for (String claim : List.of("name", "preferred_username", "email")) {
                String value = jwt.getClaimAsString(claim);
                if (value != null && !value.isBlank()) {
                    return value.length() > 255 ? value.substring(0, 255) : value;
                }
            }
        }
        return null;
    }
}
