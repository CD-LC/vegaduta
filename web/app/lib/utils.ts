import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

// Standard shadcn/ui helper: clsx resolves conditional/array className
// input into one string, twMerge then resolves conflicting Tailwind
// utilities in that string (e.g. "px-2 px-4" -> "px-4") so later classes in
// a component's className prop reliably win over earlier defaults instead
// of both landing in the DOM and leaving the outcome up to CSS source order.
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
