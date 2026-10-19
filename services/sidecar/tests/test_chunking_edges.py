"""
Additional chunk_text() edge cases not covered by test_pipeline.py's
TestChunkText - unicode/multi-byte words, single-unsplittable-token input,
exact step-boundary termination (the `if end >= total: break` branch), and
the no-overlap case (chunk_overlap=0), which the existing suite never
exercises on its own.

Same posture as the rest of tests/: pure-logic, no model loading, no
network. chunk_text has no external dependencies at all, so nothing here
needs mocking.

Run from sidecar/:  pytest tests/
"""
from app.chunking import chunk_text


class TestChunkTextUnicode:
    def test_multibyte_words_are_preserved_intact_in_a_single_chunk(self):
        # Hindi, Tamil, and an emoji - str.split()/str.join() operate on
        # whole Unicode code-point "words", so none of this should be
        # mangled, truncated, or byte-split.
        words = ["नमस्ते", "ग्राहक", "வணக்கம்", "ஆவணம்", "🚀", "café", "naïve"]
        text = " ".join(words)

        chunks = chunk_text(text, chunk_size=150, chunk_overlap=30)

        assert chunks == [text]
        assert chunks[0].split() == words

    def test_multibyte_words_split_and_overlap_exactly_like_ascii(self):
        words = [f"शब्द{i}" for i in range(10)]
        chunks = chunk_text(" ".join(words), chunk_size=4, chunk_overlap=2)

        assert chunks == [
            "शब्द0 शब्द1 शब्द2 शब्द3",
            "शब्द2 शब्द3 शब्द4 शब्द5",
            "शब्द4 शब्द5 शब्द6 शब्द7",
            "शब्द6 शब्द7 शब्द8 शब्द9",
        ]


class TestChunkTextUnsplittableToken:
    def test_single_giant_token_with_no_whitespace_is_one_whole_chunk(self):
        # A single "word" (e.g. a URL, a base64 blob, a hash) with no
        # internal whitespace is exactly one str.split() token no matter how
        # long it is - the chunker must never cut it, even with the
        # smallest legal chunk_size.
        giant_token = "x" * 5000
        chunks = chunk_text(giant_token, chunk_size=1, chunk_overlap=0)

        assert chunks == [giant_token]

    def test_giant_token_mixed_with_normal_words_still_never_splits_it(self):
        giant_token = "y" * 2000
        text = f"intro {giant_token} outro"
        chunks = chunk_text(text, chunk_size=2, chunk_overlap=0)

        # 3 words total, chunk_size 2, no overlap -> ["intro giant", "outro"]
        assert chunks == [f"intro {giant_token}", "outro"]
        # The giant token itself must appear whole, in exactly one chunk.
        hits = [c for c in chunks if giant_token in c]
        assert len(hits) == 1
        assert hits[0].split().count(giant_token) == 1


class TestChunkTextBoundaries:
    def test_exact_multiple_of_step_terminates_without_a_trailing_empty_chunk(self):
        # 12 words, chunk_size=4, chunk_overlap=0 -> step=4 divides evenly:
        # starts 0, 4, 8; the third chunk's end (12) hits `total` exactly,
        # so the loop must break immediately rather than looping once more
        # with an out-of-range/empty start.
        words = [f"w{i}" for i in range(12)]
        chunks = chunk_text(" ".join(words), chunk_size=4, chunk_overlap=0)

        assert chunks == ["w0 w1 w2 w3", "w4 w5 w6 w7", "w8 w9 w10 w11"]

    def test_exact_multiple_of_step_with_overlap_terminates_cleanly(self):
        # 8 words, chunk_size=4, chunk_overlap=2 -> step=2: starts 0,2,4;
        # the third chunk's end (4+4=8) hits `total` exactly.
        words = [f"w{i}" for i in range(8)]
        chunks = chunk_text(" ".join(words), chunk_size=4, chunk_overlap=2)

        assert chunks == ["w0 w1 w2 w3", "w2 w3 w4 w5", "w4 w5 w6 w7"]

    def test_one_word_short_of_exact_multiple_adds_a_final_partial_chunk(self):
        # 11 words (one short of the 12-word exact case above), chunk_size=4,
        # chunk_overlap=0 -> starts 0,4,8; third chunk's end is min(12,11)=11,
        # which is >= total, so it stops there with a shorter final chunk
        # instead of ever producing a 4th, empty one.
        words = [f"w{i}" for i in range(11)]
        chunks = chunk_text(" ".join(words), chunk_size=4, chunk_overlap=0)

        assert chunks == ["w0 w1 w2 w3", "w4 w5 w6 w7", "w8 w9 w10"]


class TestChunkTextNoOverlap:
    def test_zero_overlap_never_repeats_a_word_across_chunks(self):
        words = [f"word{i}" for i in range(23)]
        chunks = chunk_text(" ".join(words), chunk_size=5, chunk_overlap=0)

        seen: list[str] = []
        for chunk in chunks:
            seen.extend(chunk.split())
        # With zero overlap every source word appears exactly once, in
        # original order, across the whole set of chunks.
        assert seen == words

    def test_chunk_size_one_produces_one_word_per_chunk(self):
        words = [f"tok{i}" for i in range(6)]
        chunks = chunk_text(" ".join(words), chunk_size=1, chunk_overlap=0)

        assert chunks == words


class TestChunkTextWhitespaceHandling:
    def test_irregular_internal_whitespace_collapses_like_str_split(self):
        text = "alpha\tbeta\n\ncharlie   delta"
        chunks = chunk_text(text, chunk_size=150, chunk_overlap=30)

        assert chunks == ["alpha beta charlie delta"]
