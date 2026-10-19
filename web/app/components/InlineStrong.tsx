import { Fragment, type ReactNode } from "react";

// Renders a translated string whose only markup is <strong>…</strong> (and
// <br/>) WITHOUT treating it as HTML (security review FE-003). Message
// catalogs are machine-translated and PR-editable after the OSS release, so
// any other tag, attribute or entity is shown as literal text - a hostile
// "<img onerror=…>" in a catalog can never become markup.
const TOKEN = /<strong>([\s\S]*?)<\/strong>|<br\s*\/?>/g;

export default function InlineStrong({ text }: { text: string }) {
  const out: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(TOKEN)) {
    const at = m.index ?? 0;
    if (at > last) out.push(<Fragment key={key++}>{text.slice(last, at)}</Fragment>);
    out.push(m[0].startsWith("<br") ? <br key={key++} /> : <strong key={key++}>{m[1]}</strong>);
    last = at + m[0].length;
  }
  if (last < text.length) out.push(<Fragment key={key++}>{text.slice(last)}</Fragment>);
  return <>{out}</>;
}
