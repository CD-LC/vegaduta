package com.agenticai.core.knowledge.dto;

import java.util.List;

public record SidecarEmbedQueryResponse(String model, int dimensions, List<Double> embedding) {
}
