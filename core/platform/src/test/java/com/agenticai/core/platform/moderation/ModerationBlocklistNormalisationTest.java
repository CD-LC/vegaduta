package com.agenticai.core.platform.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Normalisation is the one piece of blocklist logic that can fail SILENTLY: the
 * read path and the write path both call it, so if it is inconsistent an entry
 * gets written that can never be matched, and the operator sees a block listed
 * in the UI that does nothing. Pinned here rather than left to integration.
 */
class ModerationBlocklistNormalisationTest {

    @Test
    @DisplayName("the same phone number written several ways collapses to one entry")
    void phoneVariantsCollapse() {
        String canonical = ModerationBlocklistService.normalise(ModerationBlocklistType.PHONE, "+919742424300");

        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.PHONE, "+91 97424-24300"));
        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.PHONE, " +91 (97424) 24300 "));
        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.PHONE, "+91.97424.24300"));
    }

    @Test
    @DisplayName("domains lose scheme, path, www and case")
    void domainVariantsCollapse() {
        String canonical = ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "example.com");

        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "Example.COM "));
        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "https://example.com"));
        assertEquals(canonical,
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "https://www.example.com/some/path"));
    }

    @Test
    @DisplayName("distinct domains stay distinct - normalisation must not over-collapse")
    void domainsDoNotOverCollapse() {
        assertNotEquals(
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "example.com"),
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "evil-example.com"));
        // A subdomain is NOT the same block as the apex domain. Collapsing these
        // would silently widen every block an operator adds.
        assertNotEquals(
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "example.com"),
            ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "mail.example.com"));
    }

    @Test
    @DisplayName("users and regions are case-insensitive")
    void userAndRegionCaseFolded() {
        assertEquals("in", ModerationBlocklistService.normalise(ModerationBlocklistType.REGION, " IN "));
        assertEquals("someone@example.com",
            ModerationBlocklistService.normalise(ModerationBlocklistType.USER, "Someone@Example.com"));
    }

    @Test
    @DisplayName("null and blank normalise to empty rather than throwing")
    void nullSafe() {
        assertEquals("", ModerationBlocklistService.normalise(ModerationBlocklistType.USER, null));
        assertEquals("", ModerationBlocklistService.normalise(ModerationBlocklistType.DOMAIN, "   "));
    }
}
