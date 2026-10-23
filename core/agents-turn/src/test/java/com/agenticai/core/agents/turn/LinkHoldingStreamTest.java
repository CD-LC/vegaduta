package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * The streamed invented-link hold: every word before the first link arrives as it is generated, nothing from a link
 * on is sent before the reply is complete, and the held part is sent as written or replaced.
 */
class LinkHoldingStreamTest {

    private static final List<String> MARKERS = List.of("http", "www.");

    private static List<String> run(List<String> chunks, String replacement) {
        return LinkHoldingStream.apply(
                        Flux.fromIterable(chunks), MARKERS, (whole, held) -> replacement == null ? held : replacement)
                .collectList()
                .block();
    }

    @Test
    void aReplyWithoutALinkIsStreamedChunkByChunkAndArrivesWhole() {
        List<String> out = run(List.of("The sky ", "is blue ", "because of ", "Rayleigh scattering."), null);
        assertThat(String.join("", out)).isEqualTo("The sky is blue because of Rayleigh scattering.");
        // Streamed, not collected: several pieces reach the person before the end.
        assertThat(out.size()).isGreaterThan(2);
    }

    @Test
    void wordsBeforeALinkAreSentLiveAndTheLinkOnlyAfterTheReplyIsComplete() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        List<String> received = new ArrayList<>();
        AtomicReference<String> heldSeen = new AtomicReference<>();
        LinkHoldingStream.apply(source.asFlux(), MARKERS, (whole, held) -> {
                    heldSeen.set(held);
                    return held;
                })
                .subscribe(received::add);

        source.tryEmitNext("Your report is here: ");
        source.tryEmitNext("https://files.example/report.pdf");
        source.tryEmitNext(" enjoy");
        assertThat(String.join("", received)).isEqualTo("Your report is here: ");

        source.tryEmitComplete();
        assertThat(String.join("", received)).isEqualTo("Your report is here: https://files.example/report.pdf enjoy");
        assertThat(heldSeen.get()).isEqualTo("https://files.example/report.pdf enjoy");
    }

    @Test
    void aMarkerSplitAcrossChunksIsNeverSentHalf() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        List<String> received = new ArrayList<>();
        LinkHoldingStream.apply(source.asFlux(), MARKERS, (whole, held) -> "[checked]").subscribe(received::add);

        source.tryEmitNext("See h");
        source.tryEmitNext("t");
        source.tryEmitNext("tps://x.example/a.png");
        assertThat(String.join("", received)).isEqualTo("See ");

        source.tryEmitComplete();
        assertThat(String.join("", received)).isEqualTo("See [checked]");
    }

    @Test
    void anInventedLinkIsReplacedAndNeverReachesThePerson() {
        List<String> out = run(List.of("Done! Download it at ", "HTTPS://Fake.example/x.pdf", " now."), "\n\nI could not create that file.");
        String joined = String.join("", out);
        assertThat(joined).isEqualTo("Done! Download it at \n\nI could not create that file.");
        assertThat(joined.toLowerCase()).doesNotContain("fake.example");
    }

    @Test
    void aLinkAtTheVeryStartHoldsTheWholeReply() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        List<String> received = new ArrayList<>();
        LinkHoldingStream.apply(source.asFlux(), MARKERS, (whole, held) -> held).subscribe(received::add);
        source.tryEmitNext("www.example.org is the site");
        assertThat(received).isEmpty();
        source.tryEmitComplete();
        assertThat(String.join("", received)).isEqualTo("www.example.org is the site");
    }

    @Test
    void textWhoseLowerCaseChangesLengthDoesNotShiftTheHold() {
        // Turkish capital dotted I lower-cases to two characters; the hold must still start exactly at the link.
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        List<String> received = new ArrayList<>();
        LinkHoldingStream.apply(source.asFlux(), MARKERS, (whole, held) -> held).subscribe(received::add);
        source.tryEmitNext("İİİİ bak: ");
        source.tryEmitNext("http://ornek.example");
        assertThat(String.join("", received)).isEqualTo("İİİİ bak: ");
        source.tryEmitComplete();
        assertThat(String.join("", received)).isEqualTo("İİİİ bak: http://ornek.example");
    }

    @Test
    void anEmptyReplyAndEmptyChunksSendNothingButStillReachTheCheck() {
        AtomicReference<String> whole = new AtomicReference<>();
        List<String> out = LinkHoldingStream.apply(Flux.just("", ""), MARKERS, (w, held) -> {
                    whole.set(w);
                    return held;
                })
                .collectList()
                .block();
        assertThat(out).isEmpty();
        assertThat(whole.get()).isEmpty();
    }

    @Test
    void noMarkersStreamsEverythingAndTheCheckStillSeesTheWholeReply() {
        AtomicReference<String> whole = new AtomicReference<>();
        List<String> out = LinkHoldingStream.apply(Flux.just("a http://b ", "c"), List.of(" ", ""), (w, held) -> {
                    whole.set(w);
                    return held;
                })
                .collectList()
                .block();
        assertThat(String.join("", out)).isEqualTo("a http://b c");
        assertThat(whole.get()).isEqualTo("a http://b c");
    }

    @Test
    void eachSubscriptionHasItsOwnState() {
        Flux<String> stream = LinkHoldingStream.apply(Flux.just("one ", "http://x ", "two"), MARKERS, (w, held) -> held);
        assertThat(String.join("", stream.collectList().block())).isEqualTo("one http://x two");
        assertThat(String.join("", stream.collectList().block())).isEqualTo("one http://x two");
    }

    @Test
    void anErrorMidStreamPropagatesAndSendsNothingHeld() {
        List<String> received = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        LinkHoldingStream.apply(
                        Flux.just("before ", "http://x").concatWith(Flux.error(new IllegalStateException("boom"))),
                        MARKERS,
                        (w, held) -> held)
                .subscribe(received::add, error::set);
        assertThat(String.join("", received)).isEqualTo("before ");
        assertThat(error.get()).hasMessage("boom");
    }
}
