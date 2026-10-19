import type { Translate } from "../agentHooks";

export default function FieldError({ t, error, id }: { t: Translate; error?: string; id: string }) {
  if (!error) return null;
  return (
    <p id={id} className="mt-1 text-xs text-destructive" role="alert">
      {t(`error.${error}`)}
    </p>
  );
}
