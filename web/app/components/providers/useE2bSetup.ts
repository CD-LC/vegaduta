"use client";

import { useState, type FormEvent, type MouseEvent } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";

type Translate = (key: string, values?: Record<string, any>) => string;

/**
 * E2B follow-up steps once the key is ACTIVE: save the code-execution template id, and a
 * "Test connection" run (SdlcSandboxService#runCode always kills its sandbox, so a network
 * hiccup here cannot strand one against the tenant's E2B quota).
 */
export function useE2bSetup(
  refreshE2b: () => Promise<unknown>,
  setError: (m: string | null) => void,
  setSuccess: (m: string | null) => void,
  t: Translate
) {
  const [templateId, setTemplateId] = useState("");
  const [templateBusy, setTemplateBusy] = useState(false);
  const [testBusy, setTestBusy] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  async function handleSetTemplate(e: FormEvent) {
    e.preventDefault();
    if (!templateId.trim()) return;
    setTemplateBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.setE2bCodeExecutionTemplate(templateId.trim());
      await refreshE2b();
      setTemplateId("");
      setSuccess(t("e2bTemplateSavedSuccess"));
    } catch (err) {
      setError(errorMessage(err, t("e2bTemplateSaveFailedError")));
    } finally {
      setTemplateBusy(false);
    }
  }

  // A non-zero exitCode or timedOut is a failed test even though the HTTP call succeeded.
  async function handleTestConnection(e: MouseEvent<HTMLButtonElement>) {
    e.preventDefault();
    e.stopPropagation();
    setTestBusy(true);
    setTestResult(null);
    try {
      const result = await api.runSandboxCode("print('E2B connection OK')", "python", 20);
      if (result.timedOut) {
        setTestResult({ ok: false, message: t("e2bTestConnectionTimedOutError") });
      } else if (result.exitCode !== 0) {
        setTestResult({
          ok: false,
          message: t("e2bTestConnectionExitCodeError", {
            exitCode: result.exitCode,
            stderr: (result.stderr || "").trim(),
          }),
        });
      } else {
        setTestResult({ ok: true, message: t("e2bTestConnectionSuccess") });
      }
    } catch (err) {
      setTestResult({ ok: false, message: errorMessage(err, t("e2bTestConnectionFailedError")) });
    } finally {
      setTestBusy(false);
    }
  }

  return { templateId, setTemplateId, templateBusy, testBusy, testResult, handleSetTemplate, handleTestConnection };
}

export type E2bSetup = ReturnType<typeof useE2bSetup>;
