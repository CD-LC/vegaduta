import type { ReactNode } from "react";
import { cn } from "../../../lib/utils";

const optionButtonBase =
  "flex w-full flex-col items-start gap-0.5 rounded-md border px-2.5 py-2 text-start text-xs transition-colors disabled:cursor-not-allowed disabled:opacity-60";

export default function SourceOption({
  icon,
  title,
  subtitle,
  active,
  disabled,
  onClick,
}: {
  icon: ReactNode;
  title: string;
  subtitle: string;
  active: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      className={cn(
        optionButtonBase,
        active
          ? "border-primary bg-primary/10 text-foreground"
          : "border-border bg-transparent text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
      )}
    >
      <span className="inline-flex items-center gap-1.5 font-medium text-foreground">
        {icon}
        {title}
      </span>
      <span className="text-[11px] leading-snug text-muted-foreground">{subtitle}</span>
    </button>
  );
}
