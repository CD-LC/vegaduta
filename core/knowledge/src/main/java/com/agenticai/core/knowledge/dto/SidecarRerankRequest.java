package com.agenticai.core.knowledge.dto;

import java.util.List;

/** Master-Roadmap-2026.md item #2 - see {@code reranking.py}'s module docstring for the cross-encoder rationale. */
public record SidecarRerankRequest(String query, List<String> documents) {
}
