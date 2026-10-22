package com.agenticai.core.agents.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * A playground experiment: free-text {@code state} plus a caller-built map of named questions
 * ({@code noul}/{@code choice}/{@code score} shapes, validated and bounded in {@link
 * com.agenticai.core.agents.decision.JevDecisionService#playground}).
 */
public record JevPlaygroundRequest(String state, Map<String, JsonNode> questions) {}
