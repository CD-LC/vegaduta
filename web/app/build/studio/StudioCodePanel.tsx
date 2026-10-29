"use client";

// Split out of StudioSurface.tsx (behaviour unchanged).
import { useEffect, useState } from "react";
import { Button } from "../../components/ui/button";
import { looksLikeValidApp } from "./patch";
import { useTranslations } from "../../lib/i18n";

export default function CodePanel({ html, onApply }: { html: string; onApply: (next: string) => void }) {
    const tHC = useTranslations("hcBuildStudioStudioCodePanel");
  const [draft, setDraft] = useState(html);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => setDraft(html), [html]);
  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground">{tHC("hcBuildStudioStudioCodePanel.s3")} {Math.max(1, Math.round(html.length / 1024))} {tHC("hcBuildStudioStudioCodePanel.s2")}</p>
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        spellCheck={false}
        className="h-[420px] w-full resize-y rounded-lg border border-border bg-neutral-950 p-2 font-mono text-[11px] leading-snug text-neutral-100"
        aria-label={tHC("hcBuildStudioStudioCodePanel.s1")}
      />
      {err && <p className="text-xs text-destructive">{err}</p>}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={draft === html}
          onClick={() => {
            if (!looksLikeValidApp(draft)) {
              setErr("That doesn't look like a complete app (missing <body> or an unclosed <script>/<style>).");
              return;
            }
            setErr(null);
            onApply(draft);
          }}
        >
          Apply
        </Button>
        <Button size="sm" variant="ghost" disabled={draft === html} onClick={() => setDraft(html)}>
          Discard
        </Button>
      </div>
    </div>
  );
}
