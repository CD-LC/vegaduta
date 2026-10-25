package com.agenticai.core.agents.branching;

import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadView;
import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadsResponse;
import com.agenticai.core.agents.memory.CallerSessionIds;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's conversation list over the branching tables ({@code /api/chat/threads}; the server
 * side of the console's thread rail, design gap G1 in PERSONAL-ASSISTANT-SURFACE-2026-10-17). Same
 * flag as the rest of the package (app.chat.branching.enabled, default off: the routes are 404
 * then, see {@link ChatBranchFlagGate}), because the rows only exist while the flag is on.
 *
 * <p><b>Authorisation.</b> A thread is the caller's own data: a session is listed, and may be
 * deleted, only when its tree is bound to the caller's subject ({@code owner_subject}, the same
 * binding {@link ChatBranchService} makes when a subject records turns into or changes a tree).
 * Nothing else is checked: a revoked RUNNER grant on the agent does not hide what the person
 * already said to it, and a delete of someone else's or an unknown session answers 404, like every
 * other cross-user miss in the package.
 *
 * <p>Paging is keyset on {@code updated_at}, strictly descending: {@code nextBefore} is the last
 * row's {@code updatedAt}. Two sessions touched in the same millisecond at a page boundary can be
 * skipped; the list is a convenience view of what Redis holds for 24h and the tree keeps for the
 * retention window.
 */
@Service
public class ChatThreadService {

    /** Server-side cut of the first user message that becomes a thread title. */
    public static final int TITLE_MAX_CHARS = 200;

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ChatBranchStore store;
    private final SessionMemoryService sessionMemory;
    private final boolean enabled;

    public ChatThreadService(
            ChatBranchStore store,
            SessionMemoryService sessionMemory,
            @Value("${app.chat.branching.enabled:false}") boolean enabled) {
        this.store = store;
        this.sessionMemory = sessionMemory;
        this.enabled = enabled;
    }

    /** GET /api/chat/threads - the caller's sessions, newest activity first. */
    public ThreadsResponse list(Integer limit, Instant before) {
        requireEnabled();
        int size = clampLimit(limit);
        String subject = currentSubject();
        if (subject == null) {
            // No bound subject (a tenant API key, say): no session can be the caller's.
            return new ThreadsResponse(List.of(), null);
        }
        List<ChatBranchStore.Thread> rows = store.findThreadsOwnedBy(subject, before, size + 1);
        boolean more = rows.size() > size;
        List<ChatBranchStore.Thread> page = more ? rows.subList(0, size) : rows;
        List<ThreadView> views = new ArrayList<>(page.size());
        for (ChatBranchStore.Thread t : page) {
            views.add(
                    new ThreadView(
                            t.sessionId(),
                            t.agentId(),
                            titleOf(t.firstUserMessage()),
                            t.createdAt(),
                            t.updatedAt()));
        }
        Instant next = more ? page.get(page.size() - 1).updatedAt() : null;
        return new ThreadsResponse(views, next);
    }

    /**
     * DELETE /api/chat/threads/{agentId}/{sessionId} - 404 unless the session is the caller's own.
     *
     * <p>The tree AND the caller's live Redis history go: the tree is rebuilt lazily from the live
     * list on the next branching read ({@link ChatBranchService#activePath}), so a tree-only delete
     * would bring the thread back while its 24h history is still alive. The live list is addressed
     * by the caller-scoped id (SEC-004: tenant, agent, subject, client session id), so only the
     * caller's own list can ever be touched; {@link SessionMemoryService#rewind} to zero drops the
     * list, its owner record, the rolling summary and the fold marker together.
     */
    public void delete(UUID agentId, String sessionId) {
        requireEnabled();
        if (!StringUtils.hasText(sessionId)) {
            throw notFound();
        }
        String subject = currentSubject();
        if (subject == null || !store.deleteSessionOwnedBy(agentId, sessionId, subject)) {
            throw notFound();
        }
        CallerSessionIds.scopedForCurrentCaller(agentId, sessionId)
                .ifPresent(liveId -> sessionMemory.rewind(liveId, 0));
    }

    /** Flag off: the routes behave as if they did not exist (404). */
    void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    /**
     * The block the console puts ahead of what the person typed ({@code <app_context>...</app_context>}:
     * what is connected, the attached folder - written by the app, for the model). Not the person's
     * words, so never their thread's title.
     */
    static final String APP_CONTEXT_OPEN = "<app_context>";

    static final String APP_CONTEXT_CLOSE = "</app_context>";

    /**
     * The first user line as stored, marker-free, without the console's leading app-context block,
     * whitespace-trimmed and cut to {@link #TITLE_MAX_CHARS}; null for none. Derived at read time, so
     * threads stored before the block was stripped get a proper title too.
     */
    static String titleOf(String firstUserMessage) {
        if (firstUserMessage == null) {
            return null;
        }
        String text =
                withoutAppContext(TranscriptView.textOf(MessageRole.USER, firstUserMessage)).strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= TITLE_MAX_CHARS ? text : text.substring(0, TITLE_MAX_CHARS);
    }

    /**
     * {@code text} without a leading app-context block. A block that is opened and never closed is all
     * app text, so nothing of it is kept.
     */
    public static String withoutAppContext(String text) {
        String trimmed = text == null ? "" : text.stripLeading();
        if (!trimmed.startsWith(APP_CONTEXT_OPEN)) {
            return text == null ? "" : text;
        }
        int close = trimmed.indexOf(APP_CONTEXT_CLOSE);
        return close < 0 ? "" : trimmed.substring(close + APP_CONTEXT_CLOSE.length());
    }

    /**
     * The subject trees are bound to ({@link ChatBranchService} binds them the same way); null when
     * none.
     */
    private static String currentSubject() {
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return StringUtils.hasText(subject) ? subject : null;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
    }
}
