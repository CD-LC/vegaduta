/** Simulation persona (agent quality simulations). */
export interface SimulationPersona {
  key: string;
  name: string;
  description: string;
  objective: string;
  builtIn: boolean;
}

/** Simulation session (agent quality simulations). */
export interface SimulationSession {
  id: string;
  agentId: string;
  personaKeys: string[];
  maxTurns: number;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "CANCELLED";
  budgetUsd: number | null;
  cancelRequested: boolean;
  createdBy: string | null;
  createdAt: string;
  completedAt: string | null;
}

/** One persona run inside a simulation session. */
export interface SimulationRun {
  id: string;
  sessionId: string;
  personaKey: string;
  personaName: string;
  status: "QUEUED" | "RUNNING" | "PASSED" | "FAILED" | "ERROR" | "CANCELLED";
  termination: string | null;
  verdict: string | null;
  verdictReason: string | null;
  turnCount: number;
  costUsd: number | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}
