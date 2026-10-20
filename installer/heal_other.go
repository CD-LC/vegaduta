//go:build !windows

package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
)

func ensurePlatformPrereqs(context.Context, func(Event)) error { return nil }

func raiseDockerMemory(context.Context, int64, func(Event)) (bool, error) {
	return false, errors.New(dockerMemoryHint())
}

func dockerSettingsFiles() []dockerSetting {
	if runtime.GOOS != "darwin" {
		return nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	dir := filepath.Join(home, "Library", "Group Containers", "group.com.docker")
	return []dockerSetting{
		{filepath.Join(dir, "settings-store.json"), "AutoStart"},
		{filepath.Join(dir, "settings.json"), "autoStart"},
	}
}
