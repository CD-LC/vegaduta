"use client";

import { useState } from "react";
import { api } from "../../lib/api";
import { generateToolOnDevice } from "../../lib/edge/codegen";
import { useTranslations } from "../../lib/i18n";
import { GEN_TEMPLATE_PRESETS, type CodeToolGenerationResponse, type CustomCodeToolLanguage } from "./model";

/**
 * State and handler behind the "Ask AI to generate the tool" popover. Tries
 * on-device generation first; anything short of a server-validated draft falls
 * back to the full server-side endpoint.
 */
export function useToolGeneration({
  language,
  toolName,
  onSourceCode,
}: {
  language: CustomCodeToolLanguage;
  toolName: string;
  onSourceCode: (sourceCode: string) => void;
}) {
  const t = useTranslations("customCodeTools");
  const [open, setOpen] = useState(false);
  const [requirementInput, setRequirementInput] = useState("");
  const [templateChoice, setTemplateChoice] = useState<string>(GEN_TEMPLATE_PRESETS[0].id);
  const [customTemplate, setCustomTemplate] = useState("");
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [genResult, setGenResult] = useState<CodeToolGenerationResponse | null>(null);
  // Which of the ways (edge / server) produced the draft currently shown.
  const [genOrigin, setGenOrigin] = useState<"edge" | "server" | null>(null);

  function reset() {
    setOpen(false);
    setRequirementInput("");
    setTemplateChoice(GEN_TEMPLATE_PRESETS[0].id);
    setCustomTemplate("");
    setGenerating(false);
    setError(null);
    setGenResult(null);
    setGenOrigin(null);
  }

  // Tries on-device generation first: generateToolOnDevice only ever resolves
  // ok:true once the draft has already passed the server's authoritative
  // sandbox validation, so an ok:true+valid:false result still means "try
  // again server-side" rather than "show this rejected draft".
  async function generate() {
    const requirement = requirementInput.trim();
    if (!requirement) {
      setError(t("askAiRequirementRequired"));
      return;
    }
    const template =
      templateChoice === "custom"
        ? customTemplate.trim() || null
        : (GEN_TEMPLATE_PRESETS.find((p) => p.id === templateChoice)?.template ?? null);
    const name = toolName.trim() || null;
    setError(null);
    setGenerating(true);
    try {
      let result: CodeToolGenerationResponse | null = null;
      let origin: "edge" | "server" = "server";

      const edgeOutcome = await generateToolOnDevice({ requirement, template, language, toolName: name });
      if (edgeOutcome.ok && edgeOutcome.valid) {
        result = {
          sourceCode: edgeOutcome.sourceCode,
          valid: edgeOutcome.valid,
          errors: edgeOutcome.errors,
          warnings: edgeOutcome.warnings,
          language: edgeOutcome.language,
        };
        origin = "edge";
      } else {
        // Covers: edge disabled/not opted in, on-device generation failed,
        // validate was unreachable, AND edge produced a draft that failed
        // validation - every one of those falls through to the full
        // server-side path, never surfacing an unvalidated (or
        // known-invalid) edge draft as if it were the final answer.
        result = await api.generateCustomCodeTool({ requirement, template, language, toolName: name });
        origin = "server";
      }
      setGenResult(result);
      setGenOrigin(origin);
      // Populate the editor regardless of verdict - a rejected draft should
      // never be lost, same posture as a REJECTED save.
      onSourceCode(result.sourceCode);
      if (result.valid) setOpen(false);
    } catch (err: any) {
      setError(err?.message || t("askAiGenerateError"));
    } finally {
      setGenerating(false);
    }
  }

  return {
    open,
    setOpen,
    requirementInput,
    setRequirementInput,
    templateChoice,
    setTemplateChoice,
    customTemplate,
    setCustomTemplate,
    generating,
    error,
    genResult,
    genOrigin,
    reset,
    generate,
  };
}

export type ToolGeneration = ReturnType<typeof useToolGeneration>;
