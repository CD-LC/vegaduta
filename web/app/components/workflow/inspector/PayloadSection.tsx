import { useCallback, useMemo, useState } from "react";
import { AlertTriangle, Check, ChevronRight, Copy } from "lucide-react";
import { intFmt } from "./format";
import { childPath, childrenOf, type Payload } from "./payload";

/** One captured payload (input / output / tool arguments) as a collapsible JSON tree or raw text. */
type ExpandMode = "default" | "all" | "none";

export function PayloadSection({ id, title, payload, truncated, copied, onCopy }: {
  id: string; title: string; payload: Payload; truncated: boolean; copied: string | null;
  onCopy: (id: string, text: string) => void;
}) {
  const [mode, setMode] = useState<{ m: ExpandMode; n: number }>({ m: "default", n: 0 });
  const copyPath = useCallback((p: string) => onCopy(`path:${p}`, p), [onCopy]);
  const copyId = `payload:${id}`;
  return (
    <section className="ri-payload" aria-label={title}>
      <div className="ri-payload-head">
        <h3>{title}</h3>
        {payload.kind === "tree" && childrenOf(payload.value) ? (
          <>
            <button type="button" className="ri-link" onClick={() => setMode((s) => ({ m: "all", n: s.n + 1 }))}>
              Expand all
            </button>
            <button type="button" className="ri-link" onClick={() => setMode((s) => ({ m: "none", n: s.n + 1 }))}>
              Collapse
            </button>
          </>
        ) : null}
        {payload.kind !== "none" ? (
          <button
            type="button"
            className="ri-iconbtn ri-sm"
            onClick={() => onCopy(copyId, payload.text)}
            aria-label={copied === copyId ? "Copied" : `Copy ${title.toLowerCase()} JSON`}
            title={`Copy ${title.toLowerCase()}`}
          >
            {copied === copyId ? <Check size={13} aria-hidden="true" /> : <Copy size={13} aria-hidden="true" />}
          </button>
        ) : null}
      </div>
      {truncated ? (
        <p className="ri-trunc" role="note">
          <AlertTriangle size={12} aria-hidden="true" /> Truncated by the server; this is a partial capture.
        </p>
      ) : null}
      {payload.kind === "none" ? <p className="ri-hint">Not captured for this step.</p> : null}
      {payload.kind === "raw" ? <pre className="ri-raw">{payload.text}</pre> : null}
      {payload.kind === "tree" ? (
        <ul className="ri-json" key={mode.n}>
          <JsonNode name={null} value={payload.value} path="$" depth={0} mode={mode.m} onCopyPath={copyPath} copied={copied} />
        </ul>
      ) : null}
    </section>
  );
}

const CHILD_PAGE = 50;
const STR_CLIP = 240;

function JsonNode({ name, value, path, depth, mode, onCopyPath, copied }: {
  name: string | number | null; value: unknown; path: string; depth: number; mode: ExpandMode;
  onCopyPath: (p: string) => void; copied: string | null;
}) {
  const kids = useMemo(() => childrenOf(value), [value]);
  const [open, setOpen] = useState(mode === "all" ? true : mode === "none" ? depth === 0 : depth < 2);
  const [limit, setLimit] = useState(CHILD_PAGE);
  const isArr = Array.isArray(value);

  const keyEl =
    name === null ? null : (
      <span className="ri-jk" title={String(name)}>
        {name}
        <span className="ri-jp">:</span>
      </span>
    );
  const pathBtn =
    depth > 0 ? (
      <button
        type="button"
        className="ri-jpath"
        onClick={() => onCopyPath(path)}
        aria-label={copied === `path:${path}` ? "Path copied" : `Copy path ${path}`}
        title={path}
      >
        {copied === `path:${path}` ? <Check size={11} aria-hidden="true" /> : <Copy size={11} aria-hidden="true" />}
      </button>
    ) : null;

  if (!kids) {
    return (
      <li className="ri-jn">
        <div className="ri-jrow">
          <span className="ri-jgap" aria-hidden="true" />
          {keyEl}
          <JsonLeaf value={value} />
          {pathBtn}
        </div>
      </li>
    );
  }

  const noun = isArr ? (kids.length === 1 ? "item" : "items") : kids.length === 1 ? "key" : "keys";
  const preview = `${isArr ? "[" : "{"} ${intFmt.format(kids.length)} ${noun} ${isArr ? "]" : "}"}`;
  return (
    <li className="ri-jn">
      <div className="ri-jrow">
        <button
          type="button"
          className="ri-jtoggle"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
          disabled={kids.length === 0}
        >
          <ChevronRight size={12} aria-hidden="true" className="ri-chev" />
          {keyEl}
          <span className="ri-jmeta">{open && kids.length ? (isArr ? "[" : "{") : preview}</span>
        </button>
        {pathBtn}
      </div>
      {open && kids.length ? (
        <>
          <ul role="group" className="ri-jkids">
            {kids.slice(0, limit).map(([k, v]) => (
              <JsonNode
                key={String(k)}
                name={k}
                value={v}
                path={childPath(path, k)}
                depth={depth + 1}
                mode={mode}
                onCopyPath={onCopyPath}
                copied={copied}
              />
            ))}
            {kids.length > limit ? (
              <li className="ri-jn">
                <button type="button" className="ri-link ri-more" onClick={() => setLimit(kids.length)}>
                  Show {intFmt.format(kids.length - limit)} more
                </button>
              </li>
            ) : null}
          </ul>
          <span className="ri-jmeta ri-jclose" aria-hidden="true">
            {isArr ? "]" : "}"}
          </span>
        </>
      ) : null}
    </li>
  );
}

function JsonLeaf({ value }: { value: unknown }) {
  const [full, setFull] = useState(false);
  if (typeof value === "string") {
    const long = value.length > STR_CLIP;
    const text = long && !full ? `${value.slice(0, STR_CLIP)}...` : value;
    return (
      <span className="ri-jv" data-t="string">
        &quot;{text}&quot;
        {long ? (
          <button type="button" className="ri-link" onClick={() => setFull((f) => !f)}>
            {full ? "less" : `+${intFmt.format(value.length - STR_CLIP)} chars`}
          </button>
        ) : null}
      </span>
    );
  }
  if (typeof value === "number") return <span className="ri-jv" data-t="number">{String(value)}</span>;
  if (typeof value === "boolean") return <span className="ri-jv" data-t="bool">{String(value)}</span>;
  if (value === null) return <span className="ri-jv" data-t="null">null</span>;
  return <span className="ri-jv">{String(value)}</span>;
}
