package com.agenticai.core.agents.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.ApproveRequest;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** The HTTP edge: the flag refuses an upload before a byte of it is read, the size cap is a 413, decisions use the principal. */
class CodingTaskControllerTest {

    private final CodingTaskService service = mock(CodingTaskService.class);
    private final Principal alice = () -> "alice";

    private CodingTaskSettings settings(long maxArchiveBytes) {
        return new CodingTaskSettings(true, false, 3, 20, 900, 150_000, 40, 1800, 400_000, 5, maxArchiveBytes, 4000, 900, 600,
            240, false, "e2b", "", 300, 8000);
    }

    @Test
    void aDisabledFeatureRefusesTheUploadBeforeReadingIt() throws IOException {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "off")).when(service).requireEnabled();
        MultipartFile upload = mock(MultipartFile.class);
        CodingTaskController controller = new CodingTaskController(service, settings(1 << 20));

        assertThatThrownBy(() -> controller.start(UUID.randomUUID().toString(), "x", "mvn test", null, upload,
            null, null, null, null, alice)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(upload, never()).getBytes();
        verify(upload, never()).getInputStream();
        verify(service, never()).start(any(), any());
    }

    @Test
    void anOversizedUploadIsA413AndIsNeverRead() throws IOException {
        MultipartFile upload = mock(MultipartFile.class);
        when(upload.isEmpty()).thenReturn(false);
        when(upload.getSize()).thenReturn(2048L);
        CodingTaskController controller = new CodingTaskController(service, settings(1024));

        assertThatThrownBy(() -> controller.start(UUID.randomUUID().toString(), "x", "mvn test", null, upload,
            null, null, null, null, alice)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        verify(upload, never()).getBytes();
        verify(service, never()).start(any(), any());
    }

    @Test
    void aBadAgentIdIsA400BeforeAnythingElse() {
        CodingTaskController controller = new CodingTaskController(service, settings(1 << 20));
        assertThatThrownBy(() -> controller.start("not-a-uuid", "x", "mvn test", null, null, null, null, null, null, alice))
            .isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).start(any(), any());
    }

    @Test
    void startPassesTheArchiveBytesAndThePrincipalAndAnswers202() {
        UUID agent = UUID.randomUUID();
        TaskView queued = mock(TaskView.class);
        when(service.start(any(), anyString())).thenReturn(queued);
        CodingTaskController controller = new CodingTaskController(service, settings(1 << 20));
        MockMultipartFile upload = new MockMultipartFile("archive", "repo.zip", "application/zip", new byte[] {'P', 'K', 3, 4});

        ResponseEntity<TaskView> response = controller.start(agent.toString(), "Add validation", "pytest -q", null, upload,
            5, 600, 20000, 2, alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<StartInput> in = ArgumentCaptor.forClass(StartInput.class);
        verify(service).start(in.capture(), eq("alice"));
        assertThat(in.getValue().agentId()).isEqualTo(agent);
        assertThat(in.getValue().archive()).containsExactly('P', 'K', 3, 4);
        assertThat(in.getValue().maxSteps()).isEqualTo(5);
        assertThat(in.getValue().maxWallSeconds()).isEqualTo(600);
        assertThat(in.getValue().maxFixIterations()).isEqualTo(2);
    }

    @Test
    void decisionsAreAlwaysMadeAsTheAuthenticatedPrincipal() {
        CodingTaskController controller = new CodingTaskController(service, settings(1 << 20));
        controller.approve("id", new ApproveRequest("hash"), alice);
        verify(service).approve("id", "alice", "hash");
        controller.approve("id2", null, alice);
        verify(service).approve("id2", "alice", null);
        controller.reject("id", alice);
        verify(service).reject("id", "alice");
        controller.cancel("id", alice);
        verify(service).cancel("id", "alice");
        controller.rollback("id", alice);
        verify(service).rollback("id", "alice");
        controller.get("id", alice);
        controller.diff("id", alice);
        org.mockito.Mockito.when(service.list("alice", 0, 20))
            .thenReturn(new CodingTaskListing.Page(java.util.List.of(), 0));
        controller.list(null, null, alice);
        verify(service).get("id", "alice");
        verify(service).diff("id", "alice");
        verify(service).list("alice", 0, 20);
    }
}
