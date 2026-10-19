"use client";

import {  useEffect,  useState } from "react";
import {
  
  
  
  
  
  
  Globe,
  
  
  
  
  
  ShieldCheck,
  
  
  
  
  
} from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import {
  
  
  getEdgeRedactionMode,
  
  getWebMcpEnabled,
  
  
  
  
  setEdgeRedactionMode,
  setWebMcpEnabled,
  EDGE_PREFS_EVENT,
  type EdgeRedactionMode,
  
  
  
  
  
} from "../../lib/edge/capabilities";
import {
  
  
  
  
  
  
  tier1StructuredAvailable,
} from "../../lib/edge/tier1";
import {
  detectWebMcp,
  getWebMcpPublishState,
  subscribeWebMcpPublishState,
  type WebMcpPublishState,
  type WebMcpSupport,
} from "../../lib/edge/webmcp";

// ---------------------------------------------------------------------------
// Data shield (E-3) — how hard this device scrubs a turn that falls through
// to a hosted model. The copy deliberately names the COST of each level, not
// just the benefit: "contact" and "model" genuinely change what some tasks
// can accomplish, and a privacy control that hides that is how people end up
// with quietly worse answers and no idea why.
// ---------------------------------------------------------------------------

export function EdgeDataShieldSection() {
  const t = useTranslations("edge");
  const [mode, setMode] = useState<EdgeRedactionMode>("secrets");
  const [modelReady, setModelReady] = useState(false);

  useEffect(() => {
    const sync = () => {
      setMode(getEdgeRedactionMode());
      setModelReady(tier1StructuredAvailable());
    };
    sync();
    window.addEventListener(EDGE_PREFS_EVENT, sync);
    return () => window.removeEventListener(EDGE_PREFS_EVENT, sync);
  }, []);

  const options: Array<{ value: EdgeRedactionMode; label: string; detail: string }> = [
    { value: "off", label: t("dataShield.optionOff"), detail: t("dataShield.optionOffDetail") },
    { value: "secrets", label: t("dataShield.optionSecrets"), detail: t("dataShield.optionSecretsDetail") },
    { value: "contact", label: t("dataShield.optionContact"), detail: t("dataShield.optionContactDetail") },
    { value: "model", label: t("dataShield.optionModel"), detail: t("dataShield.optionModelDetail") },
  ];

  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-start gap-3">
        <ShieldCheck size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-foreground">{t("dataShield.title")}</h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{t("dataShield.body")}</p>
        </div>
      </div>
      <div className="mt-4 flex flex-col gap-2">
        {options.map((option) => (
          <label
            key={option.value}
            className={cn(
              "flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors",
              mode === option.value ? "border-primary bg-primary/5" : "border-border hover:bg-secondary/40"
            )}
          >
            <input
              type="radio"
              name="edge-data-shield"
              className="mt-1 accent-primary"
              checked={mode === option.value}
              onChange={() => {
                setEdgeRedactionMode(option.value);
                setMode(option.value);
              }}
            />
            <span className="min-w-0">
              <span className="block text-sm font-medium text-foreground">{option.label}</span>
              <span className="block text-xs leading-relaxed text-muted-foreground">{option.detail}</span>
              {option.value === "model" && mode === "model" && (
                <span className="mt-1 block text-xs text-muted-foreground">
                  {modelReady ? t("dataShield.modelReady") : t("dataShield.modelIdle")}
                </span>
              )}
            </span>
          </label>
        ))}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Browser tools (E-2b) — the WebMCP publish switch and its honest status.
// ---------------------------------------------------------------------------

export function EdgeBrowserToolsSection() {
  const t = useTranslations("edge");
  const [enabled, setEnabled] = useState(true);
  const [support, setSupport] = useState<WebMcpSupport>({ supported: false, api: null });
  const [state, setState] = useState<WebMcpPublishState>(getWebMcpPublishState);

  useEffect(() => {
    setSupport(detectWebMcp());
    setEnabled(getWebMcpEnabled());
    return subscribeWebMcpPublishState(setState);
  }, []);

  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <div className="flex items-start gap-3">
        <Globe size={18} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold text-foreground">{t("browserTools.title")}</h2>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted-foreground">{t("browserTools.body")}</p>
          {support.supported ? (
            <>
              <label className="mt-3 flex cursor-pointer items-center gap-2 text-sm text-foreground">
                <input
                  type="checkbox"
                  className="accent-primary"
                  checked={enabled}
                  onChange={(event) => {
                    setWebMcpEnabled(event.target.checked);
                    setEnabled(event.target.checked);
                  }}
                />
                {t("browserTools.enable")}
              </label>
              <p className="mt-2 text-xs text-muted-foreground">
                {state.toolNames.length
                  ? t("browserTools.published", { count: String(state.toolNames.length) })
                  : t("browserTools.none")}
                {state.skipped > 0 && ` ${t("browserTools.skipped", { count: String(state.skipped) })}`}
              </p>
              {support.api === "modelContextTesting" && (
                <p className="mt-1 text-xs text-muted-foreground">{t("browserTools.testing")}</p>
              )}
            </>
          ) : (
            <p className="mt-3 text-xs text-muted-foreground">{t("browserTools.unsupported")}</p>
          )}
        </div>
      </div>
    </section>
  );
}
