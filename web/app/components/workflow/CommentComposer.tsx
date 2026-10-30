"use client";

// <CommentComposer> - textarea + @mention picker; Ctrl/Cmd+Enter sends.
// Re-exported from CommentThread.tsx.
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { AtSign, Loader2, Send } from "lucide-react";
import {
  COMMENT_MAX_CHARS,
  activeMention,
  extractMentionIds,
  filterPeople,
  insertMention,
  personLabel,
  validateBody,
  type CommentPerson,
} from "./comments";
import { initials, type MentionPerson } from "./commentCardShared";
import { useTranslations } from "../../lib/i18n";

export interface CommentComposerProps {
  people?: MentionPerson[];
  initialText?: string;
  initialMentions?: CommentPerson[];
  placeholder?: string;
  submitLabel?: string;
  /** Accessible name of the text box. */
  label: string;
  busy?: boolean;
  autoFocus?: boolean;
  /**
   * Called with the trimmed body, the mention ids still present in the text
   * and the matching people. The text clears at once; resolve `false` to put
   * it back (rollback), anything else keeps it cleared.
   */
  onSubmit: (
    body: string,
    mentionIds: string[],
    mentioned: CommentPerson[],
    meta: { pickedDuringEdit: boolean },
  ) => Promise<boolean | void> | boolean | void;
  /** Escape (with the picker closed) and the Cancel button. Omit to let Escape bubble. */
  onCancel?: () => void;
  /** Keep the text after a successful submit (edit mode unmounts anyway). */
  keepOnSubmit?: boolean;
}

export function CommentComposer({
  people = [], initialText = "", initialMentions = [], placeholder = "Write a comment…", submitLabel = "Comment",
  label, busy = false, autoFocus = false, onSubmit, onCancel, keepOnSubmit = false,
}: CommentComposerProps) {
    const tHC = useTranslations("hcComponentsWorkflowCommentComposer");
  const uid = useId();
  const listId = `${uid}-people`;
  const hintId = `${uid}-hint`;
  const errId = `${uid}-err`;
  const taRef = useRef<HTMLTextAreaElement>(null);
  const [text, setText] = useState(initialText);
  const [picked, setPicked] = useState<CommentPerson[]>(initialMentions);
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [active, setActive] = useState(0);
  const [error, setError] = useState<string | null>(null);
  // True once the user picks someone from the list (edit mode sends
  // `mentions` only then, or when a token was deleted).
  const pickedRef = useRef(false);
  const canMention = people.length > 0;

  const matches = useMemo(
    () => (canMention && mention ? filterPeople(people, mention.query) : []),
    [canMention, mention, people],
  );
  const pickerOpen = matches.length > 0;
  const activeIdx = Math.min(active, Math.max(0, matches.length - 1));

  useEffect(() => {
    if (!autoFocus) return;
    const ta = taRef.current;
    if (!ta) return;
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);
  }, [autoFocus]);

  function sync(value: string, caret: number) {
    setText(value);
    setError(null);
    const m = canMention ? activeMention(value, caret) : null;
    setMention(m);
    if (!m || m.query !== mention?.query) setActive(0);
  }

  function pick(p: MentionPerson) {
    if (!mention) return;
    const ta = taRef.current;
    const caret = ta ? ta.selectionStart : text.length;
    const next = insertMention(text, mention.start, caret, p);
    setText(next.text);
    pickedRef.current = true;
    setPicked((prev) => (prev.some((x) => x.id === p.id) ? prev : [...prev, { id: p.id, displayName: p.displayName }]));
    setMention(null);
    requestAnimationFrame(() => {
      if (!ta) return;
      ta.focus();
      ta.setSelectionRange(next.caret, next.caret);
    });
  }

  async function submit() {
    if (busy) return;
    const invalid = validateBody(text);
    if (invalid) {
      setError(invalid);
      return;
    }
    const body = text.trim();
    const ids = extractMentionIds(body, picked);
    const mentioned = picked.filter((p) => ids.includes(p.id));
    const before = { text, picked, pickedFlag: pickedRef.current };
    if (!keepOnSubmit) {
      setText("");
      setPicked([]);
      pickedRef.current = false;
    }
    setMention(null);
    const ok = await onSubmit(body, ids, mentioned, { pickedDuringEdit: before.pickedFlag });
    if (ok === false && !keepOnSubmit) {
      // Rollback: only restore when nothing new was typed meanwhile.
      setText((cur) => (cur === "" ? before.text : cur));
      setPicked((cur) => (cur.length === 0 ? before.picked : cur));
      if (before.pickedFlag) pickedRef.current = true;
    }
  }

  function onKeyDown(e: ReactKeyboardEvent<HTMLTextAreaElement>) {
    if (pickerOpen) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const d = e.key === "ArrowDown" ? 1 : -1;
        setActive((i) => (i + d + matches.length) % matches.length);
        return;
      }
      if ((e.key === "Enter" && !e.ctrlKey && !e.metaKey && !e.shiftKey) || e.key === "Tab") {
        e.preventDefault();
        pick(matches[activeIdx]);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        setMention(null);
        return;
      }
    }
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      e.stopPropagation();
      void submit();
      return;
    }
    if (e.key === "Escape" && onCancel) {
      e.preventDefault();
      e.stopPropagation();
      onCancel();
    }
  }

  const over = text.trim().length > COMMENT_MAX_CHARS;
  const near = text.length > COMMENT_MAX_CHARS - 200;

  return (
    <form
      className="wfc-composer"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <div className="wfc-composer-box">
        <label htmlFor={`${uid}-ta`} className="wfc-sr">{label}</label>
        <textarea
          id={`${uid}-ta`}
          ref={taRef}
          className="wfc-input"
          rows={2}
          value={text}
          placeholder={placeholder}
          disabled={busy}
          maxLength={COMMENT_MAX_CHARS + 200}
          aria-describedby={error ? `${hintId} ${errId}` : hintId}
          aria-invalid={error || over ? true : undefined}
          role={canMention ? "combobox" : undefined}
          aria-autocomplete={canMention ? "list" : undefined}
          aria-expanded={canMention ? pickerOpen : undefined}
          aria-controls={canMention && pickerOpen ? listId : undefined}
          aria-activedescendant={pickerOpen ? `${listId}-${activeIdx}` : undefined}
          onChange={(e) => sync(e.target.value, e.target.selectionStart)}
          onSelect={(e) => {
            const t = e.currentTarget;
            if (canMention) setMention(activeMention(t.value, t.selectionStart));
          }}
          onBlur={() => setMention(null)}
          onKeyDown={onKeyDown}
        />
        {pickerOpen && (
          <ul id={listId} className="wfc-picker" role="listbox" aria-label={tHC("s3")}>
            {matches.map((p, i) => (
              <li
                key={p.id}
                id={`${listId}-${i}`}
                role="option"
                aria-selected={i === activeIdx}
                className="wfc-picker-opt"
                // mousedown keeps focus in the textarea (blur would close the list first).
                onMouseDown={(e) => {
                  e.preventDefault();
                  pick(p);
                }}
                onMouseEnter={() => setActive(i)}
              >
                <span className="wfc-avatar" aria-hidden="true">{initials(personLabel(p))}</span>
                {personLabel(p)}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="wfc-composer-foot">
        <span id={hintId} className="wfc-hint">
          {near ? (
            <span data-over={over || undefined}>{text.trim().length} / {COMMENT_MAX_CHARS}</span>
          ) : (
            <>
              {canMention && (
                <span className="wfc-hint-item">
                  <AtSign size={11} aria-hidden="true" /> {tHC("s2")}
                </span>
              )}
              <span className="wfc-hint-item wfc-keys">{tHC("s1")}</span>
            </>
          )}
        </span>
        <span className="wfc-composer-actions">
          {onCancel && (
            <button type="button" className="wfc-btn" onClick={onCancel} disabled={busy}>
              Cancel
            </button>
          )}
          <button type="submit" className="wfc-btn" data-primary="true" disabled={busy || !text.trim() || over}>
            {busy ? <Loader2 size={12} className="wfc-spin" aria-hidden="true" /> : <Send size={12} aria-hidden="true" />}
            {submitLabel}
          </button>
        </span>
      </div>
      {error && (
        <p id={errId} className="wfc-err" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
