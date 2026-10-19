package com.agenticai.core.workflow.comment;

import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canvas comment threads (phase 4). Access and behaviour rules live on
 * {@link WorkflowCommentService}: VIEWER reads and writes, authors edit and
 * delete their own, thread author or EDITOR resolves.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/comments")
public class WorkflowCommentController {

    private final WorkflowCommentService service;

    public WorkflowCommentController(WorkflowCommentService service) {
        this.service = service;
    }

    /** Every thread on the workflow, roots in creation order, each with its replies. */
    @GetMapping
    public ResponseEntity<List<WorkflowCommentService.CommentView>> list(@PathVariable UUID workflowId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(workflowId), limit, offset, 100, 500);
    }

    @PostMapping
    public ResponseEntity<WorkflowCommentService.CommentView> create(
        @PathVariable UUID workflowId, @RequestBody WorkflowCommentService.CreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(workflowId, request));
    }

    /** Edit body/mentions (author) and/or {@code resolved} true/false (thread author or editor). */
    @PatchMapping("/{commentId}")
    public WorkflowCommentService.CommentView update(
        @PathVariable UUID workflowId, @PathVariable UUID commentId,
        @RequestBody WorkflowCommentService.UpdateRequest request
    ) {
        return service.update(workflowId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workflowId, @PathVariable UUID commentId) {
        service.delete(workflowId, commentId);
        return ResponseEntity.noContent().build();
    }
}
