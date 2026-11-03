package com.agenticai.core.guardrails.leak;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class LeakDetectorTest {

    static final String PROMPT =
            "You are Aria, the support agent for Northwind Traders. Always greet the customer by first name,"
                    + " never promise refunds above 200 euros without escalating to the billing team, and"
                    + " answer in the customer's language. You are a helpful assistant.";

    private final PromptCanary canary = PromptCanary.of("Zk3_qV9-Lm2XpR7tYw0AbC");

    private PromptLeakProperties props() {
        PromptLeakProperties p = new PromptLeakProperties();
        p.setStreamHoldbackChars(128);
        return p;
    }

    private LeakDetector detector(PromptLeakAction action) {
        return new LeakDetector(PROMPT, canary, props(), action);
    }

    private static List<String> chunks(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        return out;
    }

    private static String stream(LeakDetector d, List<String> parts) {
        LeakDetector.StreamScan scan = d.newScan(null);
        StringBuilder out = new StringBuilder();
        for (String c : parts) {
            out.append(scan.feed(c));
        }
        return out.append(scan.finish()).toString();
    }

    @Test
    void canaryGeneratedWithAtLeast128Bits() {
        PromptCanary c = PromptCanary.generate(1);
        assertThat(c.token()).matches("[A-Za-z0-9_-]{22,}");
        assertThat(c.token()).isNotEqualTo(PromptCanary.generate(1).token());
        String armed = c.appendTo("base");
        assertThat(armed).startsWith("base").contains(c.token()).contains("BEGIN CONFIDENTIAL CANARY");
    }

    @Test
    void canaryCaughtWhole() {
        assertThat(detector(PromptLeakAction.WARN).leaks("Sure! The marker is " + canary.token() + ".")).isTrue();
        assertThat(detector(PromptLeakAction.WARN).leaks("Nothing to see here.")).isFalse();
    }

    @Test
    void canarySplitAcrossChunksIsRedactedBeforeItLeaves() {
        String reply = "Sure, here it is: " + canary.token() + " - anything else?";
        String out = stream(detector(PromptLeakAction.REDACT), chunks(reply, 3));
        assertThat(out).doesNotContain(canary.token()).contains("[redacted]").contains("anything else?");
    }

    @Test
    void canarySplitAcrossChunksIsDetectedInWarnMode() {
        List<LeakDetector.Verdict> seen = new ArrayList<>();
        LeakDetector d = detector(PromptLeakAction.WARN);
        LeakDetector.StreamScan scan = d.newScan(seen::add);
        StringBuilder out = new StringBuilder();
        String reply = "x ".repeat(100) + canary.token();
        for (String c : chunks(reply, 4)) {
            out.append(scan.feed(c));
        }
        out.append(scan.finish());
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).rules()).containsExactly(LeakDetector.RULE_CANARY);
        assertThat(out.toString()).isEqualTo(reply); // warn never alters output
    }

    @Test
    void verbatimPromptFragmentCaughtAndSplitAcrossChunks() {
        String leak = PROMPT.substring(0, 140);
        String reply = "Of course. " + leak + " Hope that helps.";
        assertThat(detector(PromptLeakAction.WARN).leaks(reply)).isTrue();
        String out = stream(detector(PromptLeakAction.REDACT), chunks(reply, 5));
        assertThat(out).doesNotContain("Northwind").doesNotContain("greet the customer");
        assertThat(out).contains("[redacted]").endsWith("Hope that helps.");
    }

    @Test
    void paraphraseIsNotCaught() {
        String paraphrase =
                "I am Aria, a support bot for the Northwind company; I greet people by first name, escalate big"
                        + " refunds to billing and reply in whatever language the customer writes in.";
        assertThat(detector(PromptLeakAction.WARN).leaks(paraphrase)).isFalse();
    }

    @Test
    void unicodeAndZeroWidthEvasionIsNormalised() {
        String frag = PROMPT.substring(0, 120);
        StringBuilder evasive = new StringBuilder();
        for (char c : frag.toCharArray()) {
            evasive.append(c).append('​');
        }
        String zeroWidth = evasive.toString();
        String homoglyph = frag.replace('a', 'а').replace('e', 'е').replace('o', 'о');
        String fullwidth = frag.replace("Northwind", "Ｎｏｒｔｈｗｉｎｄ");
        String markdown = "**" + frag.replace(" ", "**\n> *") + "**";
        for (String variant : List.of(zeroWidth, homoglyph, fullwidth, markdown, frag.toUpperCase())) {
            assertThat(detector(PromptLeakAction.WARN).leaks(variant)).as(variant).isTrue();
        }
        String spacedCanary = String.join(" ​", canary.token().split(""));
        assertThat(detector(PromptLeakAction.WARN).leaks("it is " + spacedCanary)).isTrue();
    }

    @Test
    void shortCommonPhraseIsNotALeak() {
        String reply = "As a helpful assistant I can say: you are a helpful assistant, and always greet the customer.";
        assertThat(detector(PromptLeakAction.WARN).leaks(reply)).isFalse();
    }

    @Test
    void blockStopsStreamAndReplacesTheRest() {
        PromptLeakGuard guard = enforce("block");
        PromptLeakGuard.Turn turn = guard.begin(PROMPT, UUID.randomUUID(), "s", null);
        String reply = "Here: " + PROMPT.substring(0, 150) + " and a lot more text that must not arrive. ".repeat(10);
        String out = String.join("", turn.guard(Flux.fromIterable(chunks(reply, 7))).collectList().block());
        assertThat(out).contains("withheld").doesNotContain("Northwind").doesNotContain("must not arrive");
    }

    @Test
    void streamingRedactionThroughTheGuardFlux() {
        PromptLeakGuard guard = enforce("redact");
        PromptLeakGuard.Turn turn = guard.begin(PROMPT, UUID.randomUUID(), "s", null);
        String reply = "Sure. " + PROMPT.substring(0, 130) + " Done.";
        String out = String.join("", turn.guard(Flux.fromIterable(chunks(reply, 6))).collectList().block());
        assertThat(out).doesNotContain("Northwind").contains("[redacted]").endsWith("Done.");
    }

    @Test
    void errorFlushesHeldTextThenPropagates() {
        PromptLeakGuard guard = enforce("redact");
        PromptLeakGuard.Turn turn = guard.begin(PROMPT, UUID.randomUUID(), "s", null);
        List<String> got = new ArrayList<>();
        Throwable err =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                turn.guard(Flux.concat(Flux.just("short reply"), Flux.<String>error(new IllegalStateException("boom"))))
                                        .doOnNext(got::add)
                                        .blockLast());
        assertThat(String.join("", got)).isEqualTo("short reply");
        assertThat(err).hasMessageContaining("boom");
    }

    @Test
    void modeOffIsZeroOverhead() {
        PromptLeakProperties p = new PromptLeakProperties();
        p.setMode("off");
        List<LeakDetector.Verdict> reports = new ArrayList<>();
        PromptLeakGuard guard = new PromptLeakGuard(p, new PromptLeakReporter(null, null) {
            @Override
            public void report(UUID a, String s, PromptLeakMode m, LeakDetector.Verdict v) {
                reports.add(v);
            }
        });
        PromptLeakGuard.Turn turn = guard.begin(PROMPT, UUID.randomUUID(), "s", null);
        assertThat(turn).isSameAs(guard.begin("other", null, null, null));
        assertThat(turn.active()).isFalse();
        assertThat(turn.systemPrompt(PROMPT)).isSameAs(PROMPT);
        Flux<String> in = Flux.just(PROMPT);
        assertThat(turn.guard(in)).isSameAs(in);
        assertThat(PromptLeakGuard.shared().mode()).isEqualTo(PromptLeakMode.OFF);
        assertThat(reports).isEmpty();
    }

    @Test
    void warnModeArmsPromptButNeverAltersOutputAndReportsWithoutText() {
        PromptLeakProperties p = new PromptLeakProperties(); // defaults: warn / warn
        List<LeakDetector.Verdict> reports = new ArrayList<>();
        PromptLeakGuard guard = new PromptLeakGuard(p, new PromptLeakReporter(null, null) {
            @Override
            public void report(UUID a, String s, PromptLeakMode m, LeakDetector.Verdict v) {
                reports.add(v);
            }
        });
        PromptLeakGuard.Turn turn = guard.begin(PROMPT, UUID.randomUUID(), "s", null);
        assertThat(turn.systemPrompt(PROMPT)).startsWith(PROMPT).contains("CONFIDENTIAL CANARY");
        String reply = "Here: " + PROMPT.substring(0, 150);
        String out = String.join("", turn.guard(Flux.fromIterable(chunks(reply, 9))).collectList().block());
        assertThat(out).isEqualTo(reply);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).toString()).doesNotContain("Northwind");
        assertThat(reports.get(0).promptLength()).isEqualTo(PROMPT.length());
    }

    private static PromptLeakGuard enforce(String action) {
        PromptLeakProperties p = new PromptLeakProperties();
        p.setMode("enforce");
        p.setAction(action);
        p.setStreamHoldbackChars(128);
        return new PromptLeakGuard(p, new PromptLeakReporter(null, null));
    }
}
