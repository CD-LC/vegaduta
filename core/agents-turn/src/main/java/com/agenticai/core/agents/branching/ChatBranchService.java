package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.Capabilities;
import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchDtos.SiblingsResponse;
import com.agenticai.core.agents.branching.ChatBranchTree.Alignment;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.branching.ChatBranchTree.SiblingInfo;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.CallerSessionIds;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatCachePolicy;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Edit / regenerate / branch-switch for chat sessions (app.chat.branching.enabled, default off).
 *
 * <p><b>Design.</b> Session history lives in Redis ({@link SessionMemoryService}) as a flat list.
 * This service keeps the conversation TREE beside it ({@link ChatBranchStore}) and maintains one
 * invariant: <i>the Redis list always equals the active path of the tree</i>. Prompt replay, the
 * rolling summary and the response caches therefore keep reading one linear list - they read the
 * active branch only without any change to them, and with the flag off none of this code runs, so
 * the linear path is byte-identical.
 *
 * <p>Normal chat turns are not intercepted. The tree learns about them lazily: every branching call
 * first {@linkplain #reconcile reconciles} the stored active path with the live list ({@link
 * ChatBranchTree#align}), recording any turns chat appended since as new nodes under the current
 * leaf. Edit and regenerate do not run a model themselves: they point the live list at the path
 * BEFORE the message, set the leaf there, and hand the text back to the controller, which runs the
 * ordinary chat pipeline (guardrails, quotas, tools, cost accounting, the lot). The turns that call
 * appends are then absorbed as new siblings by the next reconcile.
 *
 * <p><b>Authorisation</b> is the same bar as chatting: RUNNER on the agent. The live transcript is
 * always the CALLER's own ({@link CallerSessionIds}: SEC-004 keys session memory by tenant, agent,
 * subject and client session id), so another RUNNER who knows the client session id only ever sees
 * their own list. A tree is bound to the first subject that records turns into it from a live
 * session that subject owns ({@link SessionMemoryService#isOwnedBy}), or that CHANGES its active
 * branch; from then on any other caller is answered 404, like every other cross-user miss. A read
 * by someone with no live session of their own binds nothing, so it cannot lock the real user out,
 * and an unowned tree that already holds nodes is shown only to the live owner. The first CHANGE of
 * an unowned tree is only accepted from that owner, see {@link #openForChange}.
 *
 * <p><b>Retention:</b> the tree holds message text in the database beyond the 24h Redis history.
 * {@link ChatBranchRetentionPurge} deletes sessions idle for app.chat.branching.retention-days
 * (default 30), {@link ChatBranchAccountDeletionListener} deletes a member's trees on account
 * deletion, and whole-tenant erasure drops the schema with these tables.
 *
 * <p><b>Known limits:</b> attachments are not re-sent on edit / regenerate (only the text and its
 * placeholder note are); a branch switch or edit while a reply is still streaming into the same
 * session is not guarded server-side (the web client disables the controls while sending); tool
 * side effects of the original turn are not undone, and a regenerate runs the tools again.
 * Long-term and tenant memories (AgentMemoryService, TenantMemoryService) already extracted from a
 * turn on a branch the user then left stay in those stores and keep feeding recall blocks: only
 * session history, the rolling summary and the response caches follow the active branch. After the
 * 24h Redis expiry the tree still shows the old path, but the next ordinary chat turn starts from
 * an empty live list and is recorded as a new root, so the old conversation then shows as an
 * earlier version of the first message.
 */
@Service
public class ChatBranchService {

    private static final Logger log = LoggerFactory.getLogger(ChatBranchService.class);
    private static final int MAX_SESSION_ID_LENGTH = 128;

    private final AgentService agentService;
    private final SessionMemoryService sessionMemory;
    private final ChatBranchStore store;
    private final LlmResponseCacheService responseCache;
    private final ObjectProvider<SemanticResponseCacheService> semanticCache;
    private final boolean enabled;
    private final int maxNodesPerSession;

    /**
     * What the response caches key on ({@code app.chat.cache.*}): the same policy the chat turn reads and
     * writes them with, so a regenerate drops the entry that turn made. The shipped defaults until set.
     */
    private volatile ChatCachePolicy cachePolicy = new ChatCachePolicy(new ChatCacheProperties());

    private volatile ChatCacheProperties cacheProperties = new ChatCacheProperties();
    private volatile BehaviourSettings behaviour = BehaviourSettings.platformOnly();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useCacheProperties(ChatCacheProperties properties) {
        this.cacheProperties = properties == null ? new ChatCacheProperties() : properties;
        this.cachePolicy = new ChatCachePolicy(cacheProperties, behaviour);
    }

    /** The settings catalog, so a regenerate computes the key with the same {@code chat.cache.contextTurns} the turn used. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useSettings(ObjectProvider<SettingsResolver> resolver) {
        this.behaviour = BehaviourSettings.lazy(resolver::getIfAvailable);
        this.cachePolicy = new ChatCachePolicy(cacheProperties, behaviour);
    }

    public ChatBranchService(
            AgentService agentService,
            SessionMemoryService sessionMemory,
            ChatBranchStore store,
            LlmResponseCacheService responseCache,
            ObjectProvider<SemanticResponseCacheService> semanticCache,
            @Value("${app.chat.branching.enabled:false}") boolean enabled,
            @Value("${app.chat.branching.max-nodes-per-session:1000}") int maxNodesPerSession) {
        this.agentService = agentService;
        this.sessionMemory = sessionMemory;
        this.store = store;
        this.responseCache = responseCache;
        this.semanticCache = semanticCache;
        this.enabled = enabled;
        this.maxNodesPerSession = Math.max(10, maxNodesPerSession);
    }

    /**
     * What an edit / regenerate hands back: the text the controller must now send through normal
     * chat.
     */
    public record PreparedTurn(String sessionId, String text, UUID previousLeafId) {}

    /** One consistent view of a session's tree, after reconciling with the live transcript. */
    private record Snapshot(
            ChatBranchStore.State state, List<Node> nodes, Map<UUID, Node> byId, List<Node> path) {}

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Discovery for the web. 200 either way (a 404 per chat load would be console noise for every
     * flag-off tenant).
     */
    public Capabilities capabilities(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        return new Capabilities(enabled);
    }

    /** GET .../messages?branch=active - the path currently shown and replayed. */
    public ActivePathResponse activePath(UUID agentId, String sessionId) {
        return toResponse(sessionId, open(agentId, sessionId));
    }

    /** GET .../messages/{id}/siblings. */
    public SiblingsResponse siblings(UUID agentId, String sessionId, UUID messageId) {
        Snapshot s = open(agentId, sessionId);
        SiblingInfo info = ChatBranchTree.siblingInfo(s.nodes(), messageId);
        if (info == null) {
            throw messageNotFound();
        }
        return new SiblingsResponse(messageId, info.ids(), info.index(), info.count());
    }

    /**
     * PUT .../active-leaf - show the version {@code messageId}, landing on the newest leaf beneath
     * it.
     */
    public ActivePathResponse switchActiveLeaf(UUID agentId, String sessionId, UUID messageId) {
        Snapshot s = openForChange(agentId, sessionId);
        if (!s.byId().containsKey(messageId)) {
            throw messageNotFound();
        }
        UUID leaf = ChatBranchTree.leafUnder(s.nodes(), messageId);
        List<Node> path = ChatBranchTree.activePath(s.byId(), leaf);
        Snapshot moved = moveTo(s, agentId, sessionId, path, leaf, null);
        return toResponse(sessionId, moved);
    }

    /**
     * POST .../messages/{id}/edit - points the live transcript at the path before the edited
     * message. The caller then runs {@code newText} through normal chat; that turn lands as a new
     * sibling of the edited message and the original branch stays as it was.
     */
    public PreparedTurn prepareEdit(
            UUID agentId, String sessionId, UUID messageId, String newText) {
        Snapshot s = openForChange(agentId, sessionId);
        Node target = s.byId().get(messageId);
        if (target == null) {
            throw messageNotFound();
        }
        if (target.role() != MessageRole.USER) {
            throw new IllegalArgumentException("Only a user message can be edited.");
        }
        if (!StringUtils.hasText(newText)) {
            throw new IllegalArgumentException("message is required");
        }
        requireRoom(s, 2);
        UUID previousLeaf = s.state() == null ? null : s.state().activeLeafId();
        List<Node> before = ChatBranchTree.activePath(s.byId(), target.parentId());
        moveTo(s, agentId, sessionId, before, target.parentId(), null);
        return new PreparedTurn(sessionId, newText, previousLeaf);
    }

    /**
     * POST .../messages/{id}/regenerate - {@code messageId} is the assistant message to answer
     * again (or the user message it answers). Points the live transcript at the path before that
     * user message and marks the user node for reuse, so chat re-appending the same text maps onto
     * it and the new reply becomes a sibling of the old one. Cached replies for the text are
     * dropped first, otherwise the cache would hand the same answer straight back.
     */
    public PreparedTurn prepareRegenerate(UUID agentId, String sessionId, UUID messageId) {
        Snapshot s = openForChange(agentId, sessionId);
        Node target = s.byId().get(messageId);
        if (target == null) {
            throw messageNotFound();
        }
        Node user = target.role() == MessageRole.USER ? target : s.byId().get(target.parentId());
        if (user == null || user.role() != MessageRole.USER) {
            throw new IllegalArgumentException("There is no user message to answer again.");
        }
        requireRoom(s, 1);
        UUID previousLeaf = s.state() == null ? null : s.state().activeLeafId();
        List<Node> before = ChatBranchTree.activePath(s.byId(), user.parentId());
        moveTo(s, agentId, sessionId, before, user.parentId(), user.id());
        evictCachedReply(agentId, user.content(), toTurns(before));
        return new PreparedTurn(sessionId, user.content(), previousLeaf);
    }

    /**
     * Undo of a prepare call whose chat turn failed before it started: puts the live transcript and
     * the leaf back where they were. Best effort - the caller is already propagating the real
     * error.
     */
    public void restoreLeaf(UUID agentId, String sessionId, UUID previousLeafId) {
        try {
            Snapshot s = open(agentId, sessionId);
            List<Node> path = ChatBranchTree.activePath(s.byId(), previousLeafId);
            moveTo(s, agentId, sessionId, path, previousLeafId, null);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not restore the active branch of session {} after a failed turn: {}",
                    sessionId,
                    e.toString());
        }
    }

    /**
     * Called when the chat stream of an edit / regenerate has ended (completed, failed or was
     * cancelled). The ordinary chat pipeline refuses some turns WITHOUT appending anything and
     * without throwing (moderation and guardrail refusals return an ordinary Flux; quota or
     * upstream errors surface only after subscription), so the synchronous undo in {@link
     * #restoreLeaf} never sees them. If the live transcript is still exactly the path {@code
     * prepareEdit} / {@code prepareRegenerate} cut it back to - nothing was appended - the previous
     * path is put back; if the user turn did land (even a partial one) nothing is touched and the
     * next reconcile absorbs it as the new branch. Reads the stored state directly (never {@link
     * #open}), because a reconcile would be the thing that records whatever the turn did append.
     * Best effort, never throws.
     */
    public void restoreIfNothingAppended(UUID agentId, String sessionId, UUID previousLeafId) {
        try {
            if (!enabled) {
                return;
            }
            ChatBranchStore.State state = store.findState(agentId, sessionId).orElse(null);
            if (state == null) {
                return;
            }
            List<Node> nodes = store.findNodes(agentId, sessionId);
            Map<UUID, Node> byId = index(nodes);
            List<Node> cut = ChatBranchTree.activePath(byId, state.activeLeafId());
            String liveId = liveId(agentId, sessionId);
            if (liveId == null) {
                return;
            }
            ChatBranchTree.Kind kind =
                    ChatBranchTree.align(cut, sessionMemory.getFullHistory(liveId)).kind();
            if (kind != ChatBranchTree.Kind.SAME && kind != ChatBranchTree.Kind.NONE) {
                return;
            }
            List<Node> previous = ChatBranchTree.activePath(byId, previousLeafId);
            moveTo(
                    new Snapshot(state, nodes, byId, cut),
                    agentId,
                    sessionId,
                    previous,
                    previousLeafId,
                    null);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not restore the active branch of session {} after a turn that appended"
                            + " nothing: {}",
                    sessionId,
                    e.toString());
        }
    }

    // ---- internals -------------------------------------------------------

    /** Flag off: the routes behave as if they did not exist (404). */
    public void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private Snapshot open(UUID agentId, String sessionId) {
        requireEnabled();
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        if (!StringUtils.hasText(sessionId) || sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid session id.");
        }
        return reconcile(agentId, sessionId);
    }

    /**
     * {@link #open} for a call that CHANGES the active branch (switch, edit, regenerate). Such a
     * change binds an unowned tree to its caller and rewrites the live transcript, so on an unowned
     * tree the caller must be the person the live session belongs to ({@link
     * SessionMemoryService#isOwnedBy} on the caller-scoped id); otherwise another RUNNER who
     * learned the session id (session ids show up in the agent's tool-call log, for one) could
     * rewrite someone else's conversation, append turns to it and take the tree, locking the real
     * user out. 404, like every other cross-user miss. An owned tree was already checked against
     * the caller in {@link #reconcileOnce}.
     */
    private Snapshot openForChange(UUID agentId, String sessionId) {
        Snapshot s = open(agentId, sessionId);
        String owner = s.state() == null ? null : s.state().ownerSubject();
        boolean allowed =
                owner != null
                        ? owner.equals(currentSubject())
                        : callerOwnsLive(liveId(agentId, sessionId));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        return s;
    }

    private Snapshot reconcile(UUID agentId, String sessionId) {
        try {
            return reconcileOnce(agentId, sessionId);
        } catch (DataIntegrityViolationException e) {
            // Another request took the same sibling slot; it has recorded the same turns, so read
            // again.
            return reconcileOnce(agentId, sessionId);
        }
    }

    private Snapshot reconcileOnce(UUID agentId, String sessionId) {
        String subject = currentSubject();
        ChatBranchStore.State state = store.findState(agentId, sessionId).orElse(null);
        if (state != null
                && state.ownerSubject() != null
                && subject != null
                && !state.ownerSubject().equals(subject)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        List<Node> nodes = store.findNodes(agentId, sessionId);
        // SEC-004: the live transcript is the CALLER's own (caller-scoped id), so a stranger who
        // knows the client session id reads their own, empty list - never the owner's. Recording
        // turns from it therefore binds the tree to the caller who owns that list; a read by
        // someone without a live session of their own binds nothing, so it cannot lock the real
        // user out. An unowned tree that already holds nodes is shown only to the live owner.
        String liveId = liveId(agentId, sessionId);
        boolean ownsLive = callerOwnsLive(liveId);
        if (!ownsLive && (state == null || state.ownerSubject() == null) && !nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        String binder = ownsLive ? subject : null;
        Map<UUID, Node> byId = index(nodes);
        ChatBranchStore.State base =
                state != null
                        ? state
                        : new ChatBranchStore.State(agentId, sessionId, null, null, null);
        List<Node> path = ChatBranchTree.activePath(byId, base.activeLeafId());
        List<ChatTurn> live = liveId == null ? List.of() : sessionMemory.getFullHistory(liveId);
        Alignment alignment = ChatBranchTree.align(path, live);

        if (alignment.kind() == ChatBranchTree.Kind.REWIND) {
            // The session was rewound (POST .../chat/sessions/{id}/rewind): the leaf moves back
            // with it.
            UUID leaf = path.get(alignment.anchorIndex()).id();
            ChatBranchStore.State next = withLeaf(base, binder, leaf, null);
            store.save(next, List.of());
            return new Snapshot(next, nodes, byId, ChatBranchTree.activePath(byId, leaf));
        }
        if (alignment.kind() == ChatBranchTree.Kind.TAIL) {
            return absorb(base, binder, nodes, byId, path, live, alignment);
        }
        // NONE (live list empty or expired - the tree is the durable copy) or SAME: nothing to
        // record.
        return new Snapshot(state, nodes, byId, path);
    }

    /**
     * Records the turns the tree does not know yet as new nodes and moves the leaf to the last of
     * them.
     */
    private Snapshot absorb(
            ChatBranchStore.State base,
            String subject,
            List<Node> nodes,
            Map<UUID, Node> byId,
            List<Node> path,
            List<ChatTurn> live,
            Alignment alignment) {
        int tail = live.size() - alignment.tailFrom();
        if (nodes.size() + tail > maxNodesPerSession) {
            log.warn(
                    "Chat branching: session {} is at its node limit ({}); new turns are not"
                            + " recorded in the tree",
                    base.sessionId(),
                    maxNodesPerSession);
            return new Snapshot(base, nodes, byId, path);
        }
        UUID anchor = alignment.anchorIndex() < 0 ? null : path.get(alignment.anchorIndex()).id();
        UUID cursor = anchor;
        int from = alignment.tailFrom();

        // Regenerate: chat re-appended the user text the node already holds - map it onto that
        // node.
        UUID reuseId = base.reuseUserMessageId();
        if (reuseId != null) {
            Node reuse = byId.get(reuseId);
            if (reuse != null
                    && Objects.equals(reuse.parentId(), anchor)
                    && ChatBranchTree.matches(reuse, live.get(from))) {
                cursor = reuse.id();
                from++;
            }
        }

        List<Node> working = new ArrayList<>(nodes);
        List<Node> created = new ArrayList<>();
        for (int i = from; i < live.size(); i++) {
            ChatTurn turn = live.get(i);
            Node node =
                    new Node(
                            UUID.randomUUID(),
                            cursor,
                            turn.role(),
                            turn.content() == null ? "" : turn.content(),
                            nextSiblingIndex(working, cursor),
                            turn.timestamp() == null ? Instant.now() : turn.timestamp());
            created.add(node);
            working.add(node);
            cursor = node.id();
        }
        ChatBranchStore.State next = withLeaf(base, subject, cursor, null);
        store.save(next, created);
        Map<UUID, Node> merged = index(working);
        return new Snapshot(next, working, merged, ChatBranchTree.activePath(merged, cursor));
    }

    /**
     * Points the live transcript, then the stored leaf, at {@code path}. Redis first on purpose:
     * for edit and regenerate, if the database write then fails the live list is a prefix of the
     * stored path and the next reconcile reads it as a rewind and moves the leaf to match; the
     * other order would leave the live list longer than the leaf and re-record the old branch as a
     * copy. (A failed write while switching to a different branch can still record that branch once
     * more as a duplicate; it is harmless, never wrong.)
     */
    private Snapshot moveTo(
            Snapshot s,
            UUID agentId,
            String sessionId,
            List<Node> path,
            UUID leaf,
            UUID reuseUserMessageId) {
        String liveId = liveId(agentId, sessionId);
        if (liveId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
        }
        // The branch tree stores role and text only, so a turn's tool record (ChatTurn#tools) is carried
        // over from the live transcript for every turn that is unchanged - which is every turn before an
        // edit or a regenerate. Turns of another branch the live list never held have none.
        sessionMemory.replaceHistory(
                liveId, withToolRecords(toTurns(path), sessionMemory.getFullHistory(liveId)));
        ChatBranchStore.State base =
                s.state() != null
                        ? s.state()
                        : new ChatBranchStore.State(
                                agentId, sessionId, currentSubject(), null, null);
        ChatBranchStore.State next = withLeaf(base, currentSubject(), leaf, reuseUserMessageId);
        store.save(next, List.of());
        return new Snapshot(next, s.nodes(), s.byId(), path);
    }

    private void requireRoom(Snapshot s, int extra) {
        if (s.nodes().size() + extra > maxNodesPerSession) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This conversation has reached its branch limit. Start a new chat to keep"
                            + " going.");
        }
    }

    /**
     * Drops the cached reply of the message being regenerated. {@code before} is the conversation up to it,
     * so the key is the very one the chat turn read and wrote (the cache keys on the conversation too).
     */
    private void evictCachedReply(UUID agentId, String userText, List<ChatTurn> before) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String conversationKey = cachePolicy.conversationKey(before, null, agentId);
        // Same principal the chat turn keys the cache on (AgentChatService's subjectKey = the JWT
        // subject).
        responseCache.evict(tenantId, agentId, currentSubject(), userText, conversationKey);
        SemanticResponseCacheService semantic = semanticCache.getIfAvailable();
        if (semantic != null) {
            semantic.evict(agentId, userText);
        }
    }

    private ActivePathResponse toResponse(String sessionId, Snapshot s) {
        List<MessageView> views = new ArrayList<>(s.path().size());
        for (Node node : s.path()) {
            SiblingInfo info = ChatBranchTree.siblingInfo(s.nodes(), node.id());
            String status = TranscriptView.statusOf(node.role(), node.content());
            views.add(
                    new MessageView(
                            node.id(),
                            node.parentId(),
                            node.role().name().toLowerCase(Locale.ROOT),
                            TranscriptView.textOf(node.role(), node.content()),
                            node.createdAt(),
                            info == null ? 0 : info.index(),
                            info == null ? 1 : info.count(),
                            status,
                            TranscriptView.STATUS_FAILED.equals(status)));
        }
        UUID leaf = s.path().isEmpty() ? null : s.path().get(s.path().size() - 1).id();
        return new ActivePathResponse(sessionId, leaf, views);
    }

    private static ChatBranchStore.State withLeaf(
            ChatBranchStore.State base, String subject, UUID leaf, UUID reuse) {
        String owner = base.ownerSubject() != null ? base.ownerSubject() : subject;
        return new ChatBranchStore.State(base.agentId(), base.sessionId(), owner, leaf, reuse);
    }

    /** {@code turns}, each with the tool record of the same turn (same position, role and text) in {@code live}. */
    static List<ChatTurn> withToolRecords(List<ChatTurn> turns, List<ChatTurn> live) {
        if (live == null || live.isEmpty()) {
            return turns;
        }
        List<ChatTurn> out = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            ChatTurn turn = turns.get(i);
            ChatTurn before = i < live.size() ? live.get(i) : null;
            out.add(
                    before != null
                                    && before.tools() != null
                                    && before.role() == turn.role()
                                    && java.util.Objects.equals(before.content(), turn.content())
                            ? turn.withTools(before.tools())
                            : turn);
        }
        return out;
    }

    private static List<ChatTurn> toTurns(List<Node> path) {
        List<ChatTurn> turns = new ArrayList<>(path.size());
        for (Node node : path) {
            turns.add(new ChatTurn(node.role(), node.content(), node.createdAt()));
        }
        return turns;
    }

    private static int nextSiblingIndex(List<Node> all, UUID parentId) {
        int max = -1;
        for (Node n : all) {
            if (Objects.equals(n.parentId(), parentId) && n.siblingIndex() > max) {
                max = n.siblingIndex();
            }
        }
        return max + 1;
    }

    private static Map<UUID, Node> index(List<Node> nodes) {
        Map<UUID, Node> byId = new HashMap<>();
        for (Node n : nodes) {
            byId.put(n.id(), n);
        }
        return byId;
    }

    /**
     * SEC-004: the caller's own memory id for this client session id; null with no caller bound.
     */
    private static String liveId(UUID agentId, String sessionId) {
        return CallerSessionIds.scopedForCurrentCaller(agentId, sessionId).orElse(null);
    }

    private boolean callerOwnsLive(String liveId) {
        TenantContext ctx = TenantContext.current().orElse(null);
        return liveId != null
                && ctx != null
                && sessionMemory.isOwnedBy(liveId, ctx.getTenantId(), ctx.getSubject());
    }

    private static String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static ResponseStatusException messageNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found.");
    }
}
