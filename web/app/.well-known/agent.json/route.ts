import { fetchAgentCard } from "../agent-card.json/proxy";

// Legacy A2A discovery alias (pre-0.3 clients probe /.well-known/agent.json).
// Same body, same caching and same 503 posture as the canonical
// agent-card.json route next door - one implementation, two paths, so the
// two can never drift.
export async function GET() {
  return fetchAgentCard();
}
