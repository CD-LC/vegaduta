"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import Select from "../Select";
import { Label } from "../ui/label";
import { Switch } from "../ui/switch";
import { ScopedExpressionField } from "./ExpressionField";
import {
  clearTemplateConfigPatch,
  findTemplate,
  parseTemplateKey,
  parseWhatsAppTemplates,
  resizeTemplateParams,
  selectedTemplate,
  setTemplateParam,
  templateConfigPatch,
  templateKey,
  templateLoadFailure,
  type TemplateLoadState,
  type WhatsAppTemplateRef,
} from "./whatsappTemplates";

/**
 * Output node, WhatsApp channel: the message body and the approved-template
 * picker (nlflow W6). Rendered under the recipient field in NodeConfigDrawer.
 *
 * - "Message" is config.whatsappMessage; blank keeps the old behaviour (the
 *   previous step's output is sent).
 * - "Use an approved template" lists the tenant's APPROVED Meta templates and
 *   stores { name, language } plus one value per {{n}} body variable. Outside
 *   Meta's 24-hour window a template is the only thing the official channel
 *   delivers.
 *
 * The picker depends on a server flag: a 404 from the listing means templates
 * are switched off, and then no template UI renders at all - core ignores the
 * template keys in that state, so offering them would be a lie.
 */

type Translator = (key: string, values?: Record<string, string | number>) => string;

export type WhatsAppOutputConfig = {
  whatsappMessage?: string;
  whatsappTemplate?: { name?: string; language?: string } | null;
  whatsappTemplateParams?: string[];
};

type Props = {
  config: WhatsAppOutputConfig;
  setConfig: (patch: Partial<WhatsAppOutputConfig>) => void;
};

const HINT = "ax-wf-empty";
const CHANNELS_HREF = "/admin/channels";

export default function WhatsAppTemplateFields({ config, setConfig }: Props) {
  const t = useTranslations("workflowDescribe") as Translator;
  const [load, setLoad] = useState<TemplateLoadState>({ status: "loading" });
  // The switch was turned on but no template chosen yet (nothing to store).
  const [picking, setPicking] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .listWhatsAppTemplates()
      .then((raw: unknown) => {
        if (!cancelled) setLoad({ status: "ready", templates: parseWhatsAppTemplates(raw) });
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoad(templateLoadFailure(err));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const stored = selectedTemplate(config);
  const templates = load.status === "ready" ? load.templates : [];
  const current = findTemplate(templates, stored);
  const useTemplate = picking || stored !== null;
  const paramCount = current ? current.bodyParamCount : Array.isArray(config.whatsappTemplateParams) ? config.whatsappTemplateParams.length : 0;
  const params = resizeTemplateParams(config.whatsappTemplateParams, paramCount);

  function choose(key: string) {
    const ref: WhatsAppTemplateRef | null = parseTemplateKey(key);
    const template = findTemplate(templates, ref);
    if (!template) {
      // Back to "Choose a template": keep the picker open for a new choice
      // instead of collapsing it (the switch is what turns templates off).
      setPicking(true);
      setConfig(clearTemplateConfigPatch());
      return;
    }
    setConfig(templateConfigPatch(template, config.whatsappTemplateParams));
  }

  // The listing failed (no Manage channels, Meta refused, network) but the
  // node already stores a template, which core still sends at run time. Show
  // it read-only with its values and a switch to turn it off, rather than
  // hiding something that is in effect.
  const storedOnly = stored !== null && (load.status === "forbidden" || load.status === "error");
  const templateInEffect = useTemplate && (load.status === "ready" || storedOnly);

  const paramFields = params.map((value, i) => (
    <div key={i} style={{ marginTop: "0.5rem" }}>
      <Label>{t("whatsappTemplateParamLabel", { n: i + 1 })}</Label>
      <ScopedExpressionField
        field={`whatsappTemplateParams[${i}]`}
        placeholder={t("whatsappTemplateParamPlaceholder", { n: i + 1 })}
        value={value}
        onChange={(v) => setConfig({ whatsappTemplateParams: setTemplateParam(params, i, v) })}
        ariaLabel={t("whatsappTemplateParamLabel", { n: i + 1 })}
      />
    </div>
  ));

  function toggle(on: boolean) {
    setPicking(on);
    if (!on) setConfig(clearTemplateConfigPatch());
  }

  const channelsLink = (
    // New tab: the canvas may hold unsaved edits.
    <Link href={CHANNELS_HREF} target="_blank" rel="noreferrer" className="font-medium text-primary underline-offset-2 hover:underline">
      {t("whatsappChannelsLink")}
    </Link>
  );

  return (
    <div style={{ marginTop: "0.5rem" }} data-testid="whatsapp-template-fields">
      <Label>{t("whatsappMessageLabel")}</Label>
      <ScopedExpressionField
        field="whatsappMessage"
        multiline
        placeholder={t("whatsappMessagePlaceholder")}
        value={config.whatsappMessage || ""}
        onChange={(v) => setConfig({ whatsappMessage: v })}
        ariaLabel={t("whatsappMessageLabel")}
      />
      <p className={HINT}>{templateInEffect ? t("whatsappMessageFallbackHint") : t("whatsappMessageHint")}</p>

      {load.status === "loading" && (
        <p className={HINT} data-testid="whatsapp-templates-loading">
          {t("whatsappTemplatesLoading")}
        </p>
      )}

      {load.status === "forbidden" && (
        <p className={HINT} data-testid="whatsapp-templates-forbidden">
          {t("whatsappTemplatesForbidden")}
        </p>
      )}

      {load.status === "error" && (
        <p className={HINT} data-testid="whatsapp-templates-error">
          {t("whatsappTemplatesError")}
          {load.message ? ` ${load.message}` : ""}
        </p>
      )}

      {storedOnly && stored && (
        <div style={{ marginTop: "0.5rem" }} data-testid="whatsapp-template-stored">
          <div className="flex items-center gap-2">
            <Switch
              checked
              onCheckedChange={toggle}
              aria-label={t("whatsappUseTemplate")}
              data-testid="whatsapp-template-toggle"
            />
            <span className="text-sm text-foreground">{t("whatsappUseTemplate")}</span>
          </div>
          <p className={HINT}>{t("whatsappTemplateStored", { name: stored.name, language: stored.language })}</p>
          {paramFields}
        </div>
      )}

      {load.status === "ready" && templates.length === 0 && !stored && (
        <p className={HINT} data-testid="whatsapp-templates-empty">
          {t("whatsappTemplatesEmpty")} {channelsLink}
        </p>
      )}

      {load.status === "ready" && (templates.length > 0 || stored) && (
        <div style={{ marginTop: "0.5rem" }}>
          <div className="flex items-center gap-2">
            <Switch
              checked={useTemplate}
              onCheckedChange={toggle}
              aria-label={t("whatsappUseTemplate")}
              data-testid="whatsapp-template-toggle"
            />
            <span className="text-sm text-foreground">{t("whatsappUseTemplate")}</span>
          </div>
          <p className={HINT}>{t("whatsappWindowHint")}</p>

          {useTemplate && (
            <div style={{ marginTop: "0.5rem" }}>
              <Label>{t("whatsappTemplateLabel")}</Label>
              <Select
                data-testid="whatsapp-template-select"
                value={stored ? templateKey(stored) : ""}
                onChange={(e: { target: { value: string } }) => choose(e.target.value)}
                options={[
                  { value: "", label: t("whatsappTemplateChoose") },
                  ...(stored && !current
                    ? [{ value: templateKey(stored), label: t("whatsappTemplateMissing", { name: stored.name, language: stored.language }) }]
                    : []),
                  ...templates.map((tpl) => ({
                    value: templateKey(tpl),
                    label: t("whatsappTemplateOption", { name: tpl.name, language: tpl.language }),
                  })),
                ]}
              />
              {stored && !current && (
                <p className={HINT} data-testid="whatsapp-template-missing">
                  {t("whatsappTemplateMissingHint")} {channelsLink}
                </p>
              )}
              {current && current.preview && (
                <p className={HINT} data-testid="whatsapp-template-preview">
                  {/* Not interpolated through t(): the preview itself contains {{1}}-style braces. */}
                  {t("whatsappTemplatePreviewLabel")} {current.preview}
                </p>
              )}
              {stored && paramFields}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
