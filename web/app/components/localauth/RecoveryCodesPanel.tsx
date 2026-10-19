"use client";

import { useState } from "react";
import { Copy, Download } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { formatRecoveryCode } from "../../lib/localAuthPin";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";

interface Props {
  codes: string[];
  /** Called once the person has confirmed they saved the codes. */
  onContinue: () => void;
  continueLabel: string;
}

/**
 * The one-time recovery codes. They are shown exactly once, so Continue stays disabled until the
 * person ticks that they saved them; Copy and Download are the two ways to keep them.
 */
export default function RecoveryCodesPanel({ codes, onContinue, continueLabel }: Props) {
  const t = useTranslations("signin");
  const [saved, setSaved] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const formatted = codes.map(formatRecoveryCode);

  async function copy() {
    try {
      await navigator.clipboard.writeText(formatted.join("\n"));
      setNote(t("local.codes.copied"));
    } catch {
      setNote(t("local.codes.copyFailed"));
    }
  }

  function download() {
    try {
      const blob = new Blob([`${t("local.codes.fileHeader")}\n\n${formatted.join("\n")}\n`], { type: "text/plain" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "vegaduta-recovery-codes.txt";
      a.click();
      URL.revokeObjectURL(url);
      setNote(t("local.codes.downloaded"));
    } catch {
      setNote(t("local.codes.copyFailed"));
    }
  }

  return (
    <div className="vd-auth__form" data-testid="local-codes-panel">
      <p className="text-sm text-muted-foreground">{t("local.codes.intro")}</p>
      <ul
        className="grid grid-cols-2 gap-2 rounded-lg border border-border bg-secondary/40 p-3 font-mono text-sm tracking-wide"
        aria-label={t("local.codes.listLabel")}
        data-testid="local-codes-list"
      >
        {formatted.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" size="sm" onClick={() => void copy()} data-testid="local-codes-copy">
          <Copy aria-hidden="true" />
          {t("local.codes.copy")}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={download} data-testid="local-codes-download">
          <Download aria-hidden="true" />
          {t("local.codes.download")}
        </Button>
      </div>
      <p role="status" aria-live="polite" className="min-h-4 text-xs text-muted-foreground">
        {note}
      </p>
      <div className="flex items-start gap-2">
        <Checkbox
          id="local-codes-saved"
          checked={saved}
          onCheckedChange={setSaved}
          aria-labelledby="local-codes-saved-label"
          data-testid="local-codes-saved"
        />
        <label id="local-codes-saved-label" htmlFor="local-codes-saved" className="text-sm leading-tight">
          {t("local.codes.ack")}
        </label>
      </div>
      <Button type="button" size="lg" className="w-full" disabled={!saved} onClick={onContinue} data-testid="local-codes-continue">
        {continueLabel}
      </Button>
    </div>
  );
}
