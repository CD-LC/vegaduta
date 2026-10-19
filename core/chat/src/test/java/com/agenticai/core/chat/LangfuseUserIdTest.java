package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LangfuseUserIdTest {

    @Test
    void defaultIsTheRealUserWithTenantFallback() {
        assertEquals("user-1", LangfuseUserId.DEFAULT.resolve("t1", "user-1"));
        assertEquals("t1", LangfuseUserId.DEFAULT.resolve("t1", null));
        assertEquals("t1", LangfuseUserId.DEFAULT.resolve("t1", " "));
        assertNull(LangfuseUserId.DEFAULT.resolve(null, null));
    }

    @Test
    void tenantModeNeverSendsTheUser() {
        LangfuseUserId id = LangfuseUserId.of("tenant", "salt");
        assertEquals("t1", id.resolve("t1", "user-1"));
    }

    @Test
    void userHashIsStableSaltedTenantScopedAndNeverTheRawId() {
        LangfuseUserId id = LangfuseUserId.of("user-hash", "pepper");
        String a = id.resolve("t1", "user-1");

        assertEquals(a, id.resolve("t1", "user-1"), "stable, so one user's traces group together");
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertFalse(a.contains("user-1"));
        assertNotEquals(a, id.resolve("t1", "user-2"), "different users separate");
        assertNotEquals(a, id.resolve("t2", "user-1"), "same subject in another tenant is another Langfuse user");
        assertNotEquals(a, LangfuseUserId.of("user-hash", "other-salt").resolve("t1", "user-1"), "salt changes the hash");
    }

    @Test
    void userHashFallsBackToTenantWhenThereIsNoSubject() {
        assertEquals("t1", LangfuseUserId.of("user-hash", "pepper").resolve("t1", null));
    }

    @Test
    void modeParsing() {
        assertEquals(LangfuseUserId.Mode.USER, LangfuseUserId.Mode.parse(null));
        assertEquals(LangfuseUserId.Mode.USER, LangfuseUserId.Mode.parse(" "));
        assertEquals(LangfuseUserId.Mode.USER_HASH, LangfuseUserId.Mode.parse("USER_HASH"));
        assertEquals(LangfuseUserId.Mode.USER_HASH, LangfuseUserId.Mode.parse("user-hash"));
        assertEquals(LangfuseUserId.Mode.TENANT, LangfuseUserId.Mode.parse("tenant"));
        assertEquals(LangfuseUserId.Mode.TENANT, LangfuseUserId.Mode.parse("userhsah"), "a typo must not widen what is sent");
    }
}
