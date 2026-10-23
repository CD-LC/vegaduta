// Extras inside the install pipeline: pick the selection, write what the
// launcher cannot, run it, then clean up after it. runInstall calls the four
// methods of extrasRun in order; the tests drive them against a temp tree.
package main

import (
	"context"
	"fmt"
	"io"
	"strings"
)

type extrasRun struct {
	active bool // personal tier with a usable catalog
	cat    Catalog
	sel    Selection
	cur    Selection
	plan   Plan
	caps   launcherCaps
	goos   string
}

// legacyExtras maps the old --documents / --browser flags to add-on ids.
func legacyExtras(c config) []string {
	var ids []string
	if c.documents {
		ids = append(ids, "documents")
	}
	if c.browser {
		ids = append(ids, "browser")
	}
	return ids
}

// initialSelection is what the page preselects and what a run without an
// explicit list applies: --extras, else the install's current set, else the
// catalog defaults, plus the legacy flags.
func initialSelection(c config, cat Catalog, cur Current) Selection {
	return ChooseSelection(cat, cur, c.extraSel, c.extraSet, legacyExtras(c)...)
}

// prepareExtras decides the plan for this run. A broken catalog never stops an
// install: it falls back to the pre-Extras behaviour (the build terminal only).
func prepareExtras(root string, c config, goos string, emit func(Event)) extrasRun {
	x := extrasRun{goos: goos}
	if c.tier != "personal" {
		return x
	}
	cat, err := loadCatalog()
	if err != nil {
		emit(Event{Line: "Extras are unavailable (" + err.Error() + "); installing the default set only"})
		x.plan = Plan{Profiles: []string{"workspace"}, EnvSet: map[string]string{}}
		return x
	}
	cur := DetectCurrent(root, cat)
	sel := initialSelection(c, cat, cur)
	var dropped []string
	for _, id := range sel.IDs(cat) {
		if a, _ := cat.Find(id); Unsupported(a, goos) != "" {
			dropped = append(dropped, a.Name)
		}
	}
	sel = StripUnsupported(cat, sel, goos)
	if len(dropped) > 0 {
		emit(Event{Line: "Skipped on this operating system: " + strings.Join(dropped, ", ")})
	}
	x.active, x.cat, x.sel, x.cur = true, cat, sel, cur.Sel
	x.plan = BuildPlan(cat, sel, cur.Sel)
	x.caps = readLauncherCaps(root)
	return x
}

// describe is the one-paragraph summary shown in the log and by --dry-run.
func (x extrasRun) describe() []string {
	if !x.active {
		return nil
	}
	ids := x.sel.IDs(x.cat)
	if len(ids) == 0 {
		return []string{"extras: none (the base install only)"}
	}
	lines := []string{"extras: " + strings.Join(ids, ", ")}
	if len(x.plan.Profiles)+len(x.plan.Flags) > 0 {
		lines = append(lines, "  launcher: profiles=["+strings.Join(x.plan.Profiles, " ")+"] flags=["+strings.Join(x.plan.Flags, " ")+"]")
	}
	for _, k := range sortedKeys(x.plan.EnvSet) {
		lines = append(lines, fmt.Sprintf("  .env: %s=%s", k, x.plan.EnvSet[k]))
	}
	for _, p := range x.plan.Pulls {
		lines = append(lines, "  model download: "+p.Tag)
	}
	return lines
}

// beforeUp writes the .env keys and makes the launcher's remembered profile
// list match the plan, so the CLI run that follows lands on exactly this set.
func (x extrasRun) beforeUp(root string, emit func(Event)) error {
	if !x.active {
		return nil
	}
	log, err := ApplyEnv(root, x.plan, catalogEnvValues(x.cat))
	if err != nil {
		return fmt.Errorf("extras: %w", err)
	}
	for _, l := range log {
		emit(Event{Line: l})
	}
	if changed, err := SyncStateProfiles(root, x.plan.Profiles); err != nil {
		return fmt.Errorf("extras: %w", err)
	} else if changed {
		emit(Event{Line: "Updated the launcher's remembered profiles to match your choices"})
	}
	return nil
}

// args is the launcher invocation for this run.
func (x extrasRun) args(tier string) []string { return upArgs(tier, x.plan, x.caps) }

// afterUp stops what was switched off (data kept), downloads picked models and
// records the choice so the next run of Setup shows it. A model whose download
// failed is not recorded, so the next run still offers it; those packs are
// returned for the caller to say so at the end.
func (x extrasRun) afterUp(ctx context.Context, root string, emit func(Event)) (failedPulls []Pull) {
	if !x.active {
		return nil
	}
	if len(x.plan.Turned) > 0 {
		StopTurnedOff(ctx, root, x.plan.Turned, emit)
	}
	failedPulls = PullModels(ctx, root, x.goos, x.plan.Pulls, emit)
	rec := x.sel.clone()
	for _, p := range failedPulls {
		delete(rec, p.ID)
	}
	if err := saveChoice(root, x.cat, rec); err != nil {
		emit(Event{Line: "could not remember your extras choice: " + err.Error()})
	}
	return failedPulls
}

// printExtras is --list-extras: the catalog as Setup will apply it.
func printExtras(w io.Writer) {
	cat, err := loadCatalog()
	if err != nil {
		fmt.Fprintln(w, "add-on catalog unavailable:", err)
		return
	}
	fmt.Fprintf(w, "Extras Setup can install (use --extras id,id ; sizes are approximate):\n\n")
	fmt.Fprintf(w, "  %-18s %-10s %-9s %-9s %s\n", "ID", "DISK", "MEMORY", "LICENCE", "WHAT")
	for _, a := range cat.Addons {
		state := ""
		switch {
		case !a.Selectable():
			state = " (planned: not installable yet)"
		case a.DefaultOn:
			state = " (on by default)"
		}
		fmt.Fprintf(w, "  %-18s %-10s %-9s %-9s %s%s\n", a.ID, gb(a.SizeMB), gb(a.MinRAMMB), shortLicence(a.License), a.Name, state)
	}
	fmt.Fprintln(w, "\nSwitching an extra off only stops it; your data and downloaded models are kept.")
}

func shortLicence(l string) string {
	if l == "" {
		return "-"
	}
	if i := strings.IndexAny(l, ";("); i > 0 {
		l = strings.TrimSpace(l[:i])
	}
	return l
}
