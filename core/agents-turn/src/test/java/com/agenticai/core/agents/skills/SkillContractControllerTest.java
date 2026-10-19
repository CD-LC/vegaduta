package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.core.AgentService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The REST contract the console builds against: list with a status filter, detail by id or name,
 * import of a zip or a bare SKILL.md, export, and the review actions - through real Spring MVC.
 */
class SkillContractControllerTest {

    private static final String SKILL_MD =
            "---\nname: sample-skill\ndescription: Does a sample thing. Use when testing.\n---\n\nSteps.\n";

    @RestControllerAdvice
    static class TestAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        void badRequest() {}
    }

    private SkillService skillService;
    private SkillLifecycleService lifecycle;
    private AgentService agentService;
    private MockMvc mvc;

    private final UUID pendingId = UUID.randomUUID();
    private final UUID activeId = UUID.randomUUID();
    private final Authentication admin =
            new TestingAuthenticationToken("alice", "x", "PERM_MANAGE_AGENTS");
    private final Authentication member = new TestingAuthenticationToken("bob", "x", "PERM_VIEW");

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        lifecycle = mock(SkillLifecycleService.class);
        agentService = mock(AgentService.class);
        SkillController skills =
                new SkillController(
                        skillService,
                        agentService,
                        mock(SkillRegistryClient.class),
                        mock(SkillDistillationService.class),
                        lifecycle);
        SkillLifecycleController review = new SkillLifecycleController(lifecycle, agentService);
        mvc =
                MockMvcBuilders.standaloneSetup(skills, review)
                        .setControllerAdvice(new TestAdvice())
                        .build();

        when(skillService.listAll())
                .thenReturn(
                        List.of(
                                new SkillService.SkillMetadata(
                                        null, "pdf", "Built in.", SkillSource.BUILT_IN, true, 2),
                                new SkillService.SkillMetadata(
                                        pendingId,
                                        "draft-skill",
                                        "A draft.",
                                        SkillSource.CUSTOM,
                                        false,
                                        0,
                                        "agent:learning:x",
                                        false),
                                new SkillService.SkillMetadata(
                                        activeId,
                                        "live-skill",
                                        "A live one.",
                                        SkillSource.CUSTOM,
                                        true,
                                        1,
                                        "alice",
                                        false)));
        when(lifecycle.views())
                .thenReturn(
                        Map.of(
                                pendingId,
                                view(pendingId, SkillStatus.PENDING, SkillOrigin.LEARNED, 1, 0),
                                activeId,
                                view(activeId, SkillStatus.ACTIVE, SkillOrigin.IMPORTED, 3, 7)));
    }

    private static SkillLifecycleService.SkillView view(
            UUID id, SkillStatus status, SkillOrigin origin, int version, long uses) {
        return new SkillLifecycleService.SkillView(
                id,
                status,
                origin,
                version,
                uses,
                uses == 0 ? null : 0.75,
                false,
                null,
                List.of(),
                Instant.parse("2026-10-17T00:00:00Z"),
                null,
                null,
                null,
                null);
    }

    // ---------------------------------------------------------------- list

    @Test
    void listsEverythingWithTheContractFields() throws Exception {
        mvc.perform(get("/api/skills"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(jsonPath("$[1].id").value(pendingId.toString()))
                .andExpect(jsonPath("$[1].name").value("draft-skill"))
                .andExpect(jsonPath("$[1].description").value("A draft."))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].origin").value("LEARNED"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].uses").value(0))
                .andExpect(jsonPath("$[1].agentIds").isArray())
                .andExpect(jsonPath("$[1].updatedAt").exists())
                .andExpect(jsonPath("$[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$[2].origin").value("IMPORTED"))
                .andExpect(jsonPath("$[2].uses").value(7))
                .andExpect(jsonPath("$[2].effectiveness").value(0.75));
    }

    @Test
    void theBuiltInsKeepWorkingAndReportAContractShapedStatus() throws Exception {
        mvc.perform(get("/api/skills"))
                .andExpect(jsonPath("$[0].name").value("pdf"))
                .andExpect(jsonPath("$[0].source").value("BUILT_IN"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void filtersByStatus() throws Exception {
        mvc.perform(get("/api/skills").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("draft-skill"));
        mvc.perform(get("/api/skills").param("status", "archived"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/skills").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void anUnknownStatusIsA400() throws Exception {
        mvc.perform(get("/api/skills").param("status", "BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void pagesLikeEveryOtherList() throws Exception {
        mvc.perform(get("/api/skills").param("limit", "1").param("offset", "1"))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("draft-skill"));
    }

    // ---------------------------------------------------------------- detail

    private SkillDefinition definition() {
        return new SkillDefinition(
                activeId,
                "live-skill",
                "A live one.",
                "Body text.",
                Map.of("scripts/run.py", "print('hi')"),
                SkillSource.CUSTOM);
    }

    @Test
    void detailByIdAddsBodyAndFiles() throws Exception {
        when(skillService.find(activeId.toString())).thenReturn(Optional.empty());
        when(lifecycle.nameOfId(activeId)).thenReturn(Optional.of("live-skill"));
        when(skillService.find("live-skill")).thenReturn(Optional.of(definition()));
        when(lifecycle.viewOf(activeId))
                .thenReturn(Optional.of(view(activeId, SkillStatus.ACTIVE, SkillOrigin.IMPORTED, 3, 7)));
        when(lifecycle.extrasFor("live-skill")).thenReturn(Map.of("license", "MIT"));

        mvc.perform(get("/api/skills/" + activeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId.toString()))
                .andExpect(jsonPath("$.body").value("Body text."))
                .andExpect(jsonPath("$.files[0].path").value("scripts/run.py"))
                .andExpect(jsonPath("$.files[0].size").value(11))
                .andExpect(jsonPath("$.files[0].sizeBytes").value(11))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.frontmatter.license").value("MIT"))
                .andExpect(jsonPath("$.skillMd").value(org.hamcrest.Matchers.containsString("license: \"MIT\"")));
    }

    @Test
    void detailByNameStillWorks() throws Exception {
        when(skillService.find("live-skill")).thenReturn(Optional.of(definition()));
        when(lifecycle.viewOf(activeId))
                .thenReturn(Optional.of(view(activeId, SkillStatus.ACTIVE, SkillOrigin.IMPORTED, 3, 7)));
        mvc.perform(get("/api/skills/live-skill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("live-skill"));
    }

    @Test
    void anUnknownNameOrIdIs404() throws Exception {
        when(skillService.find(anyString())).thenReturn(Optional.empty());
        when(lifecycle.nameOfId(any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/skills/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/not-there")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- import / export

    private SkillService.ImportResult importResult() {
        return new SkillService.ImportResult(definition(), List.of());
    }

    @Test
    void importsABareSkillMdThroughTheArchivePath() throws Exception {
        ArgumentCaptor<InputStream> upload = ArgumentCaptor.forClass(InputStream.class);
        when(skillService.importArchive(upload.capture(), anyBoolean(), anyString()))
                .thenReturn(importResult());

        mvc.perform(
                        multipart("/api/skills/import")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "SKILL.md",
                                                "text/markdown",
                                                SKILL_MD.getBytes(StandardCharsets.UTF_8)))
                                .principal(admin))
                .andExpect(status().isCreated());

        // What reached the service is an archive holding exactly that one SKILL.md.
        try (ZipInputStream zip = new ZipInputStream(upload.getValue())) {
            assertEquals("SKILL.md", zip.getNextEntry().getName());
            assertEquals(SKILL_MD, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(null, zip.getNextEntry());
        }
        verify(lifecycle).recordImport(eq("live-skill"), any(byte[].class));
    }

    @Test
    void aZipIsPassedThroughByteForByte() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
            zip.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        ArgumentCaptor<InputStream> upload = ArgumentCaptor.forClass(InputStream.class);
        when(skillService.importArchive(upload.capture(), anyBoolean(), anyString()))
                .thenReturn(importResult());

        mvc.perform(
                        multipart("/api/skills/import")
                                .file(new MockMultipartFile("file", "skill.zip", "application/zip", archive))
                                .principal(admin))
                .andExpect(status().isCreated());
        assertArrayEquals(archive, upload.getValue().readAllBytes());
    }

    @Test
    void importNeedsAWorkspaceAdmin() throws Exception {
        mvc.perform(
                        multipart("/api/skills/import")
                                .file(
                                        new MockMultipartFile(
                                                "file", "SKILL.md", "text/markdown", SKILL_MD.getBytes(StandardCharsets.UTF_8)))
                                .principal(member))
                .andExpect(status().isForbidden());
        verify(skillService, never()).importArchive(any(InputStream.class), anyBoolean(), any());
    }

    @Test
    void exportByIdIsAZipWithTheOptionalKeysPreserved() throws Exception {
        when(skillService.find(activeId.toString())).thenReturn(Optional.empty());
        when(lifecycle.nameOfId(activeId)).thenReturn(Optional.of("live-skill"));
        when(skillService.find("live-skill")).thenReturn(Optional.of(definition()));
        when(lifecycle.extrasFor("live-skill")).thenReturn(Map.of("license", "MIT"));

        byte[] zip =
                mvc.perform(get("/api/skills/" + activeId + "/export"))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                "attachment; filename=\"live-skill.zip\""))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        SkillArchiveReader.Archive back = SkillArchiveReader.read(new ByteArrayInputStream(zip));
        assertEquals("live-skill", back.skill().name());
        assertEquals("print('hi')", back.files().get("scripts/run.py"));
        assertEquals("MIT", back.extras().get("license"));
    }

    // ---------------------------------------------------------------- review actions

    private SkillLifecycleService.Reviewed reviewed(SkillStatus status) {
        UUID id = UUID.randomUUID();
        return new SkillLifecycleService.Reviewed(
                new SkillService.SkillMetadata(
                        id,
                        "draft-skill",
                        "A draft.",
                        SkillSource.CUSTOM,
                        status == SkillStatus.ACTIVE,
                        0,
                        "agent:x",
                        false),
                view(id, status, SkillOrigin.LEARNED, 1, 0));
    }

    @Test
    void approveRejectArchiveReturnTheSkillSummary() throws Exception {
        when(lifecycle.approve(pendingId)).thenReturn(reviewed(SkillStatus.ACTIVE));
        mvc.perform(post("/api/skills/" + pendingId + "/approve").principal(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("draft-skill"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.origin").value("LEARNED"));

        when(lifecycle.reject(eq(pendingId), eq("too specific")))
                .thenReturn(reviewed(SkillStatus.REJECTED));
        mvc.perform(
                        post("/api/skills/" + pendingId + "/reject")
                                .principal(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"too specific\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(lifecycle).reject(pendingId, "too specific");

        when(lifecycle.archive(activeId)).thenReturn(reviewed(SkillStatus.ARCHIVED));
        mvc.perform(post("/api/skills/" + activeId + "/archive").principal(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void rejectWithoutABodyIsFine() throws Exception {
        when(lifecycle.reject(pendingId, null)).thenReturn(reviewed(SkillStatus.REJECTED));
        mvc.perform(post("/api/skills/" + pendingId + "/reject").principal(admin))
                .andExpect(status().isOk());
    }

    @Test
    void reviewActionsNeedAWorkspaceAdmin() throws Exception {
        for (String action : List.of("approve", "reject", "archive", "agents")) {
            mvc.perform(
                            post("/api/skills/" + pendingId + "/" + action)
                                    .principal(member)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"agentIds\":[]}"))
                    .andExpect(status().isForbidden());
        }
        verify(lifecycle, never()).approve(any());
        verify(lifecycle, never()).reject(any(), any());
        verify(lifecycle, never()).archive(any());
        verify(lifecycle, never()).setAgents(any(), any());
    }

    @Test
    void bindsAgentsAfterCheckingEditAccessOnEach() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(lifecycle.setAgents(eq(pendingId), any()))
                .thenReturn(new SkillLifecycleService.AgentBinding(List.of(a), List.of(b)));
        mvc.perform(
                        post("/api/skills/" + pendingId + "/agents")
                                .principal(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agentIds\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentIds[0]").value(a.toString()))
                .andExpect(jsonPath("$.unrestrictedAgentIds[0]").value(b.toString()));
        verify(agentService)
                .requireAccess(a, com.agenticai.core.sharing.PermissionLevel.EDITOR);
        verify(agentService)
                .requireAccess(b, com.agenticai.core.sharing.PermissionLevel.EDITOR);
    }

    @Test
    void bindingMoreAgentsThanTheCapIsRefusedBeforeAnyLookup() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i <= SkillLifecycleController.MAX_AGENTS_PER_REQUEST; i++) {
            ids.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
        }
        mvc.perform(
                        post("/api/skills/" + pendingId + "/agents")
                                .principal(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agentIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
        verify(agentService, never()).requireAccess(any(), any());
        verify(lifecycle, never()).setAgents(any(), any());
    }

    @Test
    void versionsAreListedNewestFirst() throws Exception {
        SkillVersion v2 = new SkillVersion(activeId, 2, "d", "b", null, "h2", "alice", "edited");
        SkillVersion v1 = new SkillVersion(activeId, 1, "d", "b", null, "h1", "alice", "created");
        when(lifecycle.versions(activeId)).thenReturn(List.of(v2, v1));
        mvc.perform(get("/api/skills/" + activeId + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].note").value("edited"))
                .andExpect(jsonPath("$[1].version").value(1));
    }
}
