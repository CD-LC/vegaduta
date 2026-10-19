package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.security.TenantScope;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * This turn's attachment payloads, either already resolved or resolving on a virtual thread while chat()'s
 * pre-turn fan-out (history / tools / memory) runs (flag {@code app.chat.hotpath.async-attachment-fetch}).
 *
 * <p>Only a turn with a STORED attachment key and no inline attachments is overlapped: that is a pure object
 * storage GET. The key ownership check ({@code requireOwnAttachmentKey}) already ran synchronously before
 * this is started, inline attachments (validation, model lookup, and the vision-frame metering that must
 * follow it) keep resolving synchronously, and every other turn takes the eager path, which is the very same
 * {@link ChatAttachments#resolveAttachments} call at the very same place as before. The tenant context is
 * captured on the request thread and re-applied on the worker (storage is tenant-prefixed). {@link #join}
 * rethrows a failure as the original exception; chat() joins right before the user turn is appended to session
 * memory, so a storage failure still surfaces before session memory sees the turn and before the first token.
 */
final class PendingAttachments {

    private final List<ChatAttachmentPayload> resolved;
    private final CompletableFuture<List<ChatAttachmentPayload>> future;

    private PendingAttachments(List<ChatAttachmentPayload> resolved, CompletableFuture<List<ChatAttachmentPayload>> future) {
        this.resolved = resolved;
        this.future = future;
    }

    static PendingAttachments start(
        boolean async, ChatAttachments resolver, Executor executor, Agent agent, UUID agentId, String attachmentKey,
        String attachmentMimeType, List<ChatInlineAttachment> inlineAttachments
    ) {
        boolean noInline = inlineAttachments == null || inlineAttachments.isEmpty();
        if (!async || attachmentKey == null || !noInline) {
            return new PendingAttachments(
                resolver.resolveAttachments(agent, agentId, attachmentKey, attachmentMimeType, inlineAttachments), null);
        }
        return new PendingAttachments(null, CompletableFuture.supplyAsync(
            TenantScope.capture().wrapSupplier(() ->
                resolver.resolveAttachments(agent, agentId, attachmentKey, attachmentMimeType, inlineAttachments)),
            executor));
    }

    List<ChatAttachmentPayload> join() {
        if (future == null) {
            return resolved;
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
