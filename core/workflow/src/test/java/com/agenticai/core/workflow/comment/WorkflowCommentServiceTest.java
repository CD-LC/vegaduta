package com.agenticai.core.workflow.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkflowCommentServiceTest {

    private static final UUID WF = UUID.randomUUID();

    private final List<WorkflowComment> store = new ArrayList<>();
    private WorkflowCommentRepository repo;
    private WorkflowService workflows;
    private NotificationService notifications;
    private WorkflowCommentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(WorkflowCommentRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            WorkflowComment c = inv.getArgument(0);
            store.removeIf(s -> s.getId().equals(c.getId()));
            store.add(c);
            return c;
        });
        when(repo.findByWorkflowIdOrderByCreatedAtAsc(WF)).thenAnswer(inv -> List.copyOf(store));
        when(repo.findByIdAndWorkflowId(any(), eq(WF))).thenAnswer(inv ->
            store.stream().filter(c -> c.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findFirstByAuthorIdAndAuthorNameIsNotNullOrderByCreatedAtDesc(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(inv -> {
            store.remove((WorkflowComment) inv.getArgument(0));
            return null;
        }).when(repo).delete(any());
        when(repo.existsByParentIdAndDeletedAtIsNull(any())).thenAnswer(inv ->
            store.stream().anyMatch(c -> inv.getArgument(0).equals(c.getParentId()) && c.getDeletedAt() == null));

        workflows = mock(WorkflowService.class);
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(def.getId()).thenReturn(WF);
        when(workflows.requireViewerAccess(WF)).thenReturn(def);
        when(workflows.requireEditorAccess(WF)).thenThrow(new ResourceAccessDeniedException());
        when(workflows.graph(WF)).thenReturn(new WorkflowGraphDto(
            List.of(new WorkflowNodeDto("n1", "code", Map.of(), Map.of())), List.of()));

        notifications = mock(NotificationService.class);
        ObjectProvider<NotificationService> np = mock(ObjectProvider.class);
        when(np.getIfAvailable()).thenReturn(notifications);
        service = new WorkflowCommentService(repo, workflows, np, new ObjectMapper());
        as("alice");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void as(String user) {
        TenantContext.set(TenantContext.of("acme", user, Set.of()));
    }

    private WorkflowCommentService.CommentView post(String body, String nodeId, UUID parent, List<String> mentions) {
        return service.create(WF, new WorkflowCommentService.CreateRequest(body, nodeId, null, null, parent, mentions));
    }

    @Test
    void threadsNestRepliesAndAReplyToAReplyJoinsTheRoot() {
        WorkflowCommentService.CommentView root = post("Is this prompt right?", "n1", null, null);
        WorkflowCommentService.CommentView reply = post("Yes", null, root.id(), null);
        WorkflowCommentService.CommentView nested = post("Agreed", null, reply.id(), null);

        assertEquals(root.id(), nested.parentId());
        List<WorkflowCommentService.CommentView> threads = service.list(WF);
        assertEquals(1, threads.size());
        assertEquals(2, threads.get(0).replies().size());
        assertEquals("n1", threads.get(0).replies().get(0).nodeId(), "replies report the thread's anchor");
    }

    @Test
    void anchorIsRequiredForANewThread() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> post("hi", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        WorkflowCommentService.CommentView point = service.create(WF,
            new WorkflowCommentService.CreateRequest("here", null, 10.0, 20.0, null, null));
        assertEquals(10.0, point.anchor().x());
    }

    @Test
    void deletedNodeMakesTheThreadOrphanedNotGone() {
        post("about a node that will vanish", "gone-node", null, null);
        WorkflowCommentService.CommentView view = service.list(WF).get(0);
        assertTrue(view.orphaned());
        assertEquals("gone-node", view.nodeId());
    }

    @Test
    void onlyTheAuthorEditsAndDeletes() {
        WorkflowCommentService.CommentView mine = post("mine", "n1", null, null);
        as("bob");
        assertThrows(ResponseStatusException.class, () ->
            service.update(WF, mine.id(), new WorkflowCommentService.UpdateRequest("hijack", null, null)));
        assertThrows(ResponseStatusException.class, () -> service.delete(WF, mine.id()));
        as("alice");
        assertEquals("edited", service.update(WF, mine.id(),
            new WorkflowCommentService.UpdateRequest("edited", null, null)).body());
    }

    @Test
    void threadAuthorOrEditorResolvesOthersCannot() {
        WorkflowCommentService.CommentView root = post("q", "n1", null, null);
        as("bob");
        assertThrows(ResponseStatusException.class, () ->
            service.update(WF, root.id(), new WorkflowCommentService.UpdateRequest(null, null, true)));
        as("alice");
        assertTrue(service.update(WF, root.id(), new WorkflowCommentService.UpdateRequest(null, null, true)).resolved());
    }

    @Test
    void deletingARootWithRepliesBlanksItInsteadOfDroppingTheThread() {
        WorkflowCommentService.CommentView root = post("q", "n1", null, null);
        as("bob");
        post("a", null, root.id(), null);
        as("alice");
        service.delete(WF, root.id());
        WorkflowCommentService.CommentView view = service.list(WF).get(0);
        assertTrue(view.deleted());
        assertNull(view.body());
        assertEquals(1, view.replies().size());
        assertFalse(view.canEdit());
    }

    @Test
    void mentionsNotifyEachNewlyMentionedUserOnceAndNeverTheAuthor() {
        WorkflowCommentService.CommentView c = post("@bob @carol look", "n1", null, List.of("bob", "carol", "alice", "bob"));
        verify(notifications).create(eq("bob"), eq(NotificationCategory.WORKFLOW), anyString(), anyString(),
            eq(NotificationSeverity.INFO), eq("/workflows/" + WF + "?comment=" + c.id()), isNull());
        verify(notifications).create(eq("carol"), any(), anyString(), anyString(), any(), anyString(), isNull());
        verify(notifications, never()).create(eq("alice"), any(), anyString(), anyString(), any(), anyString(), any());
        assertEquals(3, c.mentions().size());

        service.update(WF, c.id(), new WorkflowCommentService.UpdateRequest("again", List.of("bob", "dave"), null));
        verify(notifications, times(1)).create(eq("bob"), any(), anyString(), anyString(), any(), anyString(), any());
        verify(notifications).create(eq("dave"), any(), anyString(), anyString(), any(), anyString(), isNull());
    }

    @Test
    void bodyLimits() {
        assertThrows(ResponseStatusException.class, () -> post("   ", "n1", null, null));
        assertThrows(ResponseStatusException.class, () -> post("x".repeat(4001), "n1", null, null));
    }
}
