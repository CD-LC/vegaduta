"use client";

import { useCallback, useState } from "react";
import { Loader2 } from "lucide-react";
import type { UiNode, UiSpec } from "./schema";
import SafeModelImage from "../SafeModelImage";
import ApiCallPanel from "./ApiCallPanel";
import { useTranslations } from "../../lib/i18n";

/**
 * Renders a validated {@link UiSpec} (see schema.ts for the contract) as real
 * React elements. Security posture lives in the schema, not here: by the time
 * a spec reaches this component it only contains whitelisted node types,
 * whitelisted props, http(s)-only URLs and sendMessage-only actions. This
 * component's own rules:
 *  - no dangerouslySetInnerHTML, no eval - every prop lands on a real element
 *  - unknown node types render an explicit fallback card (never silently drop)
 *  - interactivity surfaces as onAction(action, values) - the HOST decides
 *    what "send a message back" means (ChatPanel wires it to streamChat).
 */

export type GenerativeUiAction = { type: "sendMessage"; message: string };
export type GenerativeUiActionHandler = (action: GenerativeUiAction, formValues?: Record<string, string>) => void;

function str(v: unknown): string | undefined {
  return typeof v === "string" && v.length ? v : undefined;
}
function num(v: unknown, fallback: number): number {
  return typeof v === "number" && Number.isFinite(v) ? v : fallback;
}

const GAP: Record<string, string> = { sm: "gap-1", md: "gap-3", lg: "gap-5" };
// Theme-reactive semantic tokens (globals.css's --success/--warning/--danger/
// --info triads, which flip with the app's own [data-mode] attribute) instead
// of raw Tailwind palette classes + a `dark:` variant — this app's dark mode
// is switched by data-mode, not the OS prefers-color-scheme media query that
// `dark:` is keyed to, so a plain `dark:text-*` class never actually applies
// here (see ChannelsPanel.tsx / DevicesPanel.tsx for the same var(--*) pattern).
const TONE: Record<string, string> = {
  default: "bg-secondary text-secondary-foreground",
  info: "bg-[var(--info-bg)] text-[var(--info-text)]",
  success: "bg-[var(--success-bg)] text-[var(--success-text)]",
  warning: "bg-[var(--warning-bg)] text-[var(--warning-text)]",
  danger: "bg-[var(--danger-bg)] text-[var(--danger-text)]",
};

function Unsupported({ type }: { type: string }) {
    const tHC = useTranslations("hcComponentsGenerativeuiGenerativeUiRenderer");
  return (
    <div className="rounded-lg border border-dashed border-border px-3 py-2 text-xs text-muted-foreground">
      {tHC("s2")} {type}
    </div>
  );
}

function Node({ node, onAction }: { node: UiNode; onAction: GenerativeUiActionHandler }) {
  const p = node.props ?? {};
  const kids = (node.children ?? []).map((c, i) => <Node key={i} node={c} onAction={onAction} />);
  // Only the "button" case reads this, but hooks can't be called
  // conditionally — declared here so every Node instance has its own pending
  // flag, preventing a duplicate sendMessage from a repeat/impatient click.
  const [pending, setPending] = useState(false);
  switch (node.type) {
    case "stack":
      return <div className={`flex flex-col ${GAP[str(p.gap) ?? "md"]}`}>{kids}</div>;
    case "row": {
      const nowrap = p.wrap === false;
      return (
        <div
          className={`flex flex-row items-center ${GAP[str(p.gap) ?? "md"]} ${
            nowrap ? "flex-nowrap overflow-x-auto" : "flex-wrap"
          }`}
        >
          {kids}
        </div>
      );
    }
    case "grid":
      // auto-fit + a minimum column width instead of a fixed repeat(N,...):
      // the requested column count is a hint, but the actual number of
      // visible columns now shrinks on narrow containers (e.g. the chat
      // panel on a phone) instead of squeezing every column onto one row.
      return (
        <div
          className="grid gap-3"
          style={{
            gridTemplateColumns: `repeat(auto-fit, minmax(min(${Math.round(
              240 / Math.max(1, Math.min(num(p.columns, 2), 6))
            )}px, 100%), 1fr))`,
          }}
        >
          {kids}
        </div>
      );
    case "card":
      return (
        <div className="rounded-xl border border-border bg-card p-4">
          {str(p.title) && <h3 className="break-words text-sm font-semibold text-foreground">{p.title as string}</h3>}
          {str(p.subtitle) && (
            <p className="mt-0.5 break-words text-xs text-muted-foreground">{p.subtitle as string}</p>
          )}
          <div className={p.title ? "mt-3" : ""}>{kids}</div>
        </div>
      );
    case "divider":
      return <hr className="border-border" />;
    case "spacer":
      return <div style={{ height: Math.min(num(p.size, 12), 96) }} />;
    case "heading": {
      const level = Math.min(Math.max(num(p.level, 2), 1), 4);
      const cls = level <= 1 ? "text-xl font-bold" : level === 2 ? "text-lg font-semibold" : "text-base font-semibold";
      // A real heading element so it participates in the document's heading
      // outline / screen-reader heading navigation, not just a styled <p>.
      // Offset by 2 so a level-1 spec heading (the common case) nests as an
      // h3 under this generative-UI block's own host page h1/h2, instead of
      // colliding with the page's real top-level headings.
      const Tag = `h${Math.min(level + 2, 6)}` as "h3" | "h4" | "h5" | "h6";
      return <Tag className={`${cls} break-words tracking-tight text-foreground`}>{str(p.text)}</Tag>;
    }
    case "text":
      return (
        <p
          className={`text-sm break-words ${p.muted ? "text-muted-foreground" : "text-foreground"} whitespace-pre-wrap`}
        >
          {str(p.text)}
        </p>
      );
    case "list": {
      const items = Array.isArray(p.items) ? p.items.filter((i): i is string => typeof i === "string") : [];
      const Tag = p.ordered ? "ol" : "ul";
      return (
        <Tag className={`text-sm text-foreground ${p.ordered ? "list-decimal" : "list-disc"} space-y-1 ps-5`}>
          {items.map((i, k) => (
            <li key={k} className="break-words">
              {i}
            </li>
          ))}
        </Tag>
      );
    }
    case "table": {
      const cols = Array.isArray(p.columns) ? p.columns.filter((c): c is string => typeof c === "string") : [];
      const rows = Array.isArray(p.rows) ? p.rows.filter((r): r is unknown[] => Array.isArray(r)) : [];
      return (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/40">
                {cols.map((c, i) => (
                  <th key={i} className="px-3 py-2 text-start font-medium text-foreground">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i} className="border-b border-border/60 last:border-0">
                  {cols.map((_, j) => (
                    <td key={j} className="px-3 py-2 text-foreground">
                      {typeof r[j] === "string" || typeof r[j] === "number" ? String(r[j]) : ""}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }
    case "keyValue": {
      const entries = Array.isArray(p.entries) ? p.entries : [];
      return (
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm">
          {entries.map((e, i) => {
            const pair = e && typeof e === "object" ? (e as Record<string, unknown>) : {};
            return (
              <div key={i} className="contents">
                <dt className="break-words text-muted-foreground">{str(pair.key)}</dt>
                <dd className="break-words text-foreground">{str(pair.value)}</dd>
              </div>
            );
          })}
        </dl>
      );
    }
    case "badge":
      return (
        <span
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${TONE[str(p.tone) ?? "default"]}`}
        >
          {str(p.text)}
        </span>
      );
    case "image":
      return <SafeModelImage src={str(p.src)} alt={str(p.alt) ?? ""} />;
    case "link":
      return (
        <a
          href={str(p.href)}
          target="_blank"
          rel="noopener noreferrer"
          className="break-words text-sm text-primary underline underline-offset-2"
        >
          {str(p.text) ?? str(p.href)}
        </a>
      );
    case "code":
      return (
        <pre className="overflow-x-auto rounded-lg bg-muted p-3 text-xs text-foreground">
          <code>{str(p.code)}</code>
        </pre>
      );
    case "button":
      return (
        <button
          type="button"
          disabled={pending}
          aria-busy={pending || undefined}
          onClick={() => {
            if (!node.action || pending) return;
            setPending(true);
            onAction(node.action);
          }}
          className="rounded-lg bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {pending && <Loader2 className="inline size-3.5 animate-spin" aria-hidden="true" />}
          {pending ? "Sending…" : (str(p.label) ?? "Continue")}
        </button>
      );
    case "form":
      return (
        <FormNode node={node} onAction={onAction}>
          {kids}
        </FormNode>
      );
    case "input": {
      const name = str(p.name) ?? "field";
      const label = str(p.label);
      const fallbackLabel = label ?? str(p.placeholder) ?? name;
      return (
        <label className="flex flex-col gap-1 text-sm">
          {label && <span className="text-xs font-medium text-muted-foreground">{label}</span>}
          <input
            name={name}
            data-genfield={name}
            type={str(p.inputType) ?? "text"}
            placeholder={str(p.placeholder)}
            defaultValue={str(p.defaultValue)}
            required={p.required === true}
            aria-required={p.required === true || undefined}
            aria-label={label ? undefined : fallbackLabel}
            className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
          />
        </label>
      );
    }
    case "textarea": {
      const name = str(p.name) ?? "field";
      const label = str(p.label);
      const fallbackLabel = label ?? str(p.placeholder) ?? name;
      return (
        <label className="flex flex-col gap-1 text-sm">
          {label && <span className="text-xs font-medium text-muted-foreground">{label}</span>}
          <textarea
            name={name}
            data-genfield={name}
            placeholder={str(p.placeholder)}
            defaultValue={str(p.defaultValue)}
            rows={Math.min(num(p.rows, 3), 20)}
            required={p.required === true}
            aria-required={p.required === true || undefined}
            aria-label={label ? undefined : fallbackLabel}
            className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
          />
        </label>
      );
    }
    case "select": {
      const name = str(p.name) ?? "field";
      const label = str(p.label);
      const fallbackLabel = label ?? name;
      const options = Array.isArray(p.options)
        ? p.options.map((o) => (typeof o === "string" ? { value: o, label: o } : (o as Record<string, unknown>)))
        : [];
      return (
        <label className="flex flex-col gap-1 text-sm">
          {label && <span className="text-xs font-medium text-muted-foreground">{label}</span>}
          <select
            name={name}
            data-genfield={name}
            defaultValue={str(p.defaultValue)}
            required={p.required === true}
            aria-required={p.required === true || undefined}
            aria-label={label ? undefined : fallbackLabel}
            className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            {options.map((o, i) => (
              <option key={i} value={str(o.value)}>
                {str(o.label) ?? str(o.value)}
              </option>
            ))}
          </select>
        </label>
      );
    }
    case "checkbox": {
      const name = str(p.name) ?? "field";
      return (
        <label className="flex flex-row items-center gap-2 text-sm text-foreground">
          <input
            name={name}
            data-genfield={name}
            type="checkbox"
            className="accent-primary focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            defaultChecked={p.defaultChecked === true}
          />
          {str(p.label)}
        </label>
      );
    }
    case "apiCall":
      return (
        <ApiCallPanel
          method={str(p.method) ?? "GET"}
          path={str(p.path) ?? ""}
          title={str(p.title)}
          query={p.query && typeof p.query === "object" ? (p.query as Record<string, unknown>) : undefined}
          body={p.body}
        />
      );
    case "__unsupported__":
    default:
      return <Unsupported type={str(p.type) ?? node.type} />;
  }
}

/** Form wrapper: collects descendant data-genfield controls and submits them through the one allowed verb. */
function FormNode({
  node,
  onAction,
  children,
}: {
  node: UiNode;
  onAction: GenerativeUiActionHandler;
  children: React.ReactNode;
}) {
  const p = node.props ?? {};
  const [submitted, setSubmitted] = useState(false);
  const submit = useCallback(
    (e: React.FormEvent<HTMLFormElement>) => {
      e.preventDefault();
      if (submitted) return; // guards a duplicate submit before the reply lands
      setSubmitted(true);
      const values: Record<string, string> = {};
      e.currentTarget
        .querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>("[data-genfield]")
        .forEach((el) => {
          const name = el.getAttribute("data-genfield")!;
          values[name] = el instanceof HTMLInputElement && el.type === "checkbox" ? String(el.checked) : el.value;
        });
      const action = node.action ?? { type: "sendMessage" as const, message: "Form submitted." };
      onAction({ type: "sendMessage", message: `${action.message}\n\n${JSON.stringify(values)}` }, values);
    },
    [node.action, onAction, submitted]
  );
  return (
    <form onSubmit={submit} className="flex flex-col gap-3 rounded-xl border border-border bg-card p-4">
      {str(p.title) && <h3 className="break-words text-sm font-semibold text-foreground">{p.title as string}</h3>}
      {children}
      <button
        type="submit"
        disabled={submitted}
        aria-busy={submitted || undefined}
        className="self-start rounded-lg bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground transition hover:opacity-90 focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {submitted && <Loader2 className="inline size-3.5 animate-spin" aria-hidden="true" />}
        {submitted ? "Sending…" : (str(p.submitLabel) ?? "Submit")}
      </button>
    </form>
  );
}

export function GenerativeUiRenderer({
  spec,
  onAction,
  errors,
}: {
  spec: UiSpec;
  /** Host callback - wires button/form actions to "send this message to the agent". */
  onAction?: GenerativeUiActionHandler;
  errors?: string[];
}) {
    const tHC = useTranslations("hcComponentsGenerativeuiGenerativeUiRenderer");
  const [fired, setFired] = useState(false);
  const handle = useCallback<GenerativeUiActionHandler>(
    (action, values) => {
      setFired(true);
      onAction?.(action, values);
    },
    [onAction]
  );
  return (
    <div className="generative-ui rounded-xl border border-border bg-background/60 p-3">
      {errors && errors.length > 0 && (
        <p className="mb-2 text-xs text-muted-foreground">{tHC("s1")}{errors.length}).</p>
      )}
      <Node node={spec.root} onAction={handle} />
      {fired && <p className="mt-2 text-xs text-muted-foreground">Sent.</p>}
    </div>
  );
}
