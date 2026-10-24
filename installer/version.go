// What an existing install is and what Setup says about it. A tree already in
// the install folder is reused as it is (Setup applies Extras to it and starts
// it); it is never silently replaced by a newer release. So the plan names the
// version that is really there, and when a newer one exists, says so with the
// exact command that upgrades it - the launcher's `upgrade` backs the database
// up first, which is not something a wizard should do by itself.
package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// installedVersion is the release an existing tree runs: the VEGADUTA_VERSION
// its .env pins (what `upgrade` changes), else the version stamped into the
// release's .env.example. "" means unknown: a source checkout, or images built
// from source (VEGADUTA_VERSION=local).
func installedVersion(root string) string {
	pinned := envFileValue(filepath.Join(composeDir(root), ".env"), "VEGADUTA_VERSION")
	switch pinned {
	case "local":
		return ""
	case "", "latest": // floating: the files say which release they came from
		return envFileValue(filepath.Join(composeDir(root), ".env.example"), "VEGADUTA_VERSION")
	}
	return pinned
}

func envFileValue(path, key string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return unquote(parseKV(string(b))[key])
}

// planVersion is the release line of the plan (--dry-run, /api/plan): the
// version of a reused tree, never "latest" for something that is not going to
// be downloaded.
func planVersion(c config, installed string, reused bool) string {
	if !reused {
		return orLatest(c.version)
	}
	if installed == "" {
		return "existing install (version unknown)"
	}
	return installed + " (existing install)"
}

// upgradeCommand is the launcher command that moves a tree to version.
func upgradeCommand(goos, version string) string {
	if goos == "windows" {
		return `.\scripts\vegaduta.ps1 upgrade -Version ` + version
	}
	return "scripts/vegaduta upgrade --version " + version
}

// upgradeAdvice says, for a reused tree, what Setup will not do and what to run
// instead. It is "" when there is nothing to say (up to date, or newer than the
// latest stable release, or a tree with no version). The latest release is
// looked up with a short timeout; a failed lookup is said, not hidden.
func upgradeAdvice(ctx context.Context, c config, installed, goos string) string {
	if installed == "" {
		return ""
	}
	explicit := strings.TrimPrefix(c.version, "v")
	target := explicit
	if explicit == "" {
		lctx, cancel := context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
		v, err := resolveVersion(lctx, c)
		if err != nil {
			return fmt.Sprintf("Version %s is installed. Setup could not check whether a newer release exists (%v).", installed, err)
		}
		target = v
	}
	if target == installed {
		return ""
	}
	// A pre-release of a version is older than the version itself.
	preOfTarget := strings.HasPrefix(installed, target+"-")
	if explicit == "" && versionGE(installed, target) && !preOfTarget {
		return "" // ahead of the latest stable release (a pre-release of the next version)
	}
	if explicit != "" {
		return fmt.Sprintf("You asked for version %s, but version %s is installed. Setup does not change the version of an existing install. "+
			"To move it, run in %s: %s", target, installed, c.dir, upgradeCommand(goos, target))
	}
	return fmt.Sprintf("Version %s is installed and %s is available. Setup does not change the version of an existing install "+
		"(your data and settings stay as they are; this run only applies your Extras and starts it). "+
		"To upgrade, run in %s: %s", installed, target, c.dir, upgradeCommand(goos, target))
}
