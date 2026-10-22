package com.agenticai.core.agents.decision.dto;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/capabilities/decision/playground} body - free-text {@code state} plus a
 * caller-built map of named questions ({@code noul}/{@code choice}/{@code score} shapes, validated
 * and bounded by whichever {@link com.agenticai.core.agents.decision.DecisionModel} the engine
 * routes to). Same wire shape {@code JevPlaygroundRequest} carries on the compat shim.
 */
public record DecisionPlaygroundRequest(String state, Map<String, JsonNode> questions) {}
