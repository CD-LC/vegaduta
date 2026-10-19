// Agent Lab - shared prop and data contracts for the overlay and its three
// instrument panels (run graph, prompt knockout, consistency check). Types
// only: nothing here may import a component, so a panel can depend on this
// file without pulling another panel (or @xyflow/react) into its chunk.
import type { ActivityStep } from "../AgentActivityTrace";
import type { LabCapabilities } from "./labApi";

export type LabTab = "graph" | "knockout" | "consistency";

export interface LabAgent {
  id: string;
  name: string;
  systemPrompt?: string | null;
  instructions?: string | null;
  temperature?: number | null;
  providerName?: string | null;
  model?: string | null;
  /** Present at runtime on the agent payload (AgentResponse.java:73,77). */
  responseCachingEnabled?: boolean;
  semanticResponseCachingEnabled?: boolean;
}

export interface LabTurnMetrics {
  elapsedMs: number;
  toolCalls: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCostUsd?: number;
}

/** Structural subset of AgentPlayground's PlaygroundTurn (AgentPlayground.tsx:255-273). */
export interface LabTurn {
  role: "user" | "assistant";
  content: string;
  steps?: ActivityStep[];
  pending?: boolean;
  ref?: string;
  watch?: boolean;
  onDevice?: boolean;
  edgeAttempt?: boolean;
  rescuedFromServer?: boolean;
  attachment?: object | null;
  frames?: readonly unknown[];
  metrics?: LabTurnMetrics;
}

export interface AgentLabProps {
  open: boolean;
  onClose: () => void;
  agent: LabAgent | null;
  sessionId: string | null;
  turns: readonly LabTurn[];
  /** A playground turn is in flight. */
  busy: boolean;
  /** Truncate the playground transcript to its first keepTurnCount entries, then send message as a server turn. */
  onForkRerun: (keepTurnCount: number, message: string) => void;
  /** Abort the in-flight playground turn. */
  onCancelTurn: () => void;
  initialTab?: LabTab;
}

export interface RunGraphPanelProps {
  agent: LabAgent;
  sessionId: string | null;
  turns: readonly LabTurn[];
  busy: boolean;
  onForkRerun: (keepTurnCount: number, message: string) => void;
  onCancelTurn: () => void;
}
export interface KnockoutPanelProps { agent: LabAgent; capabilities: LabCapabilities | null }
export interface ConsistencyPanelProps { agent: LabAgent; turns: readonly LabTurn[]; capabilities: LabCapabilities | null }
