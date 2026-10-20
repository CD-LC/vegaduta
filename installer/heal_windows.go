package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// ensurePlatformPrereqs enables WSL2 when Docker is not running yet and WSL is
// missing. Windows shows one administrator prompt; when the change needs a
// restart, setup registers itself to reopen afterwards and stops here.
func ensurePlatformPrereqs(ctx context.Context, emit func(Event)) error {
	if dockerUp(ctx) || wslReady(ctx) {
		return nil
	}
	emit(Event{Line: "Turning on WSL2 (Docker needs it). Windows will ask for permission once - click Yes."})
	err := exec.CommandContext(ctx, "powershell", "-NoProfile", "-Command",
		`Start-Process -FilePath wsl.exe -ArgumentList '--install','--no-distribution' -Verb RunAs -Wait`).Run()
	if err != nil {
		return fmt.Errorf("turning on WSL2 was cancelled or failed (%v) - click Install again and choose Yes when Windows asks", err)
	}
	if wslReady(ctx) {
		return nil
	}
	if exe, err := os.Executable(); err == nil {
		_ = exec.CommandContext(ctx, "reg", "add",
			`HKCU\Software\Microsoft\Windows\CurrentVersion\RunOnce`,
			"/v", "VegaDutaSetup", "/t", "REG_SZ", "/d", `"`+exe+`"`, "/f").Run()
	}
	return errRestartNeeded
}

func wslReady(ctx context.Context) bool {
	return exec.CommandContext(ctx, "wsl.exe", "--status").Run() == nil
}

// raiseDockerMemory sets the WSL2 VM's memory in %USERPROFILE%\.wslconfig and
// restarts WSL and Docker Desktop so it takes effect.
func raiseDockerMemory(ctx context.Context, gb int64, emit func(Event)) (bool, error) {
	out, _ := exec.CommandContext(ctx, "docker", "info", "--format", "{{.KernelVersion}}").Output()
	if !strings.Contains(strings.ToLower(string(out)), "wsl") {
		return false, fmt.Errorf("Docker is not using WSL2; raise it in Docker Desktop > Settings > Resources")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return false, err
	}
	path := filepath.Join(home, ".wslconfig")
	old, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return false, err
	}
	text, changed := mergeWSLConfig(string(old), gb)
	if !changed {
		return false, nil
	}
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		return false, err
	}
	emit(Event{Line: fmt.Sprintf("Giving Docker %d GB of memory - restarting Docker (about a minute)…", gb)})
	_ = exec.CommandContext(ctx, "taskkill", "/IM", "Docker Desktop.exe", "/F").Run()
	_ = exec.CommandContext(ctx, "wsl.exe", "--shutdown").Run()
	if err := dockerStart(ctx, emit); err != nil {
		return true, err
	}
	return true, nil
}

func dockerSettingsFiles() []dockerSetting {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		return nil
	}
	return []dockerSetting{
		{filepath.Join(appData, "Docker", "settings-store.json"), "AutoStart"},
		{filepath.Join(appData, "Docker", "settings.json"), "autoStart"},
	}
}
