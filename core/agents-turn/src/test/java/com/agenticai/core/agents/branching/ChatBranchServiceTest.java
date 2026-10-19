package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchDtos.ActivePathResponse;
import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchDtos.SiblingsResponse;
import com.agenticai.core.agents.branching.ChatBranchTree.Node;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.agents.turn.cache.SemanticResponseCacheService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ChatBranchService} against an in-memory store and a Redis stand-in. {@code chat(..)} plays
 * the part of the ordinary chat pipeline: it appends a user turn and an assistant turn to the live
 * transcript, which is all the real one does to session history.
 */
class ChatBranchServiceTest extends ChatBranchServiceTestBase {

    // ---- failed turn ----------------------------------------------------------

    @Test
    void aFailedTurnIsShownAsAnAssistantMessageFlaggedFailedWithoutTheTechnicalMarker() {
        chat("Say ok.", null);
        live.add(ChatTurn.failedAssistant("The AI provider rejected our credentials."));

        ActivePathResponse path = service.activePath(agentId, SESSION);
        // Reading again (the next page load) must give the same answer, not re-record or drop the
        // failure.
        ActivePathResponse again = service.activePath(agentId, SESSION);

        assertEquals(List.of("Say ok.", "The AI provider rejected our credentials."), texts(path));
        assertEquals("ok", path.messages().get(0).status());
        assertFalse(path.messages().get(0).error());
        MessageView failed = path.messages().get(1);
        assertEquals("assistant", failed.role());
        assertEquals("failed", failed.status());
        assertTrue(failed.error());
        assertEquals(texts(path), texts(again));
        assertEquals(2, again.messages().size());
        // The transcript itself keeps the marker (that is how the model-history filter finds it).
        assertTrue(ChatTurn.isFailedReply(live.get(1)));
    }

    @Test
    void anInterruptedReplyIsFlaggedInterruptedWithItsPartialText() {
        chat("q", "The answer is" + ChatTurn.INTERRUPTED_REPLY_SUFFIX);

        MessageView m = service.activePath(agentId, SESSION).messages().get(1);

        assertEquals("interrupted", m.status());
        assertEquals("The answer is", m.content());
        assertFalse(m.error());
    }

    // ---- flag off -------------------------------------------------------------

    @Test
    void withTheFlagOffEveryRouteIsA404AndNothingIsReadOrWritten() {
        ChatBranchService off = newService(false, 1000);
        UUID message = UUID.randomUUID();

        assertNotFound(() -> off.activePath(agentId, SESSION));
        assertNotFound(() -> off.siblings(agentId, SESSION, message));
        assertNotFound(() -> off.switchActiveLeaf(agentId, SESSION, message));
        assertNotFound(() -> off.prepareEdit(agentId, SESSION, message, "x"));
        assertNotFound(() -> off.prepareRegenerate(agentId, SESSION, message));
        assertNotFound(off::requireEnabled);

        verifyNoInteractions(sessionMemory);
        verifyNoInteractions(responseCache);
        assertTrue(store.states.isEmpty());
        assertTrue(store.nodes.isEmpty());
    }

    @Test
    void capabilitiesReportTheFlagWithoutFailingWhenItIsOff() {
        assertTrue(service.capabilities(agentId).enabled());
        assertFalse(newService(false, 1000).capabilities(agentId).enabled());
        verify(agentService, org.mockito.Mockito.times(2))
                .requireAccess(agentId, PermissionLevel.RUNNER);
    }

    // ---- linear compatibility -------------------------------------------------

    @Test
    void anExistingLinearSessionIsSeededAsAChainWithOneVersionPerMessageAndRedisUntouched() {
        chat("q1", "r1");
        chat("q2", "r2");

        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1", "q2", "r2"), texts(path));
        assertNull(path.messages().get(0).parentId());
        assertEquals(path.messages().get(0).id(), path.messages().get(1).parentId());
        for (MessageView m : path.messages()) {
            assertEquals(1, m.siblingCount());
            assertEquals(0, m.siblingIndex());
        }
        assertEquals(path.messages().get(3).id(), path.activeLeafId());
        // The live transcript is exactly as chat left it: reading never rewrites history.
        verify(sessionMemory, never()).replaceHistory(anyString(), anyList());
        assertEquals(List.of("q1", "r1", "q2", "r2"), liveTexts(live));
    }

    @Test
    void readingTwiceDoesNotRecordTheSameTurnsAgain() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);
        int after = store.nodes.size();
        service.activePath(agentId, SESSION);
        assertEquals(after, store.nodes.size());
        assertEquals(2, after);
    }

    @Test
    void turnsAppendedByNormalChatBetweenCallsAreAbsorbedUnderTheLeaf() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);
        chat("q2", "r2");

        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1", "q2", "r2"), texts(path));
        assertEquals(4, store.nodes.size());
        for (MessageView m : path.messages()) {
            assertEquals(1, m.siblingCount());
        }
    }

    @Test
    void anEmptyOrExpiredTranscriptWritesNothingForAnUntouchedSession() {
        ActivePathResponse path = service.activePath(agentId, SESSION);
        assertTrue(path.messages().isEmpty());
        assertNull(path.activeLeafId());
        assertTrue(store.states.isEmpty());
    }

    @Test
    void whenRedisExpiresTheTreeStillAnswersFromTheDatabase() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);
        live.clear();

        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1"), texts(path));
        assertEquals(2, store.nodes.size());
    }

    @Test
    void aRewoundSessionMovesTheLeafBackWithIt() {
        chat("q1", "r1");
        chat("q2", "r2");
        service.activePath(agentId, SESSION);
        // POST .../chat/sessions/{id}/rewind keepTurns=2
        live.subList(2, 4).clear();

        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1"), texts(path));
        assertEquals(4, store.nodes.size());
    }

    // ---- edit -----------------------------------------------------------------

    @Test
    void editingCreatesASiblingAndKeepsTheOriginalBranch() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse before = service.activePath(agentId, SESSION);
        UUID q2 = before.messages().get(2).id();
        UUID a2 = before.messages().get(3).id();

        ChatBranchService.PreparedTurn prepared =
                service.prepareEdit(agentId, SESSION, q2, "q2 edited");

        assertEquals("q2 edited", prepared.text());
        assertEquals(a2, prepared.previousLeafId());
        // The live transcript now stops before the edited message: the model will see only q1, r1.
        assertEquals(List.of("q1", "r1"), liveTexts(live));
        // ... and then the ordinary chat call appends the new turn.
        chat("q2 edited", "r2 edited");

        ActivePathResponse after = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1", "q2 edited", "r2 edited"), texts(after));
        assertEquals(6, store.nodes.size());
        assertTrue(store.nodes.containsKey(q2), "the original user message is not overwritten");
        assertTrue(store.nodes.containsKey(a2), "the original reply is preserved");
        MessageView edited = after.messages().get(2);
        assertEquals(before.messages().get(1).id(), edited.parentId());
        assertEquals(2, edited.siblingCount());
        assertEquals(1, edited.siblingIndex());
        SiblingsResponse siblings = service.siblings(agentId, SESSION, edited.id());
        assertEquals(List.of(q2, edited.id()), siblings.ids());
        assertEquals(1, siblings.index());
        assertEquals(2, siblings.count());
        assertEquals("q2", store.nodes.get(q2).content());
    }

    @Test
    void editingTheFirstMessageCreatesASecondRoot() {
        chat("q1", "r1");
        UUID q1 = service.activePath(agentId, SESSION).messages().get(0).id();

        service.prepareEdit(agentId, SESSION, q1, "q1 edited");
        assertTrue(live.isEmpty());
        chat("q1 edited", "r1 edited");
        ActivePathResponse after = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1 edited", "r1 edited"), texts(after));
        assertNull(after.messages().get(0).parentId());
        assertEquals(2, after.messages().get(0).siblingCount());
        assertEquals(4, store.nodes.size());
    }

    @Test
    void onlyAUserMessageCanBeEditedAndTheMessageMustExist() {
        chat("q1", "r1");
        ActivePathResponse path = service.activePath(agentId, SESSION);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepareEdit(agentId, SESSION, path.messages().get(1).id(), "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepareEdit(agentId, SESSION, path.messages().get(0).id(), "   "));
        assertNotFound(() -> service.prepareEdit(agentId, SESSION, UUID.randomUUID(), "x"));
        // Nothing was changed by the refused calls.
        assertEquals(List.of("q1", "r1"), liveTexts(live));
    }

    // ---- regenerate -----------------------------------------------------------

    @Test
    void regeneratingAddsAnAssistantSiblingUnderTheSameUserMessage() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse before = service.activePath(agentId, SESSION);
        UUID q2 = before.messages().get(2).id();
        UUID a2 = before.messages().get(3).id();

        ChatBranchService.PreparedTurn prepared = service.prepareRegenerate(agentId, SESSION, a2);

        assertEquals("q2", prepared.text());
        assertEquals(List.of("q1", "r1"), liveTexts(live));
        // The cached answer for that text must not be served straight back.
        verify(responseCache).evict(TENANT, agentId, "alice", "q2");
        verify(semanticCache).evict(agentId, "q2");
        chat("q2", "r2 again");

        ActivePathResponse after = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1", "q2", "r2 again"), texts(after));
        // The user node is reused, not duplicated: 4 original + 1 new reply.
        assertEquals(5, store.nodes.size());
        assertEquals(q2, after.messages().get(2).id());
        assertEquals(1, after.messages().get(2).siblingCount());
        MessageView regenerated = after.messages().get(3);
        assertEquals(q2, regenerated.parentId());
        assertEquals(2, regenerated.siblingCount());
        assertEquals(1, regenerated.siblingIndex());
        assertEquals(
                List.of(a2, regenerated.id()),
                service.siblings(agentId, SESSION, regenerated.id()).ids());
    }

    @Test
    void regeneratingFromTheUserMessageIdWorksToo() {
        chat("q1", "r1");
        UUID q1 = service.activePath(agentId, SESSION).messages().get(0).id();

        ChatBranchService.PreparedTurn prepared = service.prepareRegenerate(agentId, SESSION, q1);

        assertEquals("q1", prepared.text());
        assertTrue(live.isEmpty());
        chat("q1", "r1 again");
        ActivePathResponse after = service.activePath(agentId, SESSION);
        assertEquals(3, store.nodes.size());
        assertEquals(q1, after.messages().get(0).id());
        assertEquals(2, after.messages().get(1).siblingCount());
    }

    @Test
    void regeneratingRequiresAUserMessageBehindTheTarget() {
        assertNotFound(() -> service.prepareRegenerate(agentId, SESSION, UUID.randomUUID()));
    }

    @Test
    void theRegenerateHintIsOneShotSoARepeatedQuestionLaterIsANewNode() {
        chat("q1", "r1");
        UUID a1 = service.activePath(agentId, SESSION).messages().get(1).id();
        service.prepareRegenerate(agentId, SESSION, a1);
        chat("q1", "r1 again");
        service.activePath(agentId, SESSION);

        // The same question asked again at the end of the conversation is just a new message.
        chat("q1", "r1 third");
        ActivePathResponse after = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1 again", "q1", "r1 third"), texts(after));
        assertEquals(1, after.messages().get(2).siblingCount());
    }

    // ---- switching ------------------------------------------------------------

    @Test
    void switchingToASiblingReplaysThatBranchAndFlipsBack() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse original = service.activePath(agentId, SESSION);
        UUID q2 = original.messages().get(2).id();
        service.prepareEdit(agentId, SESSION, q2, "q2 edited");
        chat("q2 edited", "r2 edited");
        ActivePathResponse edited = service.activePath(agentId, SESSION);
        UUID q2Edited = edited.messages().get(2).id();

        ActivePathResponse backToOriginal = service.switchActiveLeaf(agentId, SESSION, q2);

        assertEquals(List.of("q1", "r1", "q2", "r2"), texts(backToOriginal));
        assertEquals(
                List.of("q1", "r1", "q2", "r2"),
                liveTexts(live),
                "history assembly follows the active path only");
        assertEquals(original.activeLeafId(), backToOriginal.activeLeafId());
        assertEquals(0, backToOriginal.messages().get(2).siblingIndex());
        assertEquals(2, backToOriginal.messages().get(2).siblingCount());

        ActivePathResponse forward = service.switchActiveLeaf(agentId, SESSION, q2Edited);

        assertEquals(List.of("q1", "r1", "q2 edited", "r2 edited"), texts(forward));
        assertEquals(List.of("q1", "r1", "q2 edited", "r2 edited"), liveTexts(live));
        // Nothing was lost by flipping.
        assertEquals(6, store.nodes.size());
    }

    @Test
    void aTurnAfterSwitchingBackContinuesTheSwitchedToBranch() {
        chat("q1", "r1");
        ActivePathResponse base = service.activePath(agentId, SESSION);
        UUID q1 = base.messages().get(0).id();
        service.prepareEdit(agentId, SESSION, q1, "q1 edited");
        chat("q1 edited", "r1 edited");
        service.activePath(agentId, SESSION);
        service.switchActiveLeaf(agentId, SESSION, q1);

        chat("q2", "r2");
        ActivePathResponse after = service.activePath(agentId, SESSION);

        assertEquals(List.of("q1", "r1", "q2", "r2"), texts(after));
        assertEquals(6, store.nodes.size());
    }

    @Test
    void switchingToAnUnknownMessageIs404() {
        chat("q1", "r1");
        service.activePath(agentId, SESSION);
        assertNotFound(() -> service.switchActiveLeaf(agentId, SESSION, UUID.randomUUID()));
        assertNotFound(() -> service.siblings(agentId, SESSION, UUID.randomUUID()));
    }

    @Test
    void restoreLeafPutsBackThePathAFailedTurnWasAboutToReplace() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse before = service.activePath(agentId, SESSION);
        ChatBranchService.PreparedTurn prepared =
                service.prepareEdit(agentId, SESSION, before.messages().get(2).id(), "q2 edited");
        assertEquals(List.of("q1", "r1"), liveTexts(live));

        service.restoreLeaf(agentId, SESSION, prepared.previousLeafId());

        assertEquals(List.of("q1", "r1", "q2", "r2"), liveTexts(live));
        assertEquals(before.activeLeafId(), service.activePath(agentId, SESSION).activeLeafId());
        assertEquals(4, store.nodes.size());
    }

    @Test
    void aTurnRefusedWithoutAppendingAnythingPutsTheEditedPathBack() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse before = service.activePath(agentId, SESSION);
        ChatBranchService.PreparedTurn prepared =
                service.prepareEdit(agentId, SESSION, before.messages().get(2).id(), "q2 edited");
        assertEquals(List.of("q1", "r1"), liveTexts(live));
        // The chat pipeline refused the turn (moderation / guardrail / quota): nothing was
        // appended.

        service.restoreIfNothingAppended(agentId, SESSION, prepared.previousLeafId());

        assertEquals(List.of("q1", "r1", "q2", "r2"), liveTexts(live));
        ActivePathResponse after = service.activePath(agentId, SESSION);
        assertEquals(List.of("q1", "r1", "q2", "r2"), texts(after));
        assertEquals(before.activeLeafId(), after.activeLeafId());
        assertEquals(1, after.messages().get(2).siblingCount());
        assertEquals(4, store.nodes.size());
    }

    @Test
    void aRefusedRegenerateOfTheFirstMessageAlsoPutsTheWholePathBack() {
        chat("q1", "r1");
        UUID q1 = service.activePath(agentId, SESSION).messages().get(0).id();
        ChatBranchService.PreparedTurn prepared = service.prepareRegenerate(agentId, SESSION, q1);
        assertTrue(live.isEmpty());

        service.restoreIfNothingAppended(agentId, SESSION, prepared.previousLeafId());

        assertEquals(List.of("q1", "r1"), liveTexts(live));
        assertEquals(List.of("q1", "r1"), texts(service.activePath(agentId, SESSION)));
    }

    @Test
    void aTurnThatDidAppendItsUserMessageIsNotUndone() {
        chat("q1", "r1");
        chat("q2", "r2");
        ActivePathResponse before = service.activePath(agentId, SESSION);
        ChatBranchService.PreparedTurn prepared =
                service.prepareEdit(agentId, SESSION, before.messages().get(2).id(), "q2 edited");
        // Failed mid-way: the user turn landed, the reply did not.
        chat("q2 edited", null);

        service.restoreIfNothingAppended(agentId, SESSION, prepared.previousLeafId());

        assertEquals(List.of("q1", "r1", "q2 edited"), liveTexts(live));
        ActivePathResponse after = service.activePath(agentId, SESSION);
        assertEquals(List.of("q1", "r1", "q2 edited"), texts(after));
        assertEquals(2, after.messages().get(2).siblingCount());
    }

    @Test
    void restoreIfNothingAppendedIsInertWithTheFlagOffAndNeverThrows() {
        chat("q1", "r1");
        ChatBranchService off = newService(false, 1000);
        off.restoreIfNothingAppended(agentId, SESSION, UUID.randomUUID());
        verifyNoInteractions(sessionMemory);

        // No tree at all, and a leaf id nobody knows: still no exception.
        service.restoreIfNothingAppended(agentId, "never-seen", UUID.randomUUID());
        assertTrue(store.states.isEmpty());
    }
}
