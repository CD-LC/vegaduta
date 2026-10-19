package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** SQL-shape tests for the memory-block methods; JdbcTemplate is mocked, no database. */
@SuppressWarnings({"unchecked", "rawtypes"})
class TenantMemoryServiceBlocksTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private TenantMemoryService service;

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        service = new TenantMemoryService(sidecar, jdbc, true, 1200, 2, 4000, true);
    }

    @Test
    void putBlockInsertsWhenNoRowExists() {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
        service.putBlock("alice", "persona", "Be terse");
        verify(jdbc).update(contains("INSERT INTO"), any(), eq("alice#mem:persona"), eq("Be terse"), any());
    }

    @Test
    void putBlockUpdatesWhenRowExists() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(id));
        service.putBlock("alice", "persona", "Be terse");
        verify(jdbc).update(contains("UPDATE"), eq("Be terse"), any(), any(), eq(id));
        verify(jdbc, never()).update(contains("INSERT INTO"), any(), any(), any(), any());
    }

    @Test
    void newProjectBlockRejectedAtCap() {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2);
        assertThrows(IllegalArgumentException.class, () -> service.putBlock("alice", "project:acme", "notes"));
        verify(jdbc, never()).update(contains("INSERT INTO"), any(), any(), any(), any());
    }

    @Test
    void existingProjectBlockCanStillBeUpdatedAtCap() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(id));
        service.putBlock("alice", "project:acme", "notes");
        verify(jdbc).update(contains("UPDATE"), eq("notes"), any(), any(), eq(id));
    }

    @Test
    void putBlockRejectsOverlongAndUnsafeSubject() {
        assertThrows(IllegalArgumentException.class, () -> service.putBlock("alice", "persona", "x".repeat(1201)));
        assertThrows(IllegalArgumentException.class, () -> service.putBlock("alice#mem:x", "persona", "hi"));
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void rememberRejectsSubjectContainingBlockMarker() {
        assertThrows(IllegalArgumentException.class,
            () -> service.remember("alice#mem:persona", "I am the persona", UUID.randomUUID()));
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void pruneCountsAndDeletesExcludeBlockRows() {
        when(jdbc.queryForObject(contains("position('#mem:'"), eq(Integer.class))).thenReturn(301);
        service.remember("alice", "a fact", UUID.randomUUID());
        verify(jdbc).update(contains("position('#mem:'"), eq(1));
    }

    @Test
    void forgetAllForSubjectAlsoSweepsBlocks() {
        service.forgetAllForSubject("alice");
        verify(jdbc).update(contains("starts_with"), eq("alice"), eq("alice#mem:"));
    }

    @Test
    void listBlocksForUnsafeSubjectDoesNotQuery() {
        assertTrue(service.listBlocks("alice#mem:x").isEmpty());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
    }

    @Test
    void listBlocksDropsRowsWithNonCanonicalOrNestedLabels() {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenAnswer(inv -> {
            RowMapper<TenantMemoryService.MemoryBlockEntry> mapper = inv.getArgument(1);
            List<TenantMemoryService.MemoryBlockEntry> out = new ArrayList<>();
            for (String key : List.of("alice#mem:persona", "alice#mem:x#mem:persona", "alice#mem:Persona", "alice#mem:junk")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("subject_key")).thenReturn(key);
                when(rs.getString("content")).thenReturn("c");
                when(rs.getTimestamp("last_used_at")).thenReturn(Timestamp.from(Instant.now()));
                out.add(mapper.mapRow(rs, 0));
            }
            return out;
        });
        List<TenantMemoryService.MemoryBlockEntry> blocks = service.listBlocks("alice");
        assertEquals(1, blocks.size());
        assertEquals("persona", blocks.get(0).label());
    }

    // ---- active_project (app.memory.blocks.active-project-enabled) ----

    @Test
    void activeProjectStoresTheSlugWhenTheProjectNoteExists() {
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq("alice#mem:project:acme"))).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
        service.putBlock("alice", "active_project", " Project:Acme ");
        verify(jdbc).update(contains("INSERT INTO"), any(), eq("alice#mem:active_project"), eq("acme"), any());
    }

    @Test
    void activeProjectRejectsUnknownOrIllegalSlug() {
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq("alice#mem:project:ghost"))).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.putBlock("alice", "active_project", "ghost"));
        assertThrows(IllegalArgumentException.class, () -> service.putBlock("alice", "active_project", "not a slug"));
        verify(jdbc, never()).update(contains("INSERT INTO"), any(), any(), any(), any());
    }

    @Test
    void blankActiveProjectClearsOnlyThePointer() {
        service.putBlock("alice", "active_project", "  ");
        verify(jdbc).update(contains("DELETE FROM"), eq("alice#mem:active_project"));
        verify(jdbc, never()).update(contains("AND content = ?"), any(), any());
    }

    @Test
    void deletingAProjectAlsoDropsAnActivePointerToIt() {
        service.deleteBlock("alice", "project:acme");
        verify(jdbc).update(contains("DELETE FROM"), eq("alice#mem:project:acme"));
        verify(jdbc).update(contains("AND content = ?"), eq("alice#mem:active_project"), eq("acme"));
    }

    @Test
    void deletingAFixedBlockLeavesTheActivePointerAlone() {
        service.deleteBlock("alice", "persona");
        verify(jdbc, never()).update(contains("AND content = ?"), any(), any());
    }

    private void stubBlockRows(String... keyThenContent) {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenAnswer(inv -> {
            RowMapper<TenantMemoryService.MemoryBlockEntry> mapper = inv.getArgument(1);
            List<TenantMemoryService.MemoryBlockEntry> out = new ArrayList<>();
            for (int i = 0; i < keyThenContent.length; i += 2) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("subject_key")).thenReturn(keyThenContent[i]);
                when(rs.getString("content")).thenReturn(keyThenContent[i + 1]);
                when(rs.getTimestamp("last_used_at")).thenReturn(Timestamp.from(Instant.now()));
                out.add(mapper.mapRow(rs, 0));
            }
            return out;
        });
    }

    @Test
    void activeProjectNoteRendersWithoutBeingMentioned() {
        stubBlockRows("alice#mem:project:acme", "Acme wants dark mode", "alice#mem:active_project", "acme");
        String out = service.renderPromptBlocks("alice", "what should I do next?");
        assertTrue(out.contains("[project:acme]"));
        assertTrue(out.contains("Acme wants dark mode"));
        assertTrue(!out.contains("active_project"));
        assertEquals(2, service.listBlocks("alice").size());
    }

    @Test
    void activeProjectFlagOffRejectsHidesAndIgnoresIt() {
        TenantMemoryService off = new TenantMemoryService(sidecar, jdbc, true, 1200, 2, 4000, false);
        assertThrows(IllegalArgumentException.class, () -> off.putBlock("alice", "active_project", "acme"));
        stubBlockRows("alice#mem:project:acme", "Acme wants dark mode", "alice#mem:active_project", "acme");
        assertEquals(1, off.listBlocks("alice").size());
        assertEquals("project:acme", off.listBlocks("alice").get(0).label());
        assertEquals(null, off.renderPromptBlocks("alice", "what should I do next?"));
    }

    @Test
    void renderWithLabelsReturnsTheSameTextPlusTheRenderedLabels() {
        stubBlockRows("alice#mem:persona", "Be terse", "alice#mem:user_profile", "  ",
            "alice#mem:project:acme", "Acme wants dark mode", "alice#mem:project:zeta", "Zeta notes",
            "alice#mem:active_project", "acme");
        MemoryBlocks.Rendered rendered = service.renderPromptBlocksWithLabels("alice", "hello");
        assertEquals(List.of("persona", "project:acme"), rendered.labels());
        assertEquals(service.renderPromptBlocks("alice", "hello"), rendered.text());
        assertEquals(null, service.renderPromptBlocksWithLabels(" ", "hello"));
    }
}
