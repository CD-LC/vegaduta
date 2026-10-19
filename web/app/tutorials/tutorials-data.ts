// ---------------------------------------------------------------------------
// /tutorials hub — page data (2026-08-14).
//
// Grounding notes (no-fiction rule, see lib/seo-hub.ts): every claim below maps
// to shipped code on main — WhatsApp Meta Cloud API / Twilio modes,
// Telegram per-tenant bot tokens with automatic webhook registration, the
// one-sentence agent builder and /try sandbox, the MCP registry screen
// (cached official-catalog browse + custom STDIO/SSE/streamable-HTTP
// registration, encrypted credentials, admin review, liveness tracking,
// STDIO sandboxing and command allowlist, SSRF guards), the workflow engine
// (240+ recipes, 11 channel triggers, approval nodes), the 14-provider BYOK
// credential screen with live validation and model catalog, and the voice
// stack (Whisper/Sarvam STT, ElevenLabs/Sarvam TTS, Twilio voice with DTMF,
// LiveKit rooms with optional recording).
//
// Content sync 2026-10-03. Corrected: "Temporal-backed" execution (Temporal
// is opt-in and off by default), "22 languages" as a reply-language claim
// (22 is the count of Indian INTERFACE languages, of 30 locales in
// lib/i18n-config.ts), a leftover "13 providers", "~90 recipes", and the
// Home Assistant connector mentions (do-not-claim list). The workflow
// tutorial gained expression / pin / draft-run steps (workflowCanvas.pin* in
// messages/en.json, help/workflow-expressions). Two new tutorials, with every
// button and tab name taken from messages/en.json:
//   debug-an-agent-with-agent-lab  agentLab.* keys, components/agents/lab
//   add-an-agent-skill             skillsPage.* keys, app/skills
// ---------------------------------------------------------------------------

import type { HubPage } from "../lib/seo-hub";
import { SETUP_TUTORIALS } from "./tutorials-data-setup";
import { ADVANCED_TUTORIALS } from "./tutorials-data-advanced";

export const TUTORIALS: HubPage[] = [
  ...SETUP_TUTORIALS,
  ...ADVANCED_TUTORIALS,
];
