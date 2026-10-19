import { CalendarCheck, HeartPulse, Stethoscope } from "lucide-react";
import { makeNode, makeEdge, classifier } from "./graphHelpers";
import type { TemplateGraph } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

function appointmentRouterGraph(): TemplateGraph {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WHATSAPP" });
  const intent = classifier(
    360,
    160,
    "What does the patient want?",
    [
      { handle: "booking", label: "New booking", description: "Wants to book a new appointment." },
      {
        handle: "change",
        label: "Reschedule or cancel",
        description: "Wants to move or cancel an existing appointment.",
      },
      {
        handle: "question",
        label: "General question",
        description: "Asks about hours, location, fees, preparation or anything else.",
      },
    ],
    "question",
    "Administrative requests only - never classify or answer clinical questions."
  );
  const booking = makeNode("agent", 640, 40, "Booking assistant");
  const change = makeNode("agent", 640, 160, "Reschedule & cancellation assistant");
  const question = makeNode("agent", 640, 280, "Front-desk questions assistant");
  const out = makeNode("output", 920, 160, "Reply to patient", { channel: "whatsapp" });
  return {
    nodes: [trigger, intent, booking, change, question, out],
    edges: [
      makeEdge(trigger.id, intent.id),
      makeEdge(intent.id, booking.id, "booking"),
      makeEdge(intent.id, change.id, "change"),
      makeEdge(intent.id, question.id, "question"),
      makeEdge(booking.id, out.id),
      makeEdge(change.id, out.id),
      makeEdge(question.id, out.id),
    ],
  };
}

// Workflow templates: Healthcare & clinics.
export const healthcareAndClinicsTemplates: WorkflowTemplate[] = [
  {
    key: "clinic-appointment-reminder-workflow",
    category: "Healthcare & clinics",
    icon: HeartPulse,
    description:
      "Drafts an appointment reminder from a scheduling event - no clinical content - and sends it to the patient's WhatsApp number (the event's \"phone\" field).",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Appointment reminder drafter" },
      {
        type: "output",
        label: "Send reminder on WhatsApp",
        config: { channel: "whatsapp", whatsappTo: "{{ $json.phone }}" },
      },
    ],
  },
  {
    key: "patient-intake-faq-workflow",
    category: "Healthcare & clinics",
    icon: Stethoscope,
    description: "Answers patient intake and policy questions from the clinic's own documents.",
    triggerChannel: "WHATSAPP",
    steps: [
      { type: "knowledge", label: "Clinic intake & policy documents" },
      { type: "agent", label: "Patient intake assistant" },
      { type: "output", label: "Reply to patient", config: { channel: "whatsapp" } },
    ],
  },
  {
    key: "appointment-request-router-workflow",
    category: "Healthcare & clinics",
    icon: CalendarCheck,
    description:
      "Sorts an inbound WhatsApp message into a new booking, a reschedule/cancellation, or a general question and " +
      "hands each to its own assistant, replying on the same chat. No clinical advice. Assign the three agents.",
    triggerChannel: "WHATSAPP",
    buildGraph: appointmentRouterGraph,
  },
];
