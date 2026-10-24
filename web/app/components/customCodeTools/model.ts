// Types, starter templates and pure helpers for CustomCodeToolsPanel. See the header
// comment in ../CustomCodeToolsPanel.tsx for the behaviours this panel depends on.

export type CustomCodeToolLanguage = "JAVA" | "PYTHON" | "JAVASCRIPT" | "TYPESCRIPT";
export type CustomCodeToolStatus = "DRAFT" | "VALIDATED" | "REJECTED";

export interface CustomCodeTool {
  id: string;
  toolName: string;
  displayName: string;
  description: string;
  language: CustomCodeToolLanguage;
  sourceCode: string;
  inputSchemaJson: string | null;
  outputDescription: string | null;
  enabled: boolean;
  status: CustomCodeToolStatus;
  validationError: string | null;
  timeoutMs: number;
  memoryMb: number;
  version: number;
  /** Vault secret NAMES this tool may receive. Never values - see ToolSecretGrants. */
  allowedSecretNames?: string[];
  createdAt: string;
  updatedAt: string;
}

export interface TestResult {
  ok: boolean;
  result: any;
  stdout: string;
  error: string;
  errorType: string;
  durationMs: number;
}

export interface FormState {
  toolName: string;
  displayName: string;
  description: string;
  language: CustomCodeToolLanguage;
  sourceCode: string;
  inputSchemaJson: string;
  outputDescription: string;
  timeoutMs: number;
  memoryMb: number;
}

// The sandbox enforces this exact contract (a class named Tool with a
// static run(Map), / a module-level run(input)), so these are not "example
// code" that can drift - they are the shape the validator checks for. Keep
// them byte-for-byte in sync with the sandbox runner if that contract ever
// changes.
export const JAVA_TEMPLATE = `// Your tool must be a class named Tool with a static run(Map) method.
// Return any JSON-serializable value. No file, network, process or
// reflection access is available - this runs in a locked-down sandbox.
import java.util.Map;
import java.util.List;

public class Tool {
    public static Object run(Map<String, Object> input) {
        String name = String.valueOf(input.getOrDefault("name", "world"));
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
`;

export const PYTHON_TEMPLATE = `# Your tool must define a function named run(input) taking a dict.
# Return any JSON-serializable value. No file, network, process or
# import-of-os access is available - this runs in a locked-down sandbox.

def run(input):
    name = input.get("name", "world")
    return {"greeting": f"Hello, {name}!"}
`;

export const JAVASCRIPT_TEMPLATE = `// Your tool must define a top-level function named run(input) taking a
// plain object parsed from the tool call's JSON. Return any JSON-serializable
// value. There is no module system, no process environment and no network -
// this runs in a locked-down sandbox; import/require/process/eval/fetch are
// rejected outright.

function run(input) {
  const name = input.name ?? "world";
  return { greeting: \`Hello, \${name}!\` };
}
`;

export const TYPESCRIPT_TEMPLATE = `// Same contract as the JavaScript template, with type annotations allowed
// (the runtime strips them - enums and other type-generating syntax are not
// supported).

interface ToolInput {
  name?: string;
}

function run(input: ToolInput): { greeting: string } {
  const name = input.name ?? "world";
  return { greeting: \`Hello, \${name}!\` };
}
`;

export const TEMPLATES: Record<CustomCodeToolLanguage, string> = {
  JAVA: JAVA_TEMPLATE,
  PYTHON: PYTHON_TEMPLATE,
  JAVASCRIPT: JAVASCRIPT_TEMPLATE,
  TYPESCRIPT: TYPESCRIPT_TEMPLATE,
};

export const LANGUAGE_ORDER: CustomCodeToolLanguage[] = ["JAVA", "PYTHON", "JAVASCRIPT", "TYPESCRIPT"];

/** Language names are proper nouns - shown verbatim, not translated. */
export const LANGUAGE_LABELS: Record<CustomCodeToolLanguage, string> = {
  JAVA: "Java",
  PYTHON: "Python",
  JAVASCRIPT: "JavaScript",
  TYPESCRIPT: "TypeScript",
};

// ---------------------------------------------------------------------------
// "Ask AI to generate the tool" — supports all three of the platform's ways
// to run an LLM call (edge on-device, platform-hosted FREE tier, BYOK):
// generateToolOnDevice (lib/edge/codegen.ts) is tried first when on-device
// mode is opted in and a local model is ready; anything short of a
// validated draft falls back to api.generateCustomCodeTool, whose backend
// (CodeToolGenerationService) itself picks FREE-tier vs BYOK per tenant. The
// server is always the one that certifies a draft as valid — see
// lib/edge/codegen.ts's header comment for why edge never self-certifies.
// ---------------------------------------------------------------------------

export interface GenerationValidationError {
  message?: string;
  line?: number;
  [key: string]: unknown;
}

export interface CodeToolGenerationResponse {
  sourceCode: string;
  valid: boolean;
  errors: GenerationValidationError[];
  warnings: string[];
  sourceSha256?: string;
  language: CustomCodeToolLanguage;
}

export interface GenTemplatePreset {
  id: string;
  labelKey: string;
  /** null = "write your own" — value comes from the freeform textarea. */
  template: string | null;
}

export const GEN_TEMPLATE_PRESETS: GenTemplatePreset[] = [
  { id: "general", labelKey: "askAiTemplatePresetGeneral", template: "General purpose utility" },
  {
    id: "transform",
    labelKey: "askAiTemplatePresetTransform",
    template: "Data transformation - reshape or convert the input",
  },
  {
    id: "validation",
    labelKey: "askAiTemplatePresetValidation",
    template: "Validation rule - check the input against conditions",
  },
  {
    id: "calculation",
    labelKey: "askAiTemplatePresetCalculation",
    template: "Calculation - compute a derived numeric result",
  },
  { id: "custom", labelKey: "askAiTemplatePresetCustom", template: null },
];

// Mirrors the backend's own constraint on toolName. Checked client-side
// purely so the person finds out while typing instead of after a round
// trip; the server stays the authority.
export const TOOL_NAME_PATTERN = /^[a-z][a-z0-9_]{2,63}$/;

// Defaults for a brand-new tool. Modest on purpose - a tool the model calls
// mid-conversation is on the user's latency budget, so the starting point
// should be "fast and small", with the fields right there to raise if a
// particular tool genuinely needs it.
export const DEFAULT_TIMEOUT_MS = 5000;
export const DEFAULT_MEMORY_MB = 128;

export const EMPTY_FORM: FormState = {
  toolName: "",
  displayName: "",
  description: "",
  language: "JAVA",
  sourceCode: JAVA_TEMPLATE,
  inputSchemaJson: "",
  outputDescription: "",
  timeoutMs: DEFAULT_TIMEOUT_MS,
  memoryMb: DEFAULT_MEMORY_MB,
};

// The backend's exact message when the deployment was started without the
// sandbox. Matched on the env-var name rather than the whole sentence so a
// copy tweak on the backend doesn't silently turn this page's clear empty
// state back into a raw error string.
export const FEATURE_DISABLED_MARKER = "TOOL_SANDBOX_ENABLED";

export function statusHue(status: CustomCodeToolStatus): string {
  if (status === "VALIDATED") return "var(--state-success)";
  if (status === "REJECTED") return "var(--state-error)";
  return "var(--state-idle)";
}

export function prettyJson(value: any): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    // Circular or otherwise unserialisable - never let a display helper
    // take the panel down.
    return String(value);
  }
}
