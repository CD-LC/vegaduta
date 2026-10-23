package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstalledVersion(t *testing.T) {
	mk := func(t *testing.T, env, example string) string {
		t.Helper()
		root := tree(t, env, "", "")
		if example != "" {
			if err := os.WriteFile(filepath.Join(composeDir(root), ".env.example"), []byte(example), 0o644); err != nil {
				t.Fatal(err)
			}
		}
		return root
	}
	for name, tc := range map[string]struct{ env, example, want string }{
		"pinned in .env":              {"A=1\nVEGADUTA_VERSION=0.1.8\n", "VEGADUTA_VERSION=0.1.9\n", "0.1.8"},
		"quoted":                      {"VEGADUTA_VERSION=\"0.1.8\"\n", "", "0.1.8"},
		"empty .env falls to shipped": {"VEGADUTA_VERSION=\n", "VEGADUTA_VERSION=0.1.9\r\n", "0.1.9"},
		"no .env, shipped stamp":      {"", "VEGADUTA_VERSION=0.1.9\n", "0.1.9"},
		"source checkout":             {"VEGADUTA_VERSION=\n", "VEGADUTA_VERSION=\n", ""},
		"built from source":           {"VEGADUTA_VERSION=local\n", "", ""},
		"floating latest":             {"VEGADUTA_VERSION=latest\n", "VEGADUTA_VERSION=0.1.9\n", "0.1.9"},
		"nothing":                     {"", "", ""},
	} {
		if got := installedVersion(mk(t, tc.env, tc.example)); got != tc.want {
			t.Errorf("%s: installedVersion = %q want %q", name, got, tc.want)
		}
	}
}

func TestUpgradeAdvice(t *testing.T) {
	ctx := context.Background()
	c := config{repo: "o/r", dir: "/home/me/VegaDuta"}
	g := &fakeGitHub{redirectTo: "/o/r/releases/tag/v0.1.9"}
	newFakeGitHub(t, g)

	adv := upgradeAdvice(ctx, c, "0.1.8", "linux")
	for _, want := range []string{"0.1.8", "0.1.9", "scripts/vegaduta upgrade --version 0.1.9", "does not change"} {
		if !strings.Contains(adv, want) {
			t.Errorf("advice misses %q: %s", want, adv)
		}
	}
	if win := upgradeAdvice(ctx, c, "0.1.8", "windows"); !strings.Contains(win, `.\scripts\vegaduta.ps1 upgrade -Version 0.1.9`) {
		t.Errorf("windows advice: %s", win)
	}
	if adv := upgradeAdvice(ctx, c, "0.1.9", "linux"); adv != "" {
		t.Errorf("up to date, nothing to say: %s", adv)
	}
	if adv := upgradeAdvice(ctx, c, "1.0.0-rc.1", "linux"); adv != "" {
		t.Errorf("a newer install than the latest stable release needs no advice: %s", adv)
	}
	if adv := upgradeAdvice(ctx, c, "0.1.9-rc.1", "linux"); !strings.Contains(adv, "0.1.9-rc.1") || !strings.Contains(adv, "upgrade --version 0.1.9") {
		t.Errorf("a release candidate is older than its release: %s", adv)
	}
	if adv := upgradeAdvice(ctx, c, "", "linux"); adv != "" {
		t.Errorf("a source checkout has no version to compare: %s", adv)
	}
	// An explicit --version that differs from what is installed is not applied to a reused tree: say so.
	c.version = "0.2.0"
	if adv := upgradeAdvice(ctx, c, "0.1.8", "linux"); !strings.Contains(adv, "You asked for version 0.2.0") || !strings.Contains(adv, "--version 0.2.0") || !strings.Contains(adv, "0.1.8") {
		t.Errorf("explicit version advice: %s", adv)
	}
	c.version = "0.1.8"
	if adv := upgradeAdvice(ctx, c, "0.1.8", "linux"); adv != "" {
		t.Errorf("explicit version equals installed: %s", adv)
	}
	// Offline: say the check could not be made instead of staying silent.
	c.version = ""
	githubWeb, githubAPI = "http://127.0.0.1:1", "http://127.0.0.1:1"
	if adv := upgradeAdvice(ctx, c, "0.1.8", "linux"); !strings.Contains(adv, "could not check") || !strings.Contains(adv, "0.1.8") {
		t.Errorf("offline advice: %s", adv)
	}
}

func TestPlanVersionNeverSaysLatestForAReusedTree(t *testing.T) {
	if got := planVersion(config{}, "", false); got != "latest" {
		t.Errorf("fresh install: %q", got)
	}
	if got := planVersion(config{version: "1.0.0-rc.1"}, "", false); got != "1.0.0-rc.1" {
		t.Errorf("fresh install, explicit: %q", got)
	}
	got := planVersion(config{}, "0.1.8", true)
	if strings.Contains(got, "latest") || !strings.Contains(got, "0.1.8") {
		t.Errorf("reused tree: %q", got)
	}
	if got := planVersion(config{}, "", true); strings.Contains(got, "latest") {
		t.Errorf("reused tree of unknown version: %q", got)
	}
}

func TestValidateVersionFlag(t *testing.T) {
	if v, err := validateVersion(""); err != nil || v != "" {
		t.Errorf("empty = latest: %q %v", v, err)
	}
	if v, err := validateVersion("v1.2.3"); err != nil || v != "1.2.3" {
		t.Errorf("%q %v", v, err)
	}
	for _, bad := range []string{"1.0", "latest", "1.2.3/../x", "1.0.0-"} {
		if _, err := validateVersion(bad); err == nil || !strings.Contains(err.Error(), "X.Y.Z") {
			t.Errorf("validateVersion(%q) = %v", bad, err)
		}
	}
}
