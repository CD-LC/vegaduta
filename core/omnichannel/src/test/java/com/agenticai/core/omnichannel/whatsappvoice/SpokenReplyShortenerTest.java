package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpokenReplyShortenerTest {

    @Test
    void shortPlainReplyPassesThroughUnchanged() {
        assertEquals(Optional.of("Your order ships tomorrow."), SpokenReplyShortener.shorten("Your order ships tomorrow.", 500));
    }

    @Test
    void codeBlocksUrlsAndInlineCodeAreNeverSpoken() {
        String reply = "Run this:\n```bash\nrm -rf /tmp/x\n```\nThen open https://example.com/a?b=1 and call `doThing()` now.";

        String spoken = SpokenReplyShortener.shorten(reply, 500).orElseThrow();

        assertFalse(spoken.contains("rm -rf"));
        assertFalse(spoken.contains("http"));
        assertFalse(spoken.contains("doThing"));
        assertTrue(spoken.contains("Run this"));
        assertTrue(spoken.contains("now."));
    }

    @Test
    void unterminatedCodeFenceSwallowsTheRest() {
        String spoken = SpokenReplyShortener.shorten("Here you go. ```python\nprint(1)\nprint(2)", 500).orElseThrow();

        assertEquals("Here you go.", spoken);
    }

    @Test
    void markdownIsFlattenedAndLinksKeepTheirLabel() {
        String spoken = SpokenReplyShortener.shorten("## Plan\n- **First** item\n- see [the docs](https://x.example/d) today", 500).orElseThrow();

        assertEquals("Plan First item see the docs today", spoken);
    }

    @Test
    void replyThatIsOnlyCodeOrUrlsHasNothingToSpeak() {
        assertTrue(SpokenReplyShortener.shorten("```\nlots of code\n```", 500).isEmpty());
        assertTrue(SpokenReplyShortener.shorten("https://example.com/only/a/link", 500).isEmpty());
        assertTrue(SpokenReplyShortener.shorten("   ", 500).isEmpty());
        assertTrue(SpokenReplyShortener.shorten(null, 500).isEmpty());
        assertTrue(SpokenReplyShortener.shorten("... --- ...", 500).isEmpty());
    }

    @Test
    void longReplyIsCutAtASentenceBoundaryWithinTheCap() {
        String reply = "First sentence is here. Second sentence follows it. Third sentence goes on and on and on.";

        String spoken = SpokenReplyShortener.shorten(reply, 50).orElseThrow();

        assertEquals("First sentence is here.", spoken);
        assertTrue(spoken.length() <= 50);
    }

    @Test
    void devanagariDandaCountsAsASentenceEnd() {
        String reply = "यह पहला वाक्य है। यह दूसरा वाक्य बहुत लंबा है और चलता रहता है";

        String spoken = SpokenReplyShortener.shorten(reply, 30).orElseThrow();

        assertTrue(spoken.endsWith("।"), spoken);
        assertTrue(spoken.length() <= 30);
    }

    @Test
    void shortHindiAnswerWithCombiningMarksIsNotDiscarded() {
        // "haan" - letters plus vowel signs; must survive the "is anything speakable" check.
        assertTrue(SpokenReplyShortener.shorten("हाँ", 500).isPresent());
    }

    @Test
    void noBoundaryFallsBackToTheLastSpace() {
        String reply = "word ".repeat(40);

        String spoken = SpokenReplyShortener.shorten(reply, 33).orElseThrow();

        assertTrue(spoken.length() <= 33);
        assertTrue(spoken.endsWith("word"));
    }

    @Test
    void neverSplitsASurrogatePairAtTheCut() {
        // The emoji's high surrogate sits at index 32, exactly where a 33-char cut would end.
        String reply = "a".repeat(32) + "😀" + " tail words here";

        String spoken = SpokenReplyShortener.shorten(reply, 33).orElseThrow();

        assertEquals("a".repeat(32), spoken);
        assertFalse(Character.isHighSurrogate(spoken.charAt(spoken.length() - 1)));
    }

    @Test
    void htmlTagsGoButComparisonOperatorsStay() {
        assertEquals(Optional.of("bold word"), SpokenReplyShortener.shorten("<b>bold</b> word", 500));
        assertEquals(Optional.of("Use x < 5 and y > 3."), SpokenReplyShortener.shorten("Use x < 5 and y > 3.", 500));
    }

    @Test
    void capHasAFloorSoAZeroConfigDoesNotEmptyEveryReply() {
        assertTrue(SpokenReplyShortener.shorten("A reasonably short answer for you.", 0).isPresent());
    }
}
