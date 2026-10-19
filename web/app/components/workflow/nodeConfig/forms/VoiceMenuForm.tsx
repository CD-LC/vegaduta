"use client";

import { Plus, Trash2 } from "lucide-react";
import { type VoiceMenuBranch } from "../../nodeDefs";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig } from "../types";

const DTMF_CHARS = /[^0-9*#]/g;

/**
 * Voice Menu node - the dedicated IVR menu for CALL-triggered workflows
 * (WorkflowNodeType.VOICE_MENU). Config is the prompt spoken to the caller,
 * how many digits to collect (blank = until the finish key), the timeout and
 * retry policy, and the `branches` list of {digit, label} options. Each
 * option's `label` becomes an output port on the canvas (see WorkflowNode.tsx's
 * voiceMenuPorts) - like Supervisor's route handles, the label text is
 * load-bearing: it is what a drawn edge's sourceHandle resolves against, so
 * renaming one here re-points any edge already drawn from that port. Call
 * LOGIC only - no STT/TTS model or codec settings live here.
 */
export function VoiceMenuNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const branches: VoiceMenuBranch[] = Array.isArray(config.branches) ? config.branches : [];

  function updateBranch(index: number, patch: Partial<VoiceMenuBranch>) {
    setConfig({ branches: branches.map((b, i) => (i === index ? { ...b, ...patch } : b)) });
  }

  function addBranch() {
    const n = branches.length + 1;
    // Next unused digit when one is free (1-9, then 0), else blank for the
    // author to fill - never pre-fills a digit an existing option already owns.
    const used = new Set(branches.map((b) => b.digit));
    const digit = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"].find((d) => !used.has(d)) || "";
    setConfig({ branches: [...branches, { digit, label: `option-${n}` }] });
  }

  function removeBranch(index: number) {
    setConfig({ branches: branches.filter((_, i) => i !== index) });
  }

  // Blank clears the key (server default applies); otherwise clamp to the
  // range the server enforces anyway, so the field never shows a value the
  // backend would silently rewrite.
  function numberPatch(raw: string, min: number, max: number): number | undefined {
    return raw === "" ? undefined : Math.max(min, Math.min(max, Number(raw)));
  }

  return (
    <>
      <p className="ax-wf-empty">{t("voiceMenuHint")}</p>

      <div className="field">
        <Label>{t("voiceMenuPromptLabel")}</Label>
        <textarea
          rows={3}
          className={TEXTAREA_CLASS}
          placeholder={t("voiceMenuPromptPlaceholder")}
          value={config.prompt || ""}
          onChange={(e) => setConfig({ prompt: e.target.value })}
        />
      </div>

      <div className="field">
        <Label>{t("voiceMenuInvalidPromptLabel")}</Label>
        <textarea
          rows={2}
          className={TEXTAREA_CLASS}
          placeholder={t("voiceMenuInvalidPromptPlaceholder")}
          value={config.invalidPrompt || ""}
          onChange={(e) => setConfig({ invalidPrompt: e.target.value })}
        />
      </div>

      <div className="field">
        <div className="row" style={{ gap: "0.5rem" }}>
          <div style={{ flex: 1 }}>
            <Label>{t("voiceMenuNumDigitsLabel")}</Label>
            <Input
              type="number"
              min={1}
              max={20}
              value={config.numDigits ?? ""}
              placeholder={t("voiceMenuNumDigitsPlaceholder")}
              onChange={(e) => setConfig({ numDigits: numberPatch(e.target.value, 1, 20) })}
            />
          </div>
          <div style={{ flex: "0 0 30%" }}>
            <Label>{t("voiceMenuFinishOnKeyLabel")}</Label>
            <Input
              type="text"
              maxLength={1}
              value={config.finishOnKey ?? ""}
              placeholder="#"
              onChange={(e) => setConfig({ finishOnKey: e.target.value.replace(DTMF_CHARS, "") })}
            />
          </div>
        </div>
        <p className="ax-wf-empty">{t("voiceMenuNumDigitsHint")}</p>
      </div>

      <div className="field">
        <div className="row" style={{ gap: "0.5rem" }}>
          <div style={{ flex: 1 }}>
            <Label>{t("voiceMenuTimeoutLabel")}</Label>
            <Input
              type="number"
              min={1}
              max={60}
              value={config.timeoutSeconds ?? ""}
              placeholder="5"
              onChange={(e) => setConfig({ timeoutSeconds: numberPatch(e.target.value, 1, 60) })}
            />
          </div>
          <div style={{ flex: 1 }}>
            <Label>{t("voiceMenuMaxRetriesLabel")}</Label>
            <Input
              type="number"
              min={0}
              max={5}
              value={config.maxRetries ?? ""}
              placeholder="2"
              onChange={(e) => setConfig({ maxRetries: numberPatch(e.target.value, 0, 5) })}
            />
          </div>
        </div>
      </div>

      <div className="field">
        <Label>{t("voiceMenuBranchesLabel")}</Label>
        {branches.map((branch, i) => (
          <div key={i} className="row" style={{ gap: "0.4rem", marginBottom: "0.4rem" }}>
            <Input
              type="text"
              maxLength={1}
              value={branch.digit || ""}
              onChange={(e) => updateBranch(i, { digit: e.target.value.replace(DTMF_CHARS, "") })}
              placeholder={t("voiceMenuBranchDigitPlaceholder")}
              style={{ flex: "0 0 22%" }}
            />
            <Input
              type="text"
              value={branch.label || ""}
              onChange={(e) => updateBranch(i, { label: e.target.value })}
              placeholder={t("voiceMenuBranchLabelPlaceholder")}
              style={{ flex: 1 }}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={() => removeBranch(i)}
              aria-label={t("voiceMenuRemoveBranch")}
            >
              <Trash2 size={14} />
            </Button>
          </div>
        ))}
        <Button type="button" variant="outline" size="sm" onClick={addBranch}>
          <Plus size={14} /> {t("voiceMenuAddBranch")}
        </Button>
        <p className="ax-wf-empty">{t("voiceMenuBranchesHint")}</p>
      </div>
    </>
  );
}

