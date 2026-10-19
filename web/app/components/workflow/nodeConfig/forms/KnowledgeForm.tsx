"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { CollectionSummary } from "../types";
import type { NodeFormProps } from "../registryTypes";
import Select from "../../../Select";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { SetupHint } from "../../../setup/SetupLinks";

export default function KnowledgeForm({ config, setConfig, collections }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
                <div className="field">
                  <Label>{t("collectionLabel")}</Label>
                  <Select
                    value={config.collectionId || ""}
                    onChange={(e) => setConfig({ collectionId: e.target.value })}
                    placeholder={t("everyCollectionPlaceholder")}
                    options={(collections || []).map((c: CollectionSummary) => ({ value: c.id, label: c.name }))}
                  />
                </div>
                <div className="field">
                  <Label>{t("topKLabel")}</Label>
                  <Input
                    type="number"
                    min={1}
                    max={20}
                    value={config.topK ?? 5}
                    onChange={(e) => setConfig({ topK: Number(e.target.value) })}
                  />
                </div>
              <SetupHint targets={["knowledge"]} lead="Upload documents and create collections:" newTab />
    </>
  );
}
