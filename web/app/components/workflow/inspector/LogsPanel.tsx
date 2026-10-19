import { Fragment, useEffect, useMemo, useRef, type ReactNode } from "react";
import { Check, Copy, Search } from "lucide-react";
import { intFmt } from "./format";
import type { LevelFilter, LogLevel } from "./types";

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function highlight(text: string, query: string): ReactNode {
  if (!query) return text;
  const parts = text.split(new RegExp(`(${escapeRe(query)})`, "ig"));
  // split() with one capture group puts matches on odd indices.
  return parts.map((p, i) => (i % 2 === 1 ? <mark key={i}>{p}</mark> : <Fragment key={i}>{p}</Fragment>));
}

export type LogRow = { n: number; text: string; level: LogLevel };

export function LogsPanel({ logs, counts, level, onLevel, query, onQuery, live, copied, onCopy }: {
  logs: LogRow[]; counts: Record<LogLevel, number>; level: LevelFilter; onLevel: (l: LevelFilter) => void;
  query: string; onQuery: (q: string) => void; live: boolean; copied: string | null;
  onCopy: (id: string, text: string) => void;
}) {
  const q = query.trim();
  const shown = useMemo(() => {
    const lq = q.toLowerCase();
    return logs.filter((l) => (level === "all" || l.level === level) && (!lq || l.text.toLowerCase().includes(lq)));
  }, [logs, level, q]);

  // Live tail: follow new lines only if the reader is already at the bottom.
  const listRef = useRef<HTMLOListElement | null>(null);
  const pinned = useRef(true);
  useEffect(() => {
    const el = listRef.current;
    if (el && live && pinned.current) el.scrollTop = el.scrollHeight;
  }, [shown.length, live]);

  if (logs.length === 0) {
    return <p className="ri-hint">{live ? "Waiting for this step to log something..." : "This step wrote no log lines."}</p>;
  }

  const filters: Array<{ k: LevelFilter; label: string; n: number }> = [
    { k: "all", label: "All", n: logs.length },
    { k: "error", label: "Errors", n: counts.error },
    { k: "warn", label: "Warnings", n: counts.warn },
  ];

  return (
    <div className="ri-logs">
      <div className="ri-logbar">
        <label className="ri-search">
          <Search size={13} aria-hidden="true" />
          <input
            type="search"
            value={query}
            placeholder="Filter lines"
            aria-label="Filter log lines"
            onChange={(e) => onQuery(e.target.value)}
            onKeyDown={(e) => {
              // First Esc clears the filter; the next one closes the card.
              if (e.key === "Escape" && query) {
                e.preventDefault();
                onQuery("");
              }
            }}
          />
        </label>
        <button
          type="button"
          className="ri-iconbtn"
          onClick={() => onCopy("logs", shown.map((l) => l.text).join("\n"))}
          aria-label={copied === "logs" ? "Copied" : `Copy ${shown.length} shown line${shown.length === 1 ? "" : "s"}`}
          title="Copy shown lines"
        >
          {copied === "logs" ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
        </button>
      </div>
      <div className="ri-seg" role="radiogroup" aria-label="Log level">
        {filters.map((f) => (
          <button
            key={f.k}
            type="button"
            role="radio"
            aria-checked={level === f.k}
            className="ri-seg-btn"
            data-level={f.k}
            disabled={f.k !== "all" && f.n === 0}
            onClick={() => onLevel(f.k)}
          >
            {f.label} <span className="ri-num">{intFmt.format(f.n)}</span>
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="ri-hint">No lines match.</p>
      ) : (
        <ol
          ref={listRef}
          className="ri-loglist"
          aria-label={`${shown.length} log line${shown.length === 1 ? "" : "s"}`}
          onScroll={(e) => {
            const el = e.currentTarget;
            pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
          }}
        >
          {shown.map((l) => (
            <li key={l.n} className="ri-logline" data-level={l.level}>
              <span className="ri-ln" aria-hidden="true">
                {l.n}
              </span>
              <span className="ri-lvl">{l.level === "error" ? "ERR" : l.level === "warn" ? "WRN" : ""}</span>
              <span className="ri-lt">{highlight(l.text, q)}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
