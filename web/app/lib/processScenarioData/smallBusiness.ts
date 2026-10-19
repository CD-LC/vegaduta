// Process Theater scenarios for the "Small business" audience. Small-business processes: bookings.
// Assembled, in display order, by ../processScenarios.ts (see its honesty rules).
import { designPrompt, type ProcessScenario } from "./types";

export const bookingsScenario: ProcessScenario = {
  id: "bookings",
  audience: "Small business",
  title: "Bookings and follow-ups on WhatsApp",
  pitch: "The same machinery at shop scale: answer, book, remind, win back, without anyone watching the phone.",
  span: "Always on",
  needs: "WhatsApp, Telegram or Email (no paid plan needed for these)",
  steps: [
    { node: "trigger", label: "Customer messages", tech: "WhatsApp channel trigger", clock: "T+0s" },
    {
      node: "agent",
      label: "Answer and book",
      tech: "Agent grounded in your price list and hours, with the tools you attach",
      clock: "T+5s",
    },
    {
      node: "condition",
      label: "Needs a person?",
      tech: "Condition node hands off complaints and special requests",
      clock: "T+5s",
    },
    {
      node: "loop",
      label: "Remind and follow up",
      tech: "Loop node: reminder before, thank-you after, win-back later",
      clock: "Day 1 to 30",
      waits: true,
    },
    { node: "output", label: "Daily summary to the owner", tech: "What was booked, what needs you", clock: "Daily" },
  ],
  agents: [
    {
      name: "Front Desk",
      role: "Answers questions and takes bookings, grounded in your price list and hours.",
      tools: ["Price list & hours", "WhatsApp / Telegram / Email"],
    },
    {
      name: "Reminder & Follow-up",
      role: "Reminder before, thank-you after, win-back later — on a loop.",
      tools: ["WhatsApp / Telegram / Email"],
    },
    {
      name: "Owner Summariser",
      role: "Sends you the day's bookings and whatever needs a person.",
      tools: ["WhatsApp / Telegram / Email"],
    },
  ],
  connectors: [
    { name: "WhatsApp / Telegram / Email", kind: "channel", detail: "Pick the channel your customers actually use." },
    { name: "Price list & hours", kind: "builtin", detail: "Upload a doc or paste text; the agent answers from it." },
    { name: "Calendar", kind: "custom", detail: "Optional: a small MCP tool over your calendar for real bookings." },
    { name: "Payments", kind: "http", detail: "Optional: HTTP node to your payment provider for deposits." },
  ],
  prompt: designPrompt(
    "customers message a small business on WhatsApp; answer from the price list, take bookings, hand off complaints to a person, send reminders and follow-ups, and summarise the day for the owner"
  ),
};
