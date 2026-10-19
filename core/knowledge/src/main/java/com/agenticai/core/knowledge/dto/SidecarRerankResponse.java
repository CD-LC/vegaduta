package com.agenticai.core.knowledge.dto;

import java.util.List;

/** Scores are positional - element {@code i} corresponds to request document {@code i}, same order, not sorted. */
public record SidecarRerankResponse(List<Double> scores) {
}
