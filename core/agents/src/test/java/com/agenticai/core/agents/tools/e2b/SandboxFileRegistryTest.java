package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SandboxFileRegistryTest {

    private static final String CSV = "text/csv";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AtomicLong now = new AtomicLong(1_000);
    private final UUID agent = UUID.randomUUID();

    private SandboxFileRegistry registry(boolean enabled) {
        return new SandboxFileRegistry(enabled, 3, 100, 150, 60_000, now::get);
    }

    @Test
    void registersAndReturnsFilesForTheSameAgentAndSession() {
        SandboxFileRegistry r = registry(true);
        assertTrue(
                r.register(
                        agent, "s1", "Sales 2025.csv", CSV + "; charset=utf-8", new byte[] {1, 2}));
        List<SandboxFile> files = r.filesFor(agent, "s1");
        assertEquals(1, files.size());
        assertEquals("Sales_2025.csv", files.get(0).name());
        assertTrue(r.filesFor(agent, "other").isEmpty());
        assertTrue(
                r.filesFor(UUID.randomUUID(), "s1").isEmpty(),
                "another agent/tenant must not see the files");
    }

    @Test
    void unnamedAndDuplicateNamesGetStableUniqueNames() {
        SandboxFileRegistry r = registry(true);
        r.register(agent, "s", null, CSV, new byte[] {1});
        r.register(agent, "s", null, CSV, new byte[] {1});
        r.register(agent, "s", "data.csv", CSV, new byte[] {1});
        List<String> names = r.filesFor(agent, "s").stream().map(SandboxFile::name).toList();
        assertEquals(3, names.size());
        assertEquals(3, names.stream().distinct().count());
        assertEquals("data.csv", names.get(0));
    }

    @Test
    void pathTricksInFileNamesAreFlattened() {
        assertEquals("passwd.csv", SandboxFileRegistry.sanitizeName("../../etc/passwd", ".csv", 1));
        assertEquals("data.xlsx", SandboxFileRegistry.sanitizeName("   ", ".xlsx", 1));
    }

    @Test
    void rejectsNonDataEmptyOversizedAndOverCapFiles() {
        SandboxFileRegistry r = registry(true);
        assertFalse(r.register(agent, "s", "a.png", "image/png", new byte[] {1}));
        assertFalse(r.register(agent, "s", "a.csv", CSV, new byte[0]));
        assertFalse(r.register(agent, "s", "a.csv", CSV, new byte[101]), "per-file cap");
        assertTrue(r.register(agent, "s", "a.csv", CSV, new byte[100]));
        assertFalse(r.register(agent, "s", "b.csv", CSV, new byte[100]), "per-session byte cap");
        assertTrue(r.register(agent, "s", "b.xlsx", XLSX, new byte[40]));
    }

    @Test
    void expiresAfterTtl() {
        SandboxFileRegistry r = registry(true);
        r.register(agent, "s", "a.csv", CSV, new byte[] {1});
        now.addAndGet(60_001);
        assertTrue(r.filesFor(agent, "s").isEmpty());
    }

    @Test
    void disabledRegistryIsANoOp() {
        SandboxFileRegistry r = registry(false);
        assertFalse(r.register(agent, "s", "a.csv", CSV, new byte[] {1}));
        assertTrue(r.filesFor(agent, "s").isEmpty());
    }

    @Test
    void mountableTypesAreDataOnly() {
        assertTrue(SandboxFileRegistry.isMountable("application/pdf"));
        assertTrue(SandboxFileRegistry.isMountable(XLSX));
        assertFalse(SandboxFileRegistry.isMountable("image/png"));
        assertFalse(SandboxFileRegistry.isMountable(null));
    }
}
