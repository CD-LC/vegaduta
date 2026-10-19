"use client";

import { createContext, useContext, type ComponentProps } from "react";
import { cn } from "../../lib/utils";

// Canonical shadcn/ui Tabs (new-york), token-driven. A lightweight
// context-based implementation (no @radix-ui/react-tabs dependency) that keeps
// the exact shadcn markup + styling contract: <Tabs value onValueChange> wraps
// <TabsList><TabsTrigger value>…</TabsList> and <TabsContent value>. Controlled
// only (value + onValueChange), which is all this app's call sites need.
const TabsContext = createContext<{ value: string; onValueChange: (v: string) => void } | null>(null);

function Tabs({
  value,
  onValueChange,
  className,
  ...props
}: ComponentProps<"div"> & { value: string; onValueChange: (v: string) => void }) {
  return (
    <TabsContext.Provider value={{ value, onValueChange }}>
      <div data-slot="tabs" className={cn("flex flex-col gap-4", className)} {...props} />
    </TabsContext.Provider>
  );
}

export const TABS_LIST_CLASS =
  "inline-flex h-9 w-full max-w-full items-center justify-start gap-1 overflow-x-auto overflow-y-hidden rounded-lg bg-muted p-1 text-muted-foreground [scrollbar-width:none] [&::-webkit-scrollbar]:hidden";

function TabsList({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="tabs-list"
      role="tablist"
      // Scrolls sideways instead of overflowing: triggers are `whitespace-nowrap`
      // and cannot shrink below their label, so five of them ran past a 375px
      // screen with nothing to scroll (prod pass bug 5). The scrollbar is hidden;
      // touch/trackpad scrolling and arrow-key focus still reach every tab.
      className={cn(TABS_LIST_CLASS, className)}
      {...props}
    />
  );
}

function TabsTrigger({ value, className, ...props }: ComponentProps<"button"> & { value: string }) {
  const ctx = useContext(TabsContext);
  const active = ctx?.value === value;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      data-slot="tabs-trigger"
      data-state={active ? "active" : "inactive"}
      onClick={() => ctx?.onValueChange(value)}
      className={cn(
        "inline-flex flex-1 items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1 text-xs font-medium outline-none transition-all",
        "focus-visible:ring-[3px] focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-60",
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
  return <div data-slot="tabs-content" role="tabpanel" className={cn("outline-none", className)} {...props} />;
}

export { Tabs, TabsList, TabsTrigger, TabsContent };
