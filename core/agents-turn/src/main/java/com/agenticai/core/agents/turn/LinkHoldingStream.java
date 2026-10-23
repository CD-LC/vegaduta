package com.agenticai.core.agents.turn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BinaryOperator;
import reactor.core.publisher.Flux;

/**
 * Streams a reply as it is generated and holds back only what comes from the first link onward, so a check that needs
 * the finished reply (the invented-link guard) can still replace a link before it reaches the person while every word
 * before it arrives live. Before this the whole reply of such a turn was held until the model finished.
 *
 * <p>A hold marker that starts at the end of one chunk and finishes in the next is never sent half: the last
 * {@code longest marker - 1} characters are kept back until the next chunk shows they do not start one. Markers are
 * compared ignoring case. Each subscription has its own state.
 */
final class LinkHoldingStream {

    private final List<String> markers;
    private final int lookBehind;
    private final StringBuilder all = new StringBuilder();
    private int sent;
    private boolean holding;

    private LinkHoldingStream(List<String> markers) {
        this.markers = markers;
        int longest = 0;
        for (String marker : markers) {
            longest = Math.max(longest, marker.length());
        }
        this.lookBehind = Math.max(0, longest - 1);
    }

    /**
     * @param holdMarkers text that starts the held part (blank entries ignored); none = the whole reply is streamed
     *     and {@code finish} still sees it at the end
     * @param finish given the whole reply and the held tail, returns what to send in place of the tail (the tail
     *     itself when the reply passed the check)
     */
    static Flux<String> apply(Flux<String> source, Collection<String> holdMarkers, BinaryOperator<String> finish) {
        List<String> markers = normalise(holdMarkers);
        return Flux.defer(
                () -> {
                    LinkHoldingStream state = new LinkHoldingStream(markers);
                    return source.concatMap(chunk -> Flux.fromIterable(state.accept(chunk)))
                            .concatWith(Flux.defer(() -> Flux.fromIterable(state.complete(finish))));
                });
    }

    private List<String> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        all.append(chunk);
        if (holding) {
            return List.of();
        }
        int start = firstMarker(Math.max(0, sent - lookBehind));
        if (start >= 0) {
            holding = true;
            return release(start);
        }
        return release(Math.max(sent, all.length() - lookBehind));
    }

    private List<String> complete(BinaryOperator<String> finish) {
        String whole = all.toString();
        String tail = whole.substring(sent);
        String replacement = finish.apply(whole, tail);
        sent = all.length();
        return replacement == null || replacement.isEmpty() ? List.of() : List.of(replacement);
    }

    private List<String> release(int upTo) {
        if (upTo <= sent) {
            return List.of();
        }
        String out = all.substring(sent, upTo);
        sent = upTo;
        return List.of(out);
    }

    private int firstMarker(int from) {
        if (markers.isEmpty()) {
            return -1;
        }
        String text = all.toString();
        for (int i = from; i < text.length(); i++) {
            for (String marker : markers) {
                // regionMatches, not a lower-cased copy: lower-casing can change a string's length (Turkish
                // dotted I), which would shift every index after it.
                if (text.regionMatches(true, i, marker, 0, marker.length())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> normalise(Collection<String> holdMarkers) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (holdMarkers != null) {
            for (String marker : holdMarkers) {
                if (marker != null && !marker.isBlank()) {
                    out.add(marker.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(out);
    }
}
