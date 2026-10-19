package com.agenticai.core.quality.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.quality.QualityController;
import com.agenticai.core.quality.QualityEvaluationService;
import com.agenticai.core.quality.dataset.EvalDatasetController;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.quality.dataset.EvalRunService;
import com.agenticai.core.quality.trajectory.TrajectoryEvalController;
import com.agenticai.core.quality.trajectory.TrajectoryEvalService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceGrantRepository;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B7 regression: a tenant member with no access to an agent must not be able to read, create,
 * delete or run anything under {@code /api/agents/{id}/quality/**}. Runs the REAL controllers
 * behind the REAL gate and the REAL {@link ResourceAccessService}; only persistence is faked. On
 * the old code (plan gate only) every "denied" case below answered 200/204.
 */
class QualityAgentAccessGateTest {

    private static final String TENANT = "tenant-a";
    private static final String OWNER = "user-owner";
    private static final String MEMBER = "user-member";
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();

    private AgentOwnerLookup ownerLookup;
    private ResourceGrantRepository grants;
    private EvalDatasetService datasetService;
    private EvalRunService runService;
    private TrajectoryEvalService trajectoryService;
    private QualityEvaluationService qualityService;
    private MockMvc mvc;

    /** Maps the 404-shaped denial the way core/app's GlobalExceptionHandler does. */
    @RestControllerAdvice
    static class Advice {
        @ExceptionHandler(ResourceAccessDeniedException.class)
        ResponseEntity<String> denied(ResourceAccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        ownerLookup = mock(AgentOwnerLookup.class);
        when(ownerLookup.find(AGENT))
                .thenReturn(Optional.of(new AgentOwnerLookup.AgentOwner(OWNER)));
        grants = mock(ResourceGrantRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.admin.identity.IdentityUserAdminPort> kc =
                mock(ObjectProvider.class);
        ResourceAccessService access =
                new ResourceAccessService(grants, mock(AuditLogService.class), kc);
        QualityAgentAccessGate gate = new QualityAgentAccessGate(access, ownerLookup);

        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        datasetService = mock(EvalDatasetService.class);
        runService = mock(EvalRunService.class);
        trajectoryService = mock(TrajectoryEvalService.class);
        qualityService = mock(QualityEvaluationService.class);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new EvalDatasetController(datasetService, runService, limits, true),
                                new TrajectoryEvalController(trajectoryService, limits),
                                new QualityController(qualityService, limits, true))
                        .addMappedInterceptors(QualityAgentAccessGate.PATH_PATTERNS, gate)
                        .setControllerAdvice(new Advice())
                        .build();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void as(String subject, String... roles) {
        TenantContext.set(TenantContext.of(TENANT, subject, Set.of(roles)));
    }

    private String base() {
        return "/api/agents/" + AGENT + "/quality";
    }

    // ---- member with no access: 404 everywhere, nothing reaches the services ----

    @Test
    void memberWithoutAccessGets404OnEveryReadAndWrite() throws Exception {
        as(MEMBER, "ROLE_user");
        mvc.perform(get(base() + "/datasets")).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/datasets/" + DATASET)).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/datasets/" + DATASET + "/runs"))
                .andExpect(status().isNotFound());
        mvc.perform(get(base() + "/trajectory-eval/config")).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/summary")).andExpect(status().isNotFound());
        mvc.perform(
                        post(base() + "/datasets")
                                .contentType("application/json")
                                .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(base() + "/datasets/" + DATASET)).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/datasets/" + DATASET + "/runs"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(datasetService, runService, trajectoryService, qualityService);
    }

    @Test
    void viewerGrantCanReadButNotWrite() throws Exception {
        as(MEMBER, "ROLE_user");
        ResourceGrant viewer =
                new ResourceGrant(ResourceType.AGENT, AGENT, MEMBER, PermissionLevel.VIEWER, OWNER);
        when(grants.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, AGENT, MEMBER))
                .thenReturn(Optional.of(viewer));
        when(datasetService.listDatasets(AGENT)).thenReturn(List.of());

        mvc.perform(get(base() + "/datasets")).andExpect(status().isOk());
        mvc.perform(delete(base() + "/datasets/" + DATASET)).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/datasets/" + DATASET + "/runs"))
                .andExpect(status().isNotFound());
        verify(datasetService, never()).deleteDataset(any(), any());
        verify(runService, never()).triggerRun(any(), any(), any());
    }

    @Test
    void editorGrantCanDelete() throws Exception {
        as(MEMBER, "ROLE_user");
        ResourceGrant editor =
                new ResourceGrant(ResourceType.AGENT, AGENT, MEMBER, PermissionLevel.EDITOR, OWNER);
        when(grants.findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
                        ResourceType.AGENT, AGENT, MEMBER))
                .thenReturn(Optional.of(editor));

        mvc.perform(delete(base() + "/datasets/" + DATASET)).andExpect(status().isNoContent());
        verify(datasetService).deleteDataset(AGENT, DATASET);
    }

    // ---- owner and admin still work ----

    @Test
    void ownerStillWorks() throws Exception {
        as(OWNER, "ROLE_user");
        when(datasetService.listDatasets(AGENT)).thenReturn(List.of());
        mvc.perform(get(base() + "/datasets")).andExpect(status().isOk());
        mvc.perform(delete(base() + "/datasets/" + DATASET)).andExpect(status().isNoContent());
        verify(datasetService).deleteDataset(AGENT, DATASET);
    }

    @Test
    void adminOverrideStillWorks() throws Exception {
        as("user-admin", "PERM_MANAGE_AGENTS");
        when(datasetService.listDatasets(AGENT)).thenReturn(List.of());
        mvc.perform(get(base() + "/datasets")).andExpect(status().isOk());
        mvc.perform(delete(base() + "/datasets/" + DATASET)).andExpect(status().isNoContent());
    }

    // ---- tenant isolation / unknown agent ----

    @Test
    void agentOutsideCallersTenantSchemaIsNotFoundEvenForAdmin() throws Exception {
        UUID foreign = UUID.randomUUID();
        when(ownerLookup.find(foreign)).thenReturn(Optional.empty());
        as("user-admin", "PERM_MANAGE_AGENTS");
        mvc.perform(get("/api/agents/" + foreign + "/quality/datasets"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(datasetService);
    }

    @Test
    void legacyAgentWithoutOwnerIsAdminOnlyForReads() throws Exception {
        UUID legacy = UUID.randomUUID();
        when(ownerLookup.find(legacy))
                .thenReturn(Optional.of(new AgentOwnerLookup.AgentOwner(null)));
        as(MEMBER, "ROLE_user");
        mvc.perform(get("/api/agents/" + legacy + "/quality/datasets"))
                .andExpect(status().isNotFound());
        as("user-admin", "PERM_MANAGE_AGENTS");
        when(datasetService.listDatasets(legacy)).thenReturn(List.of());
        mvc.perform(get("/api/agents/" + legacy + "/quality/datasets")).andExpect(status().isOk());
    }

    @Test
    void noTenantContextIsDenied() throws Exception {
        TenantContext.clear();
        mvc.perform(get(base() + "/datasets")).andExpect(status().isNotFound());
    }

    // ---- coverage sweep: nothing under the prefix escapes the interceptor pattern ----

    @Test
    void everyQualityControllerTakingAnAgentIdIsUnderTheGatedPrefix() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                new AnnotationTypeFilter(
                        org.springframework.web.bind.annotation.RestController.class));
        int seen = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.agenticai.core.quality")) {
            Class<?> c;
            try {
                c = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            // A controller may declare its base at class level, or (to keep the class-level
            // base-path namespace unique - scripts/ci/entity-name-lint.sh) carry the full path
            // on each handler method; both shapes must stay under the gated prefix.
            RequestMapping rm = c.getAnnotation(RequestMapping.class);
            List<String> bases =
                    rm == null || rm.value().length == 0 ? List.of("") : List.of(rm.value());
            boolean agentScoped = false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                RequestMapping mm =
                        org.springframework.core.annotation.AnnotatedElementUtils
                                .findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) {
                    continue;
                }
                List<String> subs = mm.path().length == 0 ? List.of("") : List.of(mm.path());
                for (String base : bases) {
                    for (String sub : subs) {
                        String path = base + sub;
                        if (path.contains("{agentId}") || path.contains("agents/")) {
                            agentScoped = true;
                            assertTrue(
                                    path.startsWith("/api/agents/{agentId}/quality"),
                                    c.getSimpleName()
                                            + "#"
                                            + m.getName()
                                            + " maps "
                                            + path
                                            + " outside the gated /api/agents/{agentId}/quality"
                                            + " prefix");
                        }
                    }
                }
            }
            if (agentScoped) {
                seen++;
            }
        }
        assertTrue(seen >= 8, "expected the eight agent-scoped quality controllers, saw " + seen);
    }

    @Test
    void writesNeedEditorReadsNeedViewer() {
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        for (String m : new String[] {"GET", "HEAD"}) {
            req.setMethod(m);
            assertEquals(PermissionLevel.VIEWER, QualityAgentAccessGate.requiredLevel(req));
        }
        for (String m : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
            req.setMethod(m);
            assertEquals(PermissionLevel.EDITOR, QualityAgentAccessGate.requiredLevel(req));
        }
        assertFalse(QualityAgentAccessGate.PATH_PATTERNS.length == 0);
    }
}
