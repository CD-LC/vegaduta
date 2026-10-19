package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.rag.ParentChildChunker.Child;
import com.agenticai.core.knowledge.rag.ParentChildChunker.Parent;
import com.agenticai.core.knowledge.rag.ParentChildChunker.Settings;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offsets and size guarantees of the two-level chunker. */
class ParentChildChunkerTest {

    private static int words(String s) {
        String t = s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static void assertInvariants(List<Parent> parents, Settings settings, int expectedTotalWords) {
        int childWords = 0;
        for (Parent p : parents) {
            assertTrue(words(p.text()) <= settings.parentWords(), "parent over budget: " + words(p.text()));
            assertTrue(!p.children().isEmpty(), "every parent has children");
            for (Child c : p.children()) {
                // The load-bearing property: the child IS the parent's substring at its offsets.
                assertEquals(c.text(), p.text().substring(c.startInParent(), c.endInParent()));
                assertTrue(words(c.text()) <= settings.childWords(), "child over budget: " + words(c.text()));
                assertTrue(words(c.text()) > 0);
                childWords += words(c.text());
            }
        }
        assertEquals(expectedTotalWords, childWords, "every source word lands in exactly one child");
    }

    @Test
    void headingsStartNewParentsAndChildrenKeepSentencesTogether() {
        String text = "# Refunds\nRefunds are issued within 14 days. Contact support first.\n\n"
            + "# Shipping\nShipping takes 5 days. Express takes 2 days.\n\nTracking numbers are emailed.";
        Settings settings = new Settings(10, 20);

        List<Parent> parents = ParentChildChunker.chunk(text, settings);

        assertEquals(2, parents.size());
        assertEquals("# Refunds\nRefunds are issued within 14 days. Contact support first.", parents.get(0).text());
        assertTrue(parents.get(1).text().startsWith("# Shipping"));
        assertEquals(0, parents.get(0).index());
        assertEquals(1, parents.get(1).index());

        List<Child> first = parents.get(0).children();
        assertEquals(2, first.size());
        assertEquals("# Refunds\nRefunds are issued within 14 days.", first.get(0).text());
        assertEquals("Contact support first.", first.get(1).text());
        assertEquals(parents.get(0).text().indexOf("Contact support"), first.get(1).startInParent());

        List<Child> second = parents.get(1).children();
        assertEquals("Tracking numbers are emailed.", second.get(second.size() - 1).text());

        assertInvariants(parents, settings, 25);
    }

    @Test
    void anOverlongSentenceIsCutByHardWordWindowsAtBothLevels() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 45; i++) {
            sb.append("w").append(i).append(i == 44 ? "" : " ");
        }
        Settings settings = new Settings(10, 20);

        List<Parent> parents = ParentChildChunker.chunk(sb.toString(), settings);

        assertEquals(3, parents.size());
        assertEquals(20, words(parents.get(0).text()));
        assertEquals(5, words(parents.get(2).text()));
        assertEquals(2, parents.get(0).children().size());
        assertInvariants(parents, settings, 45);
    }

    @Test
    void devanagariDandaEndsASentence() {
        String s1 = "one two three four five six।";
        String s2 = "seven eight nine ten eleven twelve।";
        String s3 = "alpha beta gamma delta epsilon zeta।";
        Settings settings = new Settings(10, 40);

        List<Parent> parents = ParentChildChunker.chunk(s1 + " " + s2 + " " + s3, settings);

        assertEquals(1, parents.size());
        List<Child> children = parents.get(0).children();
        assertEquals(3, children.size(), "6-word sentences never pair up under a 10-word child budget");
        assertEquals(s1, children.get(0).text());
        assertEquals(s3, children.get(2).text());
        assertInvariants(parents, settings, 18);
    }

    @Test
    void largerDocumentRespectsEveryBudgetAndCoversEveryWordOnce() {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int sentence = 0; sentence < 90; sentence++) {
            int len = 4 + (sentence * 7) % 9; // 4..12 words
            for (int w = 0; w < len; w++) {
                sb.append("t").append(total++);
                sb.append(w == len - 1 ? "." : "");
                sb.append(' ');
            }
            if (sentence % 11 == 10) {
                sb.append("\n\n");
            }
        }
        Settings settings = new Settings(25, 120);

        List<Parent> parents = ParentChildChunker.chunk(sb.toString(), settings);

        assertTrue(parents.size() > 3);
        assertInvariants(parents, settings, total);
    }

    @Test
    void crlfIsNormalisedAndShortTextIsOneParentOneChild() {
        List<Parent> parents = ParentChildChunker.chunk("alpha beta gamma.\r\n\r\ndelta epsilon.", new Settings(10, 20));

        assertEquals(1, parents.size());
        assertEquals(1, parents.get(0).children().size());
        assertEquals("alpha beta gamma.\n\ndelta epsilon.", parents.get(0).text());
        assertEquals(parents.get(0).text(), parents.get(0).children().get(0).text());
        assertEquals(0, parents.get(0).children().get(0).startInParent());
    }

    @Test
    void blankInputYieldsNothing() {
        assertTrue(ParentChildChunker.chunk(null, new Settings(10, 20)).isEmpty());
        assertTrue(ParentChildChunker.chunk("   \n\t ", new Settings(10, 20)).isEmpty());
    }

    @Test
    void settingsValidateAndClamp() {
        assertThrows(IllegalArgumentException.class, () -> new Settings(9, 100));
        assertThrows(IllegalArgumentException.class, () -> new Settings(201, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Settings(100, 150), "parent must be at least twice the child");
        assertThrows(IllegalArgumentException.class, () -> new Settings(100, ParentChildChunker.MAX_PARENT_WORDS + 1));

        Settings clamped = Settings.clamped(5, 7);
        assertEquals(10, clamped.childWords());
        assertEquals(20, clamped.parentWords());
        Settings big = Settings.clamped(500, 99999);
        assertEquals(200, big.childWords());
        assertEquals(ParentChildChunker.MAX_PARENT_WORDS, big.parentWords());
        Settings defaults = Settings.clamped(200, 1000);
        assertEquals(200, defaults.childWords());
        assertEquals(1000, defaults.parentWords());
    }
}
