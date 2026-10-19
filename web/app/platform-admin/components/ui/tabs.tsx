"use client";

import { createContext, useContext, useId, useRef, type ComponentProps, type KeyboardEvent } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Tabs (new-york), token-driven. A lightweight
// context-based implementation (no @radix-ui/react-tabs dependency) that keeps
// the exact shadcn markup + styling contract: <Tabs value onValueChange> wraps
// <TabsList><TabsTrigger value>…</TabsList> and <TabsContent value>. Controlled
// only (value + onValueChange), which is all this app's call sites need.
const TabsContext = createContext<{ value: string; onValueChange: (v: string) => void; baseId: string } | null>(null);

function Tabs({
  value,
  onValueChange,
  className,
  ...props
}: ComponentProps<"div"> & { value: string; onValueChange: (v: string) => void }) {
  const baseId = useId();
  return (
    <TabsContext.Provider value={{ value, onValueChange, baseId }}>
      <div data-slot="tabs" className={cn("flex flex-col gap-4", className)} {...props} />
    </TabsContext.Provider>
  );
}

function TabsList({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="tabs-list"
      role="tablist"
      className={cn(
        "inline-flex h-9 w-full items-center justify-start gap-1 rounded-lg bg-muted p-1 text-muted-foreground",
        className
      )}
      {...props}
    />
  );
}

function TabsTrigger({ value, className, onKeyDown, ...props }: ComponentProps<"button"> & { value: string }) {
  const ctx = useContext(TabsContext);
  const active = ctx?.value === value;
  const ref = useRef<HTMLButtonElement | null>(null);

  // Roving tabindex + Left/Right/Home/End - only the active trigger sits in
  // the tab order (matches the tablist pattern); arrow keys move focus AND
  // selection between sibling [role="tab"] elements in the same tablist.
  function handleKeyDown(e: KeyboardEvent<HTMLButtonElement>) {
    onKeyDown?.(e);
    if (e.defaultPrevented || !ctx) return;
    const list = ref.current?.closest('[role="tablist"]');
    if (!list) return;
    const tabs = Array.from(list.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    const currentIndex = tabs.indexOf(ref.current as HTMLButtonElement);
    if (currentIndex === -1) return;
    let nextIndex: number | null = null;
    if (e.key === "ArrowRight") nextIndex = (currentIndex + 1) % tabs.length;
    else if (e.key === "ArrowLeft") nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
    else if (e.key === "Home") nextIndex = 0;
    else if (e.key === "End") nextIndex = tabs.length - 1;
    if (nextIndex === null) return;
    e.preventDefault();
    const next = tabs[nextIndex];
    next.focus();
    next.click();
  }

  return (
    <button
      ref={ref}
      type="button"
      id={ctx ? `${ctx.baseId}-trigger-${value}` : undefined}
      role="tab"
      aria-selected={active}
      aria-controls={ctx ? `${ctx.baseId}-content-${value}` : undefined}
      tabIndex={active ? 0 : -1}
      data-slot="tabs-trigger"
      data-state={active ? "active" : "inactive"}
      onClick={() => ctx?.onValueChange(value)}
      onKeyDown={handleKeyDown}
      className={cn(
        "inline-flex flex-1 items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1 text-xs font-medium outline-none transition-all",
        "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50",
        "text-muted-foreground hover:text-foreground",
        active && "bg-background text-foreground shadow-sm",
        className
      )}
      {...props}
    />
  );
}

function TabsContent({ value, className, ...props }: ComponentProps<"div"> & { value: string }) {
  const ctx = useContext(TabsContext);
  if (ctx?.value !== value) return null;
  return (
    <div
      id={ctx ? `${ctx.baseId}-content-${value}` : undefined}
      data-slot="tabs-content"
      role="tabpanel"
      aria-labelledby={ctx ? `${ctx.baseId}-trigger-${value}` : undefined}
      // WAI-ARIA tabs pattern: the panel is in the tab order after its tablist.
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      className={cn("outline-none", className)}
      {...props}
    />
  );
}

export { Tabs, TabsList, TabsTrigger, TabsContent };
