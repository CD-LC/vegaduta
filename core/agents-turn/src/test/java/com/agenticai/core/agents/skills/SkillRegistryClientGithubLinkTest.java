package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The GitHub folder link a user pastes is matched by a regex; it must not be a ReDoS surface. */
class SkillRegistryClientGithubLinkTest {

    private static final String PREFIX = "https://github.com/owner/repo/tree/main/";

    @Test
    void ordinaryFolderLinksStillMatch() {
        assertTrue(SkillRegistryClient.GITHUB_TREE.matcher(PREFIX + "skills/pdf").matches());
        assertTrue(SkillRegistryClient.GITHUB_TREE.matcher(PREFIX + "skills/my skill/").matches());
        assertTrue(
                SkillRegistryClient.GITHUB_TREE
                        .matcher("https://github.com/a.b/c_d/tree/feature/x/skills/pdf-tools")
                        .matches());
        assertFalse(SkillRegistryClient.GITHUB_TREE.matcher(PREFIX + "skills/pdf?x=1").matches());
        assertFalse(
                SkillRegistryClient.GITHUB_TREE
                        .matcher("https://evil.example/owner/repo/tree/main/skills")
                        .matches());
    }

    @Test
    void aLongHostileLinkIsRefusedInBoundedTime() {
        SkillRegistryClient client = new SkillRegistryClient(new ObjectMapper(), "", "");
        for (String filler : new String[] {"a", " ", "a "}) {
            String hostile = PREFIX + filler.repeat(50_000) + "!";
            SkillValidationException e =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(2),
                            () ->
                                    assertThrows(
                                            SkillValidationException.class,
                                            () -> client.fetchAsArchive(hostile)));
            assertTrue(e.getMessage().contains("not a GitHub skill folder link"));
        }
    }

    @Test
    void theMatcherItselfIsLinearOnAPathThatAlmostMatches() {
        // Under the cap, but the old nested optional-slash group backtracked exponentially on this
        // shape.
        String almost = PREFIX + "a".repeat(300) + "!";
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> SkillRegistryClient.GITHUB_TREE.matcher(almost).matches());
        String spaces = PREFIX + " ".repeat(50_000) + "!";
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> SkillRegistryClient.GITHUB_TREE.matcher(spaces).matches());
    }
}
