// Command vegaduta-setup is the one-file installer for people who do not use a
// terminal: it serves a wizard in the browser (loopback only), which checks
// Docker, downloads a release tarball, verifies it against the release's
// SHA256SUMS (and the Sigstore bundle when cosign is installed), unpacks it,
// runs scripts/vegaduta, and opens the console.
//
// It wraps the same contract as install.sh (release artifacts, VEGADUTA_* env)
// and never reimplements the stack's install logic: secrets, doctor and up are
// delegated to scripts/vegaduta (or vegaduta.ps1 on Windows) inside the
// unpacked tree, so the CLI and the wizard can never drift apart.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
)

// buildVersion is stamped by the release workflow (-ldflags -X main.buildVersion).
var buildVersion = "dev"

type config struct {
	dir        string // where the release is unpacked and run
	tier       string // lite | full | personal
	version    string // X.Y.Z; empty = latest published release
	repo       string // GitHub owner/repo
	from       string // existing checkout/tarball dir to use instead of downloading
	releaseURL string // custom tarball URL (install.sh's VEGADUTA_RELEASE_URL)
	releaseSHA string // its expected SHA-256, or "" to consult a sidecar SHA256SUMS
	port       int
	locale     string    // console language chosen in the wizard (APP_LOCALE)
	documents  bool      // personal: also start the ML sidecar (PDF/scan reading, ~2 GB)
	browser    bool      // personal: also start the browser add-on (the agent's own Chromium)
	extras     string    // personal: --extras a,b = exactly these add-ons (ids from addons/catalog.json); "none" = no extras
	listExtras bool      // print the add-on catalog and exit
	extraSel   Selection // the parsed explicit list: --extras, or what the wizard page sent
	extraSet   bool      // extraSel was given explicitly (an empty list is a choice too)
	noBrowser  bool
	headless   bool // run the whole install without the wizard
	dryRun     bool
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func defaultInstallDir() string {
	if v := os.Getenv("VEGADUTA_HOME"); v != "" {
		return v
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "./vegaduta"
	}
	return filepath.Join(home, "VegaDuta")
}

func parseFlags() config {
	var c config
	flag.StringVar(&c.dir, "dir", defaultInstallDir(), "install directory")
	flag.StringVar(&c.tier, "tier", envOr("VEGADUTA_TIER", "personal"), "install tier: personal | lite | full")
	flag.StringVar(&c.version, "version", os.Getenv("VEGADUTA_VERSION"), "release to install, X.Y.Z (default: latest published)")
	flag.StringVar(&c.repo, "repo", envOr("VEGADUTA_REPO", "CD-LC/vegaduta"), "GitHub owner/repo for releases")
	flag.StringVar(&c.releaseURL, "release-url", os.Getenv("VEGADUTA_RELEASE_URL"), "install from this tarball URL instead (file:// for a bundled payload)")
	flag.StringVar(&c.releaseSHA, "release-sha256", os.Getenv("VEGADUTA_RELEASE_SHA256"), "expected SHA-256 of --release-url (hex); empty = read SHA256SUMS next to it")
	flag.StringVar(&c.from, "from", "", "use this existing checkout instead of downloading a release")
	flag.IntVar(&c.port, "port", 17890, "loopback port for the wizard (0 = pick a free one)")
	flag.BoolVar(&c.documents, "documents", false, "personal tier: also install the document sidecar (PDF and scan reading, about 2 GB more)")
	flag.BoolVar(&c.browser, "browser", false, "personal tier: also install the browser add-on (same as --extras ...,browser)")
	flag.StringVar(&c.extras, "extras", "", "personal tier: install exactly these add-ons, comma separated ids (see --list-extras); \"none\" for no extras. Default: the ones this install already has, else the recommended set")
	flag.BoolVar(&c.listExtras, "list-extras", false, "print the add-ons Setup can install, with sizes, and exit")
	flag.BoolVar(&c.noBrowser, "no-browser", false, "serve the wizard but do not open a browser")
	flag.BoolVar(&c.headless, "yes", false, "install without the wizard (no interaction)")
	flag.BoolVar(&c.dryRun, "dry-run", false, "preflight and print the plan; change nothing")
	flag.Parse()
	return c
}

// augmentPath repairs the launchd PATH a Finder-launched app gets
// (/usr/bin:/bin:/usr/sbin:/sbin): docker, brew and winget live elsewhere.
// Docker Desktop >= 4.x links its CLI under ~/.docker/bin first; the
// /usr/local/bin symlink appears only after its privileged first-run step,
// so relying on PATH alone reports a running engine as "not installed".
// Terminal runs are unaffected - the entries are only appended when absent.
func augmentPath() {
	path := os.Getenv("PATH")
	home, _ := os.UserHomeDir()
	dirs := []string{"/usr/local/bin", "/opt/homebrew/bin", "/opt/homebrew/sbin",
		`C:\Program Files\Docker\Docker\resources\bin`}
	if home != "" {
		dirs = append(dirs, filepath.Join(home, ".docker", "bin"))
	}
	for _, d := range dirs {
		if !strings.Contains(path, d) {
			path += string(os.PathListSeparator) + d
		}
	}
	os.Setenv("PATH", path)
}

func main() {
	augmentPath()
	c := parseFlags()
	if c.listExtras {
		printExtras(os.Stdout)
		return
	}
	if c.extras != "" {
		cat, err := loadCatalog()
		if err != nil {
			fatalf("%v", err)
		}
		list := c.extras
		if list == "none" {
			list = ""
		}
		sel, err := ParseIDs(cat, list)
		if err != nil {
			fatalf("--extras: %v", err)
		}
		c.extraSel, c.extraSet = sel, true
	}
	switch c.tier {
	case "lite", "full", "personal":
	default:
		fatalf("unknown --tier %q (valid: lite, full, personal)", c.tier)
	}
	if c.from == "" {
		if dir, ok := looksLikeCheckout(c.dir); ok {
			// The user already has a tree there (re-run, or a dev checkout): reuse it.
			c.from = dir
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	rep := runPreflight(c)
	printReport(rep)
	if c.dryRun {
		fmt.Printf("plan: tier=%s dir=%s version=%s repo=%s\n", c.tier, c.dir, orLatest(c.version), c.repo)
		for _, l := range prepareExtras(installRoot(c), c, runtime.GOOS, func(e Event) { fmt.Println(e.Line) }).describe() {
			fmt.Println(l)
		}
		return
	}
	if c.headless {
		if rep.Blocking() {
			fatalf("%d problem(s) must be fixed before install can run", rep.Failures())
		}
		if err := runInstall(ctx, c, func(e Event) { fmt.Println(e.Line) }); err != nil {
			fatalf("install failed: %v", err)
		}
		return
	}
	// Wizard mode keeps serving on failures: showing the failed checks and how
	// to fix them is the point of the page.
	if err := serveWizard(ctx, c); err != nil {
		fatalf("wizard: %v", err)
	}
}

func looksLikeCheckout(dir string) (string, bool) {
	if st, err := os.Stat(filepath.Join(dir, "scripts", "vegaduta")); err == nil && !st.IsDir() {
		if st, err = os.Stat(filepath.Join(dir, "deploy", "compose")); err == nil && st.IsDir() {
			return dir, true
		}
	}
	return "", false
}

func orLatest(v string) string {
	if v == "" {
		return "latest"
	}
	return v
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "ERROR: "+format+"\n", args...)
	os.Exit(1)
}
