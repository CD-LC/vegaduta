package com.agenticai.core.workflow.version;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.version.VersionLabel;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/workflows/{id}/versions carries each row's STAGING/PRODUCTION label (null when unlabelled). */
class WorkflowVersionControllerLabelsTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private static WorkflowVersion version(int number, VersionLabel label) throws Exception {
        WorkflowVersion v = WorkflowVersion.snapshot(
            WORKFLOW_ID, number, "{\"nodes\":[],\"edges\":[]}", "{}", "wf", "d", WorkflowStatus.DRAFT, null, null);
        Field id = WorkflowVersion.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(v, UUID.randomUUID());
        if (label != null) {
            v.assignLabel(label);
        }
        return v;
    }

    private MockMvc mvc(WorkflowVersionService versions) {
        // runRepository is only used by the run-diff endpoint, never by the version list/get.
        return MockMvcBuilders.standaloneSetup(
            new WorkflowVersionController(mock(WorkflowService.class), versions, null)).build();
    }

    @Test
    void listReturnsLabelPerRowAndNullWhenUnlabelled() throws Exception {
        WorkflowVersionService versions = mock(WorkflowVersionService.class);
        when(versions.list(WORKFLOW_ID)).thenReturn(
            List.of(version(3, VersionLabel.STAGING), version(2, VersionLabel.PRODUCTION), version(1, null)));

        mvc(versions).perform(get("/api/workflows/{id}/versions", WORKFLOW_ID).accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].versionNumber").value(3))
            .andExpect(jsonPath("$[0].label").value("STAGING"))
            .andExpect(jsonPath("$[1].label").value("PRODUCTION"))
            .andExpect(jsonPath("$[2].label").value(org.hamcrest.Matchers.nullValue()))
            // existing fields keep their names and values (additive change)
            .andExpect(jsonPath("$[0].workflowName").value("wf"))
            .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void singleVersionGetAlsoCarriesTheLabel() throws Exception {
        WorkflowVersionService versions = mock(WorkflowVersionService.class);
        when(versions.get(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(version(2, VersionLabel.PRODUCTION));

        mvc(versions).perform(get("/api/workflows/{id}/versions/2", WORKFLOW_ID).accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version.label").value("PRODUCTION"));
    }
}
