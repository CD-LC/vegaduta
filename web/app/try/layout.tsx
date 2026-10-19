import type { Metadata } from "next";
import type { ReactNode } from "react";

// Server-component metadata shell for /try. page.tsx is "use client" and
// therefore cannot export metadata itself; this pass-through layout carries
// the route's SEO tags and renders children unchanged.
export const metadata: Metadata = {
  title: "Build a Real AI Agent in 2 Minutes — No Signup | VegaDūta Studio",
  description:
    "Not a chatbot. Design an AI agent with a mission, tools and rules, watch it search, read, call APIs and run code, chain agents into a workflow with human approval, and put it on your phone with a QR code — free, no signup.",
  alternates: { canonical: "/try" },
  openGraph: {
    title: "Build a Real AI Agent in 2 Minutes — No Signup",
    description:
      "Design an agent with tools and rules, watch it work step by step, chain agents with a human approval pause, scan it onto your phone. Free, no signup.",
    images: ["/og-image.png"],
  },
};

export default function TryLayout({ children }: { children: ReactNode }) {
  return children;
}
