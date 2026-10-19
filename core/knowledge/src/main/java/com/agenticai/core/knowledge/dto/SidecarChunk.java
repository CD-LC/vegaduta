package com.agenticai.core.knowledge.dto;

import java.util.List;

public record SidecarChunk(int index, String text, List<Double> embedding) {
}
