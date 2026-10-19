"""
Stage 5: text chunker.

Splits on whitespace into words and slides a fixed-size window over them,
rather than splitting by raw character count. Two reasons: (1) it never
cuts a word in half, which matters for embedding quality, and (2) overlap
correctness is trivial to reason about and verify - "chunk_overlap" words
at the tail of chunk N reappear at the head of chunk N+1, exactly, every
time (the Stage 5 verification prompt asks to confirm overlap "matches the
configured setting, not zero, not full duplication" - this makes that a
literal word-count check, not an approximation).

chunk_size / chunk_overlap are both measured in words, not characters.
"""


def chunk_text(text: str, chunk_size: int = 150, chunk_overlap: int = 30) -> list[str]:
    if chunk_overlap >= chunk_size:
        raise ValueError("chunk_overlap must be smaller than chunk_size")

    words = text.split()
    if not words:
        return []

    total = len(words)
    if total <= chunk_size:
        return [" ".join(words)]

    chunks: list[str] = []
    step = chunk_size - chunk_overlap
    start = 0
    while start < total:
        end = min(start + chunk_size, total)
        chunks.append(" ".join(words[start:end]))
        if end >= total:
            break
        start += step

    return chunks
