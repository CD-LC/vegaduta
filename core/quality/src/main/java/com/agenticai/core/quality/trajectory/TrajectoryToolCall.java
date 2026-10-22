package com.agenticai.core.quality.trajectory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * One tool call of an agent's run, in call order: which tool, with which arguments, and whether the
 * call actually ran ({@code ok = false} for a call that was refused, blocked, withheld or failed).
 *
 * @param args the tool's arguments as parsed JSON; {@link MissingNode} when the recorded input was
 *     not JSON
 */
public record TrajectoryToolCall(String tool, JsonNode args, boolean ok) {

    public TrajectoryToolCall {
        tool = tool == null ? "" : tool;
        args = args == null ? MissingNode.getInstance() : args;
    }
}
