"use client";

// /ui-kit/console-preview/agent-playground - the workbench playground over a sample agent, with its "Run
// settings" drawer open and a transcript that shows how replies end: cut off by the output limit (Continue),
// stopped by the tool-call limit (a link to raise it), and stopped by the provider's safety system. The drawer's
// reads are answered by the sample-data mock (components/agents/playground/runControlsMock.ts), so no backend
// is needed. See ../preview-harness.tsx.
import { useState } from "react";
import ConsolePreview from "../preview-harness";
import AgentPlayground from "../../../components/agents/AgentPlayground";
import { PLAYGROUND_STORAGE_PREFIX } from "../../../components/agents/playground/persistence";
import type { PlaygroundAgent, PlaygroundTurn } from "../../../components/agents/playground/types";

const AGENT: PlaygroundAgent = {
  id: "preview-agent",
  name: "Support agent",
  providerName: "OPENAI",
  model: "gpt-4o-mini",
  reasoningDepth: "CAREFUL",
  temperature: 0.4,
  topP: 0.9,
  maxTokens: 1024,
  allowedToolNames: ["web_search"],
};

const TURNS: PlaygroundTurn[] = [
  { role: "user", content: "Explain how refunds work, in full." },
  {
    role: "assistant",
    content: "Refunds go back to the original payment method. The usual steps are: 1) the request is checked, 2) the",
    steps: [
      { type: "run_started", id: 1, origin: "server" },
      { type: "finish", id: 2, origin: "server", details: { finishReason: "length" } },
    ],
    ref: "preview-1",
  },
  { role: "user", content: "Check every open order and email each customer." },
  {
    role: "assistant",
    content: "I checked 25 orders and emailed the first group.",
    steps: [
      { type: "tool_call", id: 3, tool: "send_email", origin: "server" },
      { type: "tool_result", id: 3, tool: "send_email", origin: "server" },
      { type: "guardrail", id: 4, message: "exceeded the maximum of 25 tool calls for a single turn", origin: "server" },
    ],
    ref: "preview-2",
  },
];

// Seeded before the playground mounts (its first effect restores the saved transcript), once per page mount.
function seedTranscript() {
  try {
    window.localStorage.setItem(
      PLAYGROUND_STORAGE_PREFIX + AGENT.id,
      JSON.stringify({ sessionId: "preview-session", turns: TURNS, savedAt: Date.now() })
    );
  } catch {
    // Storage blocked: the playground simply opens empty.
  }
  return true;
}

export default function Preview() {
  useState(() => (typeof window !== "undefined" ? seedTranscript() : false));
  return (
    <ConsolePreview label="agent-playground">
      <main style={{ padding: 32, height: "100vh" }}>
        <AgentPlayground agent={AGENT} initialRunSettingsOpen />
      </main>
    </ConsolePreview>
  );
}
