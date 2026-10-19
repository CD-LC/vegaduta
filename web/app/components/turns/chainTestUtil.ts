import { GENESIS_HASH, eventHash, sha256Hex } from "./journalChain";
import type { TurnJournalEvent } from "../../lib/api/turns";

const AGENT = "11111111-1111-4111-8111-111111111111";
const TURN = "22222222-2222-4222-8222-222222222222";

export async function buildChain(specs: Array<{ type: string; summary: string; payload?: string }>): Promise<TurnJournalEvent[]> {
  const out: TurnJournalEvent[] = [];
  let prev = GENESIS_HASH;
  for (let i = 0; i < specs.length; i++) {
    const e: TurnJournalEvent = {
      turnId: TURN,
      agentId: AGENT,
      seq: i,
      type: specs[i].type,
      payloadHash: (await sha256Hex(specs[i].payload ?? `p${i}`))!,
      summary: specs[i].summary,
      prevHash: prev,
      hash: "",
      createdAt: new Date(Date.UTC(2026, 9, 15, 10, 0, 0, i * 250)).toISOString(),
    };
    e.hash = (await eventHash(e))!;
    prev = e.hash;
    out.push(e);
  }
  return out;
}

export const SPECS = [
  { type: "INTAKE", summary: "messageChars=12 attachments=0 tools=1" },
  { type: "MODEL_CALL", summary: "provider=openai model=gpt route=fast" },
  { type: "TOOL_CALL", summary: "tool=lookup status=ok resultSha=abc ms=120" },
  { type: "COMMIT", summary: "status=completed answerChars=40" },
];

