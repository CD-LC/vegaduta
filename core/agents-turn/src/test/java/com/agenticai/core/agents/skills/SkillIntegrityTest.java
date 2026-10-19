package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.skills.SkillIntegrity.Status;
import com.agenticai.core.agents.skills.SkillScanner.RiskLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SkillIntegrityTest {

    /**
     * The digest SkillService has always stored in skill.content_sha256 - the pin must not change
     * it.
     */
    private static String legacyDigest(String description, String body, Map<String, String> files)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(description.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(body.getBytes(StandardCharsets.UTF_8));
        new TreeMap<>(files)
                .forEach(
                        (path, content) -> {
                            digest.update((byte) 0);
                            digest.update(path.getBytes(StandardCharsets.UTF_8));
                            digest.update((byte) 0);
                            digest.update(content.getBytes(StandardCharsets.UTF_8));
                        });
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void hashMatchesTheDigestAlreadyStoredOnSkillRows() throws Exception {
        Map<String, String> files = Map.of("b.md", "two", "a.md", "one");
        assertEquals(
                legacyDigest("desc", "body", files), SkillIntegrity.hash("desc", "body", files));
        assertEquals(
                legacyDigest("desc", "body", Map.of()), SkillIntegrity.hash("desc", "body", null));
    }

    @Test
    void hashIgnoresFileInsertionOrderButNotContent() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a.md", "1");
        ab.put("b.md", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b.md", "2");
        ba.put("a.md", "1");
        assertEquals(SkillIntegrity.hash("d", "b", ab), SkillIntegrity.hash("d", "b", ba));
        assertNotEquals(
                SkillIntegrity.hash("d", "b", ab),
                SkillIntegrity.hash("d", "b", Map.of("a.md", "1", "b.md", "3")));
        assertNotEquals(SkillIntegrity.hash("d", "b", ab), SkillIntegrity.hash("d2", "b", ab));
        assertNotEquals(SkillIntegrity.hash("d", "b", ab), SkillIntegrity.hash("d", "b2", ab));
    }

    @Test
    void statusFollowsTheRecordedApproval() {
        assertEquals(Status.UNPINNED, SkillIntegrity.status("abc", null));
        assertEquals(Status.UNPINNED, SkillIntegrity.status("abc", " "));
        assertEquals(Status.APPROVED, SkillIntegrity.status("abc", "ABC"));
        assertEquals(Status.NEEDS_REVIEW, SkillIntegrity.status("abc", "def"));
        assertEquals(Status.NEEDS_REVIEW, SkillIntegrity.status(null, "def"));
    }

    @Test
    void gateOnlyBitesWhenEnforcingAndNeverOnBuiltIns() {
        assertFalse(SkillIntegrity.blocked(false, Status.NEEDS_REVIEW, RiskLevel.HIGH));
        assertFalse(SkillIntegrity.blocked(true, Status.BUILT_IN, RiskLevel.HIGH));
        assertTrue(SkillIntegrity.blocked(true, Status.NEEDS_REVIEW, RiskLevel.NONE));
        assertTrue(SkillIntegrity.blocked(true, Status.UNPINNED, RiskLevel.HIGH));
        assertFalse(SkillIntegrity.blocked(true, Status.APPROVED, RiskLevel.HIGH));
        assertFalse(SkillIntegrity.blocked(true, Status.UNPINNED, RiskLevel.MEDIUM));
        assertFalse(SkillIntegrity.blocked(true, Status.UNPINNED, null));
        assertFalse(SkillIntegrity.blocked(true, Status.APPROVED, RiskLevel.NONE));
    }

    @Test
    void diffSummaryNamesWhatChanged() {
        String before =
                SkillIntegrity.manifestJson(
                        "d", "body one", Map.of("keep.md", "k", "gone.md", "g", "edit.md", "1"));
        String after =
                SkillIntegrity.manifestJson(
                        "d2", "body two", Map.of("keep.md", "k", "new.md", "n", "edit.md", "2"));
        List<String> changes = SkillIntegrity.diffSummary(before, after);
        assertTrue(changes.contains("Description changed"), changes.toString());
        assertTrue(changes.contains("Instructions (SKILL.md body) changed"), changes.toString());
        assertTrue(changes.contains("Added file: new.md"), changes.toString());
        assertTrue(changes.contains("Removed file: gone.md"), changes.toString());
        assertTrue(changes.contains("Changed file: edit.md"), changes.toString());
        assertFalse(changes.contains("Changed file: keep.md"));
    }

    @Test
    void diffSummaryOfAnUnchangedBodyOnlyReportsTheFileChange() {
        String before = SkillIntegrity.manifestJson("d", "same", Map.of("a.md", "1"));
        String after = SkillIntegrity.manifestJson("d", "same", Map.of("a.md", "2"));
        assertEquals(List.of("Changed file: a.md"), SkillIntegrity.diffSummary(before, after));
    }

    @Test
    void diffSummaryWithoutAnApprovedManifestStillExplains() {
        List<String> changes =
                SkillIntegrity.diffSummary(null, SkillIntegrity.manifestJson("d", "b", Map.of()));
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).contains("changed since it was approved"));
        assertEquals(1, SkillIntegrity.diffSummary("not json", "also not json").size());
    }

    @Test
    void manifestIsDeterministicAndHoldsNoSkillText() {
        String a =
                SkillIntegrity.manifestJson(
                        "secret description", "secret body", Map.of("f.md", "secret file"));
        String b =
                SkillIntegrity.manifestJson(
                        "secret description", "secret body", Map.of("f.md", "secret file"));
        assertEquals(a, b);
        assertFalse(
                a.contains("secret"), "the manifest keeps hashes only, never the approved text");
    }

    @Test
    void blockedReasonNamesTheSkillAndTheCause() {
        String changed =
                SkillIntegrity.blockedReason("my-skill", Status.NEEDS_REVIEW, RiskLevel.NONE);
        assertTrue(changed.contains("my-skill") && changed.contains("changed"), changed);
        String risky = SkillIntegrity.blockedReason("my-skill", Status.UNPINNED, RiskLevel.HIGH);
        assertTrue(risky.contains("high") && risky.contains("approved"), risky);
    }
}
