package com.agenticai.core.speech;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Per-rule coverage of {@link SpeechInlineMarkup#normalizeInline} - the inline (single-line) half
 * of {@link SpeechTextNormalizer}: HTML tags and entities, autolinks, markdown links/images/code,
 * bare URLs and emails, emphasis markers, snake_case and the residual-markup sweep.
 */
class SpeechInlineMarkupTest {

    @Test
    void autolinkBecomesTheLinkPhrase() {
        assertThat(SpeechInlineMarkup.normalizeInline("see <https://example.com/x> now"))
                .isEqualTo("see a link now");
        assertThat(SpeechInlineMarkup.normalizeInline("write <mailto:a@b.co> me"))
                .isEqualTo("write a link me");
    }

    @Test
    void htmlTagsAreStrippedKeepingText() {
        assertThat(SpeechInlineMarkup.normalizeInline("<b>bold</b> and <i class=\"x\">it</i>"))
                .isEqualTo("bold and it");
    }

    @Test
    void entityEncodedTagsAreDecodedThenStrippedByTheSecondPass() {
        // "&lt;b&gt;" is only a tag AFTER entity decoding - the second HTML_TAG pass removes it.
        assertThat(SpeechInlineMarkup.normalizeInline("&lt;b&gt;hi&lt;/b&gt;"))
                .isEqualTo("hi");
    }

    @Test
    void namedAndNumericEntitiesDecodeInsideTheLine() {
        assertThat(SpeechInlineMarkup.normalizeInline("fish &amp; chips &#65;"))
                .isEqualTo("fish and chips A");
    }

    @Test
    void markdownLinksKeepTheirTextAndImagesKeepAltText() {
        assertThat(SpeechInlineMarkup.normalizeInline("click [here](https://x.test) and ![logo](i.png)"))
                .isEqualTo("click here and logo");
    }

    @Test
    void referenceLinksKeepTheirText() {
        assertThat(SpeechInlineMarkup.normalizeInline("read [the docs][1] later"))
                .isEqualTo("read the docs later");
    }

    @Test
    void inlineCodeKeepsItsContent() {
        assertThat(SpeechInlineMarkup.normalizeInline("run `mvn test` and ``double`` now"))
                .isEqualTo("run mvn test and double now");
    }

    @Test
    void bareUrlsBecomeTheLinkPhrase() {
        assertThat(SpeechInlineMarkup.normalizeInline("open https://example.com/a?b=c today"))
                .isEqualTo("open a link today");
        assertThat(SpeechInlineMarkup.normalizeInline("open www.example.com today"))
                .isEqualTo("open a link today");
    }

    @Test
    void bareEmailsBecomeTheEmailPhrase() {
        assertThat(SpeechInlineMarkup.normalizeInline("mail me at jane.doe@example.co.uk soon"))
                .isEqualTo("mail me at an email address soon");
        assertThat(SpeechInlineMarkup.normalizeInline("via mailto:x@y.io"))
                .isEqualTo("via an email address");
    }

    @Test
    void emphasisMarkersAreRemovedKeepingTheirText() {
        assertThat(SpeechInlineMarkup.normalizeInline("**bold** __strong__ ~~gone~~ *ital* _under_"))
                .isEqualTo("bold strong gone ital under");
    }

    @Test
    void snakeCaseIdentifiersBecomeSpacedWords() {
        assertThat(SpeechInlineMarkup.normalizeInline("call run_job_now then"))
                .isEqualTo("call run job now then");
    }

    @Test
    void unmatchedMarkdownIsSweptByTheResidualRule() {
        // An emphasis run the model never closed must not be pronounced "asterisk".
        assertThat(SpeechInlineMarkup.normalizeInline("a **bold that never ends"))
                .isEqualTo("a bold that never ends");
        assertThat(SpeechInlineMarkup.normalizeInline("pipes | backslash \\ tilde ~ backtick `"))
                .isEqualTo("pipes backslash tilde backtick");
    }

    @Test
    void whitespaceRunsCollapseAndTheLineIsTrimmed() {
        assertThat(SpeechInlineMarkup.normalizeInline("  too    many   spaces  "))
                .isEqualTo("too many spaces");
    }

    @Test
    void emojiAreDroppedAlongTheWay() {
        assertThat(SpeechInlineMarkup.normalizeInline("great 😀 job ✓"))
                .isEqualTo("great job");
    }
}
