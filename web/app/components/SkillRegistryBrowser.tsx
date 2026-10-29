"use client";

// Public Agent Skills registry, the skills counterpart of the MCP registry:
// search the index, install in one click. Core does the fetching (from GitHub,
// through the same import path as an uploaded .zip), so every format and size
// limit still applies. Copy is inline English on purpose: see web-i18n rules
// on en.json fragility.

import { useEffect, useRef, useState } from "react";
import { Check, Download, ExternalLink, Search, Star } from "lucide-react";
import { api, type SkillRegistryEntry, type SkillSummary } from "../lib/api";
import { useAuth } from "./AuthProvider";
import { Alert, AlertDescription } from "./ui/alert";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardContent } from "./ui/card";
import { Input } from "./ui/input";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import SkillScanDialog, { type SkillScanPrompt } from "./skills/SkillScanDialog";

const QUICK = [
  "code review",
  "pdf",
  "testing",
  "frontend design",
  "data analysis",
  "git",
  "docs",
  "security",
  "sql",
  "marketing",
];
const REGISTRIES = [
  { name: "SkillsMP", href: "https://skillsmp.com/categories" },
  { name: "Agentic Skills", href: "https://agenticskills.io/skills" },
];

type Props = { installed: SkillSummary[]; onChanged: () => Promise<unknown> | void };

export default function SkillRegistryBrowser({ installed, onChanged }: Props) {
  const { hasPermission } = useAuth();
  const canManageSkills = hasPermission("MANAGE_AGENTS");
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SkillRegistryEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState<string | null>(null);
  // Which skill is being fetched and scanned (before anything is installed), and the scan
  // result waiting for the admin's confirmation.
  const [scanning, setScanning] = useState<string | null>(null);
  const [scanPrompt, setScanPrompt] = useState<SkillScanPrompt | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [link, setLink] = useState("");
  const installedNames = new Set(installed.map((s) => s.name));
  const resultsPagination = usePagination(results);

  // Only the latest search may write results: typing then clicking a quick-pick fires two requests, and
  // the slower, older one used to land last and show results for a query the box no longer holds.
  const searchSeq = useRef(0);
  const search = async (q: string) => {
    const seq = ++searchSeq.current;
    setLoading(true);
    setError(null);
    try {
      const found = await api.searchSkillRegistry(q);
      if (seq !== searchSeq.current) return;
      setResults(Array.isArray(found?.skills) ? found.skills : []);
      setTotal(found?.total ?? 0);
    } catch (e) {
      if (seq !== searchSeq.current) return;
      setResults([]);
      setTotal(0);
      setError(e instanceof Error ? e.message : "The registry is not answering right now.");
    } finally {
      if (seq === searchSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    void search("");
  }, []);

  // Scan first: the skill is fetched exactly as the installer would and checked, and the admin
  // sees the result in a dialog. Nothing is stored until they confirm there. If scanning is
  // switched off server-side (disabled) the install proceeds as it always did.
  const install = async (githubUrl: string, label: string) => {
    setScanning(githubUrl);
    setError(null);
    setNotice(null);
    try {
      const outcome = await api.previewRegistrySkill(githubUrl);
      if (outcome.error) {
        setError(`${label}: ${outcome.error}`);
        return;
      }
      if (outcome.preview) {
        setScanPrompt({
          preview: outcome.preview,
          confirmLabel: "Install skill",
          onConfirm: () => {
            setScanPrompt(null);
            void runInstall(githubUrl, label, outcome.preview.replacesExisting, outcome.preview.contentSha256);
          },
        });
        return;
      }
    } finally {
      setScanning(null);
    }
    await runInstall(githubUrl, label);
  };

  const runInstall = async (
    githubUrl: string,
    label: string,
    replace = false,
    expectedSha256: string | null = null
  ) => {
    setInstalling(githubUrl);
    setError(null);
    setNotice(null);
    try {
      const result = await api.installRegistrySkill(githubUrl, replace, expectedSha256);
      setNotice(`Installed "${result.skill.name}". Turn it on for an agent from that agent's Skills setting.`);
      await onChanged();
    } catch (e) {
      setError(`${label}: ${e instanceof Error ? e.message : "install failed"}`);
    } finally {
      setInstalling(null);
    }
  };

  return (
    <section aria-labelledby="skill-registry-heading" className="mt-10 space-y-4">
      <div>
        <h2 id="skill-registry-heading" className="text-lg font-semibold text-foreground">
          Skill registry
        </h2>
        <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
          Search public Agent Skills and install one in a click. Skills are downloaded from their GitHub source and
          stored in your workspace as instructions and text files; nothing in them is executed. Read a skill before you
          give it to an agent, as you would any shared prompt. Each install is scanned first and you see the result
          before anything is saved; a scan reduces risk, it does not prove a skill is safe.
        </p>
      </div>

      <form
        className="flex flex-wrap gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          void search(query);
        }}
      >
        <div className="relative min-w-0 flex-1">
          <Search
            size={14}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search skills: code review, pdf, testing..."
            aria-label="Search the skill registry"
            className="ps-8"
          />
        </div>
        <Button type="submit" size="sm" disabled={loading}>
          {loading ? "Searching..." : "Search"}
        </Button>
      </form>

      <div className="flex flex-wrap gap-1.5">
        {QUICK.map((q) => (
          <button
            key={q}
            type="button"
            onClick={() => {
              setQuery(q);
              void search(q);
            }}
            className="rounded-full border border-border bg-transparent px-2.5 py-1 text-xs text-foreground shadow-none hover:bg-accent"
          >
            {q}
          </button>
        ))}
      </div>

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {notice && (
        <Alert>
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      )}

      {!loading && results.length > 0 && (
        <p className="text-xs text-muted-foreground">
          {new Intl.NumberFormat().format(total)} skills match. Showing the most starred.
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {resultsPagination.pageItems.map((skill) => {
          const has = installedNames.has(skill.name);
          return (
            <Card key={skill.id} className="h-full">
              <CardContent className="flex h-full flex-col gap-2 p-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <p className="truncate font-mono text-sm font-semibold text-foreground">{skill.name}</p>
                    <p className="truncate text-xs text-muted-foreground">by {skill.author}</p>
                  </div>
                  <Badge variant="outline" className="shrink-0 gap-1">
                    <Star size={11} aria-hidden="true" />{" "}
                    {new Intl.NumberFormat(undefined, { notation: "compact" }).format(skill.stars)}
                  </Badge>
                </div>
                <p className="line-clamp-3 text-xs leading-relaxed text-muted-foreground">{skill.description}</p>
                <div className="mt-auto flex items-center justify-between gap-2 pt-1">
                  <a
                    href={skill.githubUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                  >
                    Source <ExternalLink size={11} aria-hidden="true" />
                  </a>
                  {has ? (
                    <span className="inline-flex items-center gap-1 text-xs font-medium text-primary">
                      <Check size={13} aria-hidden="true" /> Installed
                    </span>
                  ) : canManageSkills ? (
                    <Button
                      size="sm"
                      disabled={installing !== null || scanning !== null}
                      onClick={() => void install(skill.githubUrl, skill.name)}
                    >
                      <Download aria-hidden="true" size={13} />{" "}
                      {scanning === skill.githubUrl
                        ? "Scanning..."
                        : installing === skill.githubUrl
                          ? "Installing..."
                          : "Install"}
                    </Button>
                  ) : (
                    <span className="text-xs text-muted-foreground">Ask a workspace admin to install</span>
                  )}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>
      <PaginationControls
        page={resultsPagination.page}
        totalPages={resultsPagination.totalPages}
        totalItems={resultsPagination.totalItems}
        pageSize={resultsPagination.pageSize}
        onPageChange={resultsPagination.setPage}
        onPageSizeChange={resultsPagination.setPageSize}
        itemLabel="skills"
      />

      {!loading && results.length === 0 && !error && (
        <p className="text-sm text-muted-foreground">No skills found for that search.</p>
      )}

      {canManageSkills && (
        <form
          className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card p-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (link.trim()) void install(link.trim(), "GitHub link");
          }}
        >
          <label htmlFor="skill-github-link" className="text-xs font-medium text-foreground">
            Found one elsewhere? Paste its GitHub folder link
          </label>
          <Input
            id="skill-github-link"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            placeholder="https://github.com/owner/repo/tree/main/skills/name"
            className="min-w-0 flex-1"
          />
          <Button
            type="submit"
            size="sm"
            variant="outline"
            disabled={installing !== null || scanning !== null || !link.trim()}
          >
            Install from link
          </Button>
        </form>
      )}

      <p className="text-xs text-muted-foreground">
        Browse more on{" "}
        {REGISTRIES.map((r, i) => (
          <span key={r.href}>
            {i > 0 && " and "}
            <a href={r.href} target="_blank" rel="noopener noreferrer" className="underline hover:text-foreground">
              {r.name}
            </a>
          </span>
        ))}
        ; any skill there that links to a GitHub folder installs with the box above.
      </p>

      <SkillScanDialog prompt={scanPrompt} busy={installing !== null} onCancel={() => setScanPrompt(null)} />
    </section>
  );
}
