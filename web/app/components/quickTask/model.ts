import { MessageCircle, MessageSquareText, Phone, FileText } from "lucide-react";

// Quick Task - direct feedback: "let them type what they want without
// worrying about the workflow, on button click it should get executed
// either through whatsapp, sms or on giving a call. thats it."
//
// Rebuilt this session (task #470) to pixel-match the AXON design handoff
// (Quick Task.dc.html) - centered glass input card with a violet ambient
// glow, quick-fill example chips, an "AXON is understanding your request…"
// live-analysis indicator, and a bordered plan card once the agent/tools
// are known. See the "Screen 4 - Quick Task" block in globals.css for the
// full class list and a note on the one deliberate deviation from the
// mockup: its "RECURRING JOB DETECTED" plan (trigger detection, a numbered
// multi-step plan, a token/runtime estimate, "Save as workflow") has no
// backend behind it anywhere in QuickTaskService/QuickTaskController - the
// real preview endpoint only ever returns an agent name and a flat tool
// list, and run is single-shot with no persistence or schedule concept.
// That section is replaced here with the same card language (bordered
// panel, icon rows, mono labels) populated with the real preview/confirm
// data this screen has always had, rather than fabricating a capability
// that doesn't exist.
//
// QuickTaskController.java auto-selects the tenant's agent (already
// carrying automatic tool/MCP selection - see AgentToolProvider/
// ToolRelevanceSelector) and runs it synchronously, then dispatches the
// result via whichever channel was picked. Run still opens an explicit
// confirmation step restating the detected agent/tools and delivery
// channel - nothing runs until that's been reviewed and confirmed.
// #916 follow-up ("there should be option to set the output should be put
// to logs, not always call, whatsapp and sms") - LOG runs the task exactly
// the same way (agent picked, tools called, answer generated) but skips
// dispatch entirely - nothing goes to WhatsApp/SMS/phone, the result just
// sits in this task's own log/history (same GET /api/quick-task/{taskId}
// and /quick-task/history every other run already lands in). See
// QuickTaskChannel#LOG's javadoc - it's also the one channel that doesn't
// require a phone number on file.
export const CHANNELS = [
  { key: "WHATSAPP", labelKey: "channelWhatsapp", icon: MessageCircle },
  { key: "SMS", labelKey: "channelSms", icon: MessageSquareText },
  { key: "CALL", labelKey: "channelCall", icon: Phone },
  { key: "LOG", labelKey: "channelLogOnly", icon: FileText },
];

// Quick-fill examples - real affordances (click fills the textarea with
// this exact string, nothing more), kept to things the real single-shot
// agent run can actually do: summarize, draft, translate. The mockup's own
// example ("Set up a daily deliveries report") implies scheduling, which
// this feature doesn't support, so it isn't reproduced here.
export const EXAMPLES = [
  { key: "example1", featured: false },
  { key: "example2", featured: false },
  { key: "example3", featured: true },
];

// Shape of one QuickTaskController#runStream SSE event, decoded by
// api.ts's runQuickTaskStream() and handed to its onEvent callback (that
// parameter is typed `any` there, so the callback below needs its own
// annotation to avoid an implicit any on `evt`).
export type QuickTaskStreamEvent =
  // providerName/model ride started+done on newer core builds so the
  // screen can name the engine that actually powered the run, not just the
  // agent. Optional - an older backend simply omits them and the chip
  // falls back to whatever the preview disclosed.
  | {
      type: "started";
      sessionId: string;
      agentId?: string;
      agentName?: string;
      providerName?: string;
      model?: string;
    }
  | {
      type: "tool_call";
      toolName?: string;
      toolInput?: string;
      toolOutput?: string;
      errorMessage?: string;
      durationMs?: number;
    }
  | {
      type: "done";
      resultText: string;
      deliveryNote?: string;
      channel: string;
      providerName?: string;
      model?: string;
    }
  | { type: "error"; message?: string };

/** The engine a run resolved to (provider + model), when core reports it. */
export type RunPower = { providerName?: string | null; model?: string | null };
