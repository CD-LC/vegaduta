"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, Dices } from "lucide-react";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Textarea } from "../components/ui/textarea";
import { useTranslations } from "../lib/i18n";
import {
  MAX_IDEA,
  SHAPE_IDS,
  STYLE_IDS,
  buildImagePrompt,
  pickIdea,
  playgroundHref,
  type ShapeId,
  type StyleId,
} from "./imagePrompt";

const IDEA_KEYS = ["idea1", "idea2", "idea3", "idea4", "idea5", "idea6"] as const;

function Chips<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T | null;
  options: { id: T; label: string }[];
  onChange: (next: T | null) => void;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-1.5">
      <span className="me-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</span>
      {options.map((o) => {
        const on = value === o.id;
        return (
          <Button
            key={o.id}
            type="button"
            size="sm"
            variant={on ? "default" : "outline"}
            aria-pressed={on}
            onClick={() => onChange(on ? null : o.id)}
          >
            {o.label}
          </Button>
        );
      })}
    </div>
  );
}

/**
 * Compose an image brief and hand it to the Playground (which reads ?prompt=).
 * No new backend: the agent's generate_image tool does the work, same as typing it by hand.
 */
export default function QuickCreate() {
  const t = useTranslations("studioPage");
  const [idea, setIdea] = useState("");
  const [style, setStyle] = useState<StyleId | null>(null);
  const [shape, setShape] = useState<ShapeId | null>(null);
  const [spin, setSpin] = useState(0);

  const prompt = buildImagePrompt(idea, style, shape);
  const ideas = IDEA_KEYS.map((k) => t(k));

  return (
    <Card className="mb-6">
      <CardHeader>
        <CardTitle>{t("quickTitle")}</CardTitle>
        <CardDescription>{t("quickHint")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <Textarea
          value={idea}
          rows={2}
          maxLength={MAX_IDEA}
          placeholder={t("quickPlaceholder")}
          aria-label={t("quickIdeaLabel")}
          onChange={(e) => setIdea(e.target.value)}
        />
        <Chips<StyleId>
          label={t("quickStyleLabel")}
          value={style}
          onChange={setStyle}
          options={STYLE_IDS.map((id) => ({ id, label: t(`style_${id}`) }))}
        />
        <Chips<ShapeId>
          label={t("quickShapeLabel")}
          value={shape}
          onChange={setShape}
          options={SHAPE_IDS.map((id) => ({ id, label: t(`shape_${id}`) }))}
        />
        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setIdea(pickIdea(ideas, spin));
              setSpin((n) => n + 1);
            }}
          >
            <Dices className="h-4 w-4 shrink-0" aria-hidden="true" /> {t("quickSurprise")}
          </Button>
          {prompt ? (
            <Button asChild>
              <Link href={playgroundHref(prompt)}>
                {t("quickGo")} <ArrowRight className="h-4 w-4 shrink-0" aria-hidden="true" />
              </Link>
            </Button>
          ) : (
            <Button disabled>
              {t("quickGo")} <ArrowRight className="h-4 w-4 shrink-0" aria-hidden="true" />
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
