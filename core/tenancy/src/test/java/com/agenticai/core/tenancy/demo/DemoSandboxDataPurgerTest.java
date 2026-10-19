package com.agenticai.core.tenancy.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.storage.FileStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoSandboxDataPurgerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final DemoSandboxDataPurger purger = new DemoSandboxDataPurger(jdbc, storage);
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void deletesMediaObjectsThenSessionKeyedRowsThenTheSessionsLast() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(tenantId)))
                .thenAnswer(
                        inv -> {
                            String sql = inv.getArgument(0);
                            if (sql.contains("demo_generated_image")) {
                                return List.of("demo-images/a.png");
                            }
                            if (sql.contains("demo_generated_music")) {
                                return List.of("demo-music/b.mp3");
                            }
                            return List.of();
                        });
        List<String> updates = new ArrayList<>();
        when(jdbc.update(anyString(), eq(tenantId)))
                .thenAnswer(
                        inv -> {
                            updates.add(inv.getArgument(0));
                            return 1;
                        });

        List<String> failures = purger.purgeForTenant(tenantId);

        assertTrue(failures.isEmpty());
        verify(storage).delete("demo-images/a.png");
        verify(storage).delete("demo-music/b.mp3");
        assertEquals(7, updates.size());
        for (String table :
                List.of(
                        "demo_generated_image",
                        "demo_generated_video",
                        "demo_generated_music",
                        "demo_chat_message",
                        "demo_channel_message",
                        "demo_channel_link")) {
            assertTrue(
                    updates.stream()
                            .anyMatch(
                                    sql ->
                                            sql.startsWith(
                                                    "DELETE FROM control_plane." + table + " ")),
                    "missing " + table);
        }
        // demo_session goes last: the session-keyed deletes select session ids through it,
        // and its delete cascades demo_magic_workflow / demo_studio_*.
        assertEquals(
                "DELETE FROM control_plane.demo_session WHERE tenant_id = ?",
                updates.get(updates.size() - 1));
        // Every session-keyed delete is scoped to THIS tenant's sessions.
        updates.subList(0, 6)
                .forEach(
                        sql ->
                                assertTrue(
                                        sql.contains(
                                                "(SELECT id FROM control_plane.demo_session WHERE"
                                                        + " tenant_id = ?)")));
    }

    @Test
    void storageAndTableFailuresAreReportedNotThrown() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(tenantId)))
                .thenReturn(List.of("demo-images/a.png"));
        doThrow(new RuntimeException("s3 timeout")).when(storage).delete("demo-images/a.png");
        when(jdbc.update(anyString(), eq(tenantId)))
                .thenAnswer(
                        inv -> {
                            if (((String) inv.getArgument(0)).contains("demo_chat_message")) {
                                throw new RuntimeException("lock timeout");
                            }
                            return 0;
                        });

        List<String> failures = purger.purgeForTenant(tenantId);

        assertEquals(List.of("demo_chat_message"), failures);
    }
}
