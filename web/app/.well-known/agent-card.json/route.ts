import { fetchAgentCard } from "./proxy";

// Canonical A2A Agent Card location (0.3+). See ./proxy.ts for the whole
// story - this file is only the verb binding Next.js requires.
export async function GET() {
  return fetchAgentCard();
}
