import {
  HardDrive,
  Database,
  Search,
  MessageSquare,
  Globe,
  GitBranch,
  MapPin,
  FolderInput,
  Brain,
  ListChecks,
  Clock,
  Bug,
  BookOpen,
  Flame,
  Mail,
  Send,
  PhoneCall,
  Calendar as CalendarIcon,
  Table as TableIcon,
} from "lucide-react";
import { Github } from "../lucideBrandIcons";

// Maps McpCatalogEntry.iconKey (a stable string from the backend, not a URL)
// to a brand-consistent icon - keeps the catalog free of any external asset
// dependency and lets the icon set change without touching backend data.
// Typed as Record<string, any> (not a precise lucide icon union) because
// this table is indexed by an arbitrary backend-supplied iconKey string in
// several places below (TSC-STRICTCHECK-REPORT-2026-07-19.md's TS7053
// findings) - a plain object-literal type has no index signature, so
// `CATALOG_ICONS[someString]` errors under noImplicitAny without this.
export const CATALOG_ICONS: Record<string, any> = {
  filesystem: HardDrive,
  github: Github,
  database: Database,
  search: Search,
  "message-square": MessageSquare,
  globe: Globe,
  "git-branch": GitBranch,
  "map-pin": MapPin,
  "folder-input": FolderInput,
  brain: Brain,
  "list-checks": ListChecks,
  clock: Clock,
  bug: Bug,
  book: BookOpen,
  flame: Flame,
};

// Built-in Tools (#164) - native platform capabilities, not external MCP
// servers someone has to stand up. Exported so tools/page.js can compute a
// real "N enabled / M available" breadcrumb count without duplicating this
// list. Read-only for now: knowledge/memory attach per-agent already via
// AgentWorkbench's own editors; the send-* ones are reachable from a
// workflow's Output node, not yet as a live-chat tool an agent decides to
// call on its own - stated honestly rather than implied.
export const BUILT_IN_TOOLS = [
  {
    key: "knowledge-search",
    nameKey: "builtinKnowledgeSearchName",
    icon: BookOpen,
    descriptionKey: "builtinKnowledgeSearchDescription",
    availableFromKey: "builtinKnowledgeSearchAvailableFrom",
    // --cat-* accent (design-system contract §2) - knowledge/memory are the
    // "data" family, the send-* tools are "comms".
    cat: "var(--cat-data)",
  },
  {
    key: "long-term-memory",
    nameKey: "builtinLongTermMemoryName",
    icon: Brain,
    descriptionKey: "builtinLongTermMemoryDescription",
    availableFromKey: "builtinLongTermMemoryAvailableFrom",
    cat: "var(--cat-data)",
  },
  {
    key: "send-email",
    nameKey: "builtinSendEmailName",
    icon: Mail,
    descriptionKey: "builtinSendEmailDescription",
    availableFromKey: "builtinSendEmailAvailableFrom",
    cat: "var(--cat-comms)",
  },
  {
    key: "send-telegram",
    nameKey: "builtinSendTelegramName",
    icon: Send,
    descriptionKey: "builtinSendTelegramDescription",
    availableFromKey: "builtinSendTelegramAvailableFrom",
    cat: "var(--cat-comms)",
  },
  {
    key: "send-whatsapp",
    nameKey: "builtinSendWhatsappName",
    icon: PhoneCall,
    descriptionKey: "builtinSendWhatsappDescription",
    availableFromKey: "builtinSendWhatsappAvailableFrom",
    cat: "var(--cat-comms)",
  },
];

// Real, code-derived sub-action tables (core/agents/.../tools/google/*ToolCallback.java)
// - each of these ToolCallbacks really does multiplex exactly these
// named actions with these read/write semantics, so this is safe to show as
// fact rather than the mockup's arbitrary tool names. toolName is the exact
// literal string AgentToolProvider registers and Agent.allowedToolNames
// would contain - used below to cross-reference real agent access.
export const GOOGLE_TOOLS = [
  {
    toolName: "google_drive",
    nameKey: "googleDriveName",
    icon: HardDrive,
    descriptionKey: "googleDriveDescription",
    subActions: [
      { name: "drive.list", mode: "read" },
      { name: "drive.read", mode: "read" },
      { name: "drive.create", mode: "write" },
    ],
  },
  {
    toolName: "google_gmail",
    nameKey: "googleGmailName",
    icon: Mail,
    descriptionKey: "googleGmailDescription",
    subActions: [
      { name: "gmail.list", mode: "read" },
      { name: "gmail.read", mode: "read" },
      { name: "gmail.send", mode: "write" },
    ],
  },
  {
    toolName: "google_calendar",
    nameKey: "googleCalendarName",
    icon: CalendarIcon,
    descriptionKey: "googleCalendarDescription",
    subActions: [
      { name: "calendar.list_upcoming", mode: "read" },
      { name: "calendar.create_event", mode: "write" },
    ],
  },
  // Booking tools: separate tool names (not sub-actions of google_calendar) so
  // moves and cancels are classified irreversible and gated on their own -
  // BuiltInToolClassifications in core/agents.
  {
    toolName: "google_calendar_find_free_slots",
    nameKey: "googleCalendarFreeSlotsName",
    icon: CalendarIcon,
    descriptionKey: "googleCalendarFreeSlotsDescription",
    subActions: [{ name: "calendar.find_free_slots", mode: "read" }],
  },
  {
    toolName: "google_calendar_move_event",
    nameKey: "googleCalendarMoveName",
    icon: CalendarIcon,
    descriptionKey: "googleCalendarMoveDescription",
    subActions: [{ name: "calendar.move_event", mode: "irreversible" }],
  },
  {
    toolName: "google_calendar_cancel_event",
    nameKey: "googleCalendarCancelName",
    icon: CalendarIcon,
    descriptionKey: "googleCalendarCancelDescription",
    subActions: [{ name: "calendar.cancel_event", mode: "irreversible" }],
  },
  {
    toolName: "google_sheets",
    nameKey: "googleSheetsName",
    icon: TableIcon,
    descriptionKey: "googleSheetsDescription",
    subActions: [
      { name: "sheets.read_range", mode: "read" },
      { name: "sheets.write_range", mode: "write" },
      { name: "sheets.append_rows", mode: "write" },
    ],
  },
];
