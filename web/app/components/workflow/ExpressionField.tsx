"use client";

// A config field that takes {{ }} expressions: a Fixed | Expression toggle, a
// textarea with a highlighted mirror layer behind it (no editor library - the
// spec's v1 decision), a custom autocomplete popup fed by upstream-schema and
// the function registry, inline error markers, and a debounced live preview
// line. Every network dependency degrades: with no backend the field is a plain
// text field and the preview says "unavailable".
//
// Swapping the mirror+textarea for CodeMirror later must not change the props.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
  type SyntheticEvent,
} from "react";
import { useTranslations } from "../../lib/i18n";
import { workflowExpressionsApi, type ExpressionDiagnostic } from "../../lib/api/workflowExpressions";
import {
  BARE_PREFIX_LEN,
  applyCompletion,
  buildCompletions,
  completionContextAt,
  hasExpression,
  highlightExpression,
  highlightTemplate,
  insertExpressionAt,
  overlayMarkers,
  quickCheckTemplate,
  referencesData,
  wrapBare,
  findSegments,
  type CompletionItem,
  type Marker,
} from "./expressionLang";
import { previewText, resolvePreview, type PreviewState } from "./expressionPreview";
import { useExpressionFocus, useExpressionFieldFocusRequests } from "./ExpressionFocus";
import { useExpressionFunctions, useUpstreamSchema } from "./useExpressionData";
import "./expression-field.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** Server lint answer for the open graph; the field only trusts entries whose text it still matches. */
export type ExpressionLintState = { diagnostics: ExpressionDiagnostic[]; sent: Record<string, string> } | null;
export const ExpressionLintContext = createContext<ExpressionLintState>(null);

export type ExpressionFieldProps = {
  value: string;
  onChange: (value: string) => void;
  nodeId: string;
  /** Concrete config path, e.g. "url" or "headers[0].value" - the key lint diagnostics use. */
  field: string;
  placeholder?: string;
  multiline?: boolean;
  workflowId: string;
  disabled?: boolean;
  /** Stored text is a whole expression without {{ }} (the condition node). No Fixed mode. */
  bare?: boolean;
  /** Accessible name when no visible <label> points at the field. */
  ariaLabel?: string;
  /** id of the visible label element. */
  ariaLabelledBy?: string;
};

const DEBOUNCE_MS = 400;
const QUICK_KEYS = new Set(["UNCLOSED_BRACES", "EMPTY_EXPRESSION", "UNTERMINATED_STRING"]);

export default function ExpressionField({
  value,
  onChange,
  nodeId,
  field,
  placeholder,
  multiline = false,
  workflowId,
  disabled = false,
  bare = false,
  ariaLabel,
  ariaLabelledBy,
}: ExpressionFieldProps) {
  const t = useTranslations("expressionField") as Translator;
  const uid = useId();
  const listId = `${uid}-list`;
  const previewId = `${uid}-preview`;
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const mirrorRef = useRef<HTMLDivElement | null>(null);

  const [mode, setMode] = useState<"fixed" | "expression">(bare || hasExpression(value) ? "expression" : "fixed");
  const isExpr = bare || mode === "expression";
  const [focused, setFocused] = useState(false);
  const [caret, setCaret] = useState(0);
  const [open, setOpen] = useState(false);
  const [forced, setForced] = useState(false);
  const [active, setActive] = useState(0);

  const valueRef = useRef(value);
  valueRef.current = value;
  const selRef = useRef<{ start: number; end: number } | null>(null);
  const pendingCaret = useRef<number | null>(null);

  const shift = bare ? BARE_PREFIX_LEN : 0;
  const tpl = bare ? wrapBare(value) : value;

  // ---- data sources ------------------------------------------------------
  const { nodes: schemaNodes } = useUpstreamSchema(isExpr && workflowId ? workflowId : undefined, nodeId);
  const functions = useExpressionFunctions();

  // ---- caret restoration after a programmatic edit -------------------------
  useEffect(() => {
    const at = pendingCaret.current;
    const el = areaRef.current;
    if (at == null || !el) return;
    pendingCaret.current = null;
    const pos = Math.max(0, Math.min(at, value.length));
    el.setSelectionRange(pos, pos);
    selRef.current = { start: pos, end: pos };
    setCaret(pos);
  }, [value]);

  const commit = useCallback(
    (next: string, cursor: number) => {
      pendingCaret.current = cursor;
      onChange(next);
    },
    [onChange],
  );

  // ---- autocomplete --------------------------------------------------------
  const ctx = useMemo(
    () => (isExpr && open ? completionContextAt(tpl, caret + shift, forced) : null),
    [isExpr, open, tpl, caret, shift, forced],
  );
  const items = useMemo<CompletionItem[]>(
    () => (ctx ? buildCompletions(ctx, { nodes: schemaNodes, functions }) : []),
    [ctx, schemaNodes, functions],
  );
  const popupOpen = focused && !disabled && items.length > 0;
  const activeIdx = Math.min(active, Math.max(0, items.length - 1));
  const activeItem = popupOpen ? items[activeIdx] : undefined;

  function accept(item: CompletionItem) {
    if (!ctx) return;
    const res = applyCompletion(tpl, ctx, item);
    const nextValue = bare ? res.value.slice(BARE_PREFIX_LEN, res.value.length - BARE_PREFIX_LEN) : res.value;
    commit(nextValue, res.cursor - shift);
    setActive(0);
    setForced(false);
    setOpen(res.reopen);
  }

  // ---- data-picker insertion + jump-to-field -------------------------------
  const insertExpr = useCallback(
    (expr: string) => {
      const v = valueRef.current;
      const sel = selRef.current ?? { start: v.length, end: v.length };
      const base = bare ? wrapBare(v) : v;
      const off = bare ? BARE_PREFIX_LEN : 0;
      const res = insertExpressionAt(base, sel.start + off, sel.end + off, expr);
      const nextValue = bare ? res.value.slice(BARE_PREFIX_LEN, res.value.length - BARE_PREFIX_LEN) : res.value;
      setMode("expression");
      commit(nextValue, res.cursor - off);
      areaRef.current?.focus();
    },
    [bare, commit],
  );

  const focusCtx = useExpressionFocus();
  const register = focusCtx?.register;
  useEffect(() => {
    if (!register || disabled) return undefined;
    return register({ nodeId, field, insert: insertExpr });
  }, [register, nodeId, field, insertExpr, disabled]);
  useExpressionFieldFocusRequests(nodeId, field, () => areaRef.current?.focus());

  // ---- markers -------------------------------------------------------------
  const lintState = useContext(ExpressionLintContext);
  const [preview, setPreview] = useState<PreviewState>({ kind: "idle" });
  const [pending, setPending] = useState(false);

  const serverDiags = useMemo(() => {
    if (!lintState) return [];
    const sent = lintState.sent[`${nodeId}\u0000${field}`];
    if (sent === undefined || sent !== value) return [];
    return lintState.diagnostics.filter((d) => d.nodeId === nodeId && d.field === field);
  }, [lintState, nodeId, field, value]);

  const quick = useMemo(() => (isExpr ? quickCheckTemplate(tpl) : []), [isExpr, tpl]);

  const markers = useMemo<Marker[]>(() => {
    if (!isExpr) return [];
    const out: Marker[] = [];
    for (const d of quick) {
      if (bare && (d.code === "EMPTY_EXPRESSION" || d.code === "UNCLOSED_BRACES")) continue;
      out.push({ start: d.start - shift, end: d.end - shift, severity: d.severity });
    }
    // Core lints the wrapped "{{ expr }}" form for bare fields, so its offsets carry the same prefix as the preview's.
    for (const d of serverDiags) out.push({ start: Math.max(0, d.start - shift), end: Math.max(0, d.end - shift), severity: d.severity });
    if (preview.kind === "error") out.push({ start: preview.start - shift, end: preview.end - shift, severity: "error" });
    return out;
  }, [isExpr, quick, serverDiags, preview, bare, shift]);

  const spans = useMemo(() => {
    if (!isExpr) return [];
    const base = bare ? highlightExpression(value) : highlightTemplate(value);
    return overlayMarkers(base, markers, value.length);
  }, [isExpr, bare, value, markers]);

  // ---- live preview --------------------------------------------------------
  useEffect(() => {
    const worth = isExpr && (bare ? value.trim() !== "" : hasExpression(value));
    if (!worth || !workflowId) {
      setPreview(worth ? { kind: "unavailable" } : { kind: "idle" });
      setPending(false);
      return undefined;
    }
    setPending(true);
    let cancelled = false;
    const timer = setTimeout(() => {
      const template = bare ? wrapBare(value) : value;
      void resolvePreview(
        (req) => workflowExpressionsApi.evaluateExpression(workflowId, req),
        template,
        nodeId,
        referencesData(template),
      )
        .catch((): PreviewState => ({ kind: "unavailable" }))
        .then((state) => {
          if (cancelled) return;
          setPreview(state);
          setPending(false);
        });
    }, DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [isExpr, bare, value, workflowId, nodeId]);

  // ---- handlers ------------------------------------------------------------
  function syncSelection() {
    const el = areaRef.current;
    if (!el) return;
    selRef.current = { start: el.selectionStart, end: el.selectionEnd };
    setCaret(el.selectionStart);
  }

  function onInput(e: ChangeEvent<HTMLTextAreaElement>) {
    const el = e.target;
    let next = el.value;
    let at = el.selectionStart;
    const prev = valueRef.current;
    // Typing the second "{" of "{{" closes it: "{{ | }}". Only for a single inserted "{" on an unclosed pair.
    if (
      !bare &&
      next.length === prev.length + 1 &&
      next[at - 1] === "{" &&
      next.slice(0, at - 1) + next.slice(at) === prev &&
      next[at - 2] === "{" &&
      next[at - 3] !== "{"
    ) {
      const seg = findSegments(next).find((s) => s.start === at - 2);
      if (seg && !seg.closed) {
        next = `${next.slice(0, at)}  }}${next.slice(at)}`;
        at += 1;
      }
    }
    if (next !== el.value || at !== el.selectionStart) {
      pendingCaret.current = at;
    }
    if (!bare && mode === "fixed" && hasExpression(next)) setMode("expression");
    selRef.current = { start: at, end: at };
    setCaret(at);
    setActive(0);
    setForced(false);
    setOpen(true);
    onChange(next);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (popupOpen) {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActive((activeIdx + 1) % items.length);
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActive((activeIdx - 1 + items.length) % items.length);
        return;
      }
      if ((e.key === "Enter" || e.key === "Tab") && !e.shiftKey && activeItem) {
        e.preventDefault();
        accept(activeItem);
        return;
      }
      if (e.key === "Escape") {
        // Close the popup only; the drawer must not also see this Escape.
        e.preventDefault();
        e.stopPropagation();
        setOpen(false);
        setForced(false);
        return;
      }
    }
    if (isExpr && e.key === " " && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      setForced(true);
      setOpen(true);
      setActive(0);
      return;
    }
    if (!multiline && e.key === "Enter") e.preventDefault();
  }

  function onPaste() {
    // A paste that brings {{ }} with it is an expression, whatever mode the field was in.
    setTimeout(() => {
      if (!bare && hasExpression(areaRef.current?.value)) setMode("expression");
    }, 0);
  }

  function chooseMode(next: "fixed" | "expression") {
    if (disabled || next === mode) return;
    setMode(next);
    if (next === "expression" && value === "" && !bare) {
      commit("{{  }}", 3);
    }
    areaRef.current?.focus();
  }

  const onScroll = (e: SyntheticEvent<HTMLTextAreaElement>) => {
    const m = mirrorRef.current;
    if (m) {
      m.scrollTop = e.currentTarget.scrollTop;
      m.scrollLeft = e.currentTarget.scrollLeft;
    }
  };

  // ---- derived view --------------------------------------------------------
  const firstMarkerMessage = useMemo(() => {
    const d = serverDiags.find((x) => x.severity === "error") ?? serverDiags[0];
    if (d) return { severity: d.severity, text: d.message };
    const q = quick.find((x) => !(bare && (x.code === "EMPTY_EXPRESSION" || x.code === "UNCLOSED_BRACES")));
    if (q) return { severity: q.severity, text: QUICK_KEYS.has(q.code) ? t(`quick_${q.code}`) : q.message };
    return null;
  }, [serverDiags, quick, bare, t]);

  const rows = multiline ? 3 : 1;
  const minHeight = `calc(${rows} * var(--wfx-ex-lh) + 2 * var(--wfx-ex-py))`;

  return (
    <div className="wfx-ex" data-mode={isExpr ? "expression" : "fixed"} data-disabled={disabled ? "true" : undefined}>
      {!bare && (
        <div className="wfx-ex-toggle" role="group" aria-label={t("modeGroup")}>
          <button
            type="button"
            className="wfx-ex-tbtn"
            aria-pressed={!isExpr}
            disabled={disabled}
            onClick={() => chooseMode("fixed")}
          >
            {t("modeFixed")}
          </button>
          <button
            type="button"
            className="wfx-ex-tbtn"
            aria-pressed={isExpr}
            disabled={disabled}
            onClick={() => chooseMode("expression")}
          >
            {t("modeExpression")}
          </button>
        </div>
      )}

      <div className="wfx-ex-box" data-focused={focused ? "true" : undefined} style={{ minHeight }}>
        <div className="wfx-ex-mirror" ref={mirrorRef} aria-hidden="true" data-sizer={isExpr ? undefined : "true"}>
          {isExpr
            ? spans.map((s, i) => (
                <span key={i} className={`wfx-tok wfx-tok-${s.kind}`} data-marker={s.marker}>
                  {value.slice(s.start, s.end)}
                </span>
              ))
            : value}
          {/* A trailing newline would collapse; keep the last (empty) line sized. */}
          {"​"}
          {value.endsWith("\n") ? "\n" : ""}
        </div>
        <textarea
          ref={areaRef}
          className="wfx-ex-area"
          value={value}
          rows={rows}
          spellCheck={false}
          autoComplete="off"
          autoCapitalize="off"
          disabled={disabled}
          placeholder={placeholder}
          role="combobox"
          aria-label={ariaLabelledBy ? undefined : ariaLabel ?? field}
          aria-labelledby={ariaLabelledBy}
          aria-autocomplete="list"
          aria-haspopup="listbox"
          aria-expanded={popupOpen}
          aria-controls={popupOpen ? listId : undefined}
          aria-activedescendant={popupOpen ? `${listId}-${activeIdx}` : undefined}
          aria-describedby={isExpr ? previewId : undefined}
          aria-invalid={markers.some((m) => m.severity === "error") || undefined}
          data-testid={`expr-${field}`}
          onChange={onInput}
          onKeyDown={onKeyDown}
          onKeyUp={syncSelection}
          onClick={syncSelection}
          onSelect={syncSelection}
          onScroll={onScroll}
          onPaste={onPaste}
          onFocus={() => {
            setFocused(true);
            focusCtx?.markActive(nodeId, field);
            syncSelection();
          }}
          onBlur={() => {
            syncSelection();
            setFocused(false);
            setOpen(false);
            setForced(false);
          }}
        />

        {popupOpen && (
          <div className="wfx-ex-pop">
            <ul className="wfx-ex-list" id={listId} role="listbox" aria-label={t("suggestions")}>
              {items.map((it, i) => (
                <li
                  key={`${it.kind}:${it.label}`}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === activeIdx}
                  className="wfx-ex-opt"
                  data-active={i === activeIdx ? "true" : undefined}
                  // mousedown (not click) so the textarea keeps focus and the popup survives.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    accept(it);
                  }}
                  onMouseEnter={() => setActive(i)}
                >
                  <span className="wfx-ex-kind" data-kind={it.kind}>
                    {t(`kind_${it.kind}`)}
                  </span>
                  <span className="wfx-ex-label">{it.label}</span>
                  {it.detail && <span className="wfx-ex-detail">{it.detail}</span>}
                </li>
              ))}
            </ul>
            {activeItem && (activeItem.doc || activeItem.example) && (
              <div className="wfx-ex-doc">
                {activeItem.detail && activeItem.kind === "function" && <code className="wfx-ex-sig">{activeItem.detail}</code>}
                {activeItem.doc && <p>{activeItem.doc}</p>}
                {activeItem.example && <code className="wfx-ex-eg">{activeItem.example}</code>}
              </div>
            )}
          </div>
        )}
      </div>

      {isExpr && (
        <div id={previewId} className="wfx-ex-preview" role="status" aria-live="polite" data-state={preview.kind} data-pending={pending ? "true" : undefined}>
          <PreviewLine preview={preview} fallback={firstMarkerMessage} t={t} hasBraces={bare ? value.trim() !== "" : hasExpression(value)} />
        </div>
      )}
    </div>
  );
}

function TypeChip({ type, t }: { type: string; t: Translator }) {
  const known = ["string", "number", "boolean", "object", "array", "null"].includes(type);
  return <span className="wfx-ex-type">{known ? t(`type_${type}`) : type}</span>;
}

function PreviewLine({
  preview,
  fallback,
  t,
  hasBraces,
}: {
  preview: PreviewState;
  fallback: { severity: "error" | "warning"; text: string } | null;
  t: Translator;
  hasBraces: boolean;
}): ReactNode {
  if (preview.kind === "error") {
    return (
      <span className="wfx-ex-msg" data-severity="error">
        {preview.message}
      </span>
    );
  }
  if (fallback && fallback.severity === "error") {
    return (
      <span className="wfx-ex-msg" data-severity="error">
        {fallback.text}
      </span>
    );
  }
  if (preview.kind === "ok") {
    const text = previewText(preview.rendered);
    return (
      <>
        <span className="wfx-ex-label-result">{t("previewResult")}</span>
        <TypeChip type={preview.type} t={t} />
        <span className="wfx-ex-value" title={preview.rendered}>
          {text === "" ? t("previewEmpty") : text}
        </span>
        <span className="wfx-ex-src">{preview.source === "pinned" ? t("fromPinned") : t("fromLastRun")}</span>
        {fallback && <span className="wfx-ex-msg" data-severity={fallback.severity}>{fallback.text}</span>}
      </>
    );
  }
  if (fallback) {
    return (
      <span className="wfx-ex-msg" data-severity={fallback.severity}>
        {fallback.text}
      </span>
    );
  }
  if (!hasBraces) return null;
  if (preview.kind === "nodata") return <span className="wfx-ex-muted">{t("previewNoData")}</span>;
  if (preview.kind === "unavailable") return <span className="wfx-ex-muted">{t("previewUnavailable")}</span>;
  return <span className="wfx-ex-muted">{t("previewLoading")}</span>;
}

/**
 * The drawer mounts one scope (workflow + selected node) so the many fields in
 * its per-type sub-components need only say which config path they edit.
 */
export const ExpressionScopeContext = createContext<{ workflowId: string; nodeId: string } | null>(null);

export function ScopedExpressionField(props: Omit<ExpressionFieldProps, "workflowId" | "nodeId">) {
  const scope = useContext(ExpressionScopeContext);
  return <ExpressionField {...props} workflowId={scope?.workflowId ?? ""} nodeId={scope?.nodeId ?? ""} />;
}
