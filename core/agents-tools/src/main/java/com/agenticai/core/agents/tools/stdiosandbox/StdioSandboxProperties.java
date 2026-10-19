package com.agenticai.core.agents.tools.stdiosandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.mcp.stdio.sandbox*}. Bound alongside the existing {@code app.mcp.stdio.*} keys (those
 * stay {@code @Value}-read where they are; unknown keys under this prefix are ignored here).
 *
 * <p>Everything defaults to "off / unchanged": with {@code sandbox=none} nothing in this package
 * runs and {@code McpStdioParamsBuilder} is byte-for-byte what it was.
 */
@ConfigurationProperties(prefix = "app.mcp.stdio")
public class StdioSandboxProperties {

    /** {@code none} (default) | {@code os} | {@code container}. */
    private StdioSandboxMode sandbox = StdioSandboxMode.NONE;

    /**
     * When true (default) and the chosen mechanism is unavailable on this host, STDIO spawns are
     * REFUSED. When false they fall back to an unsandboxed spawn with a loud WARN on every spawn.
     */
    private boolean sandboxRequired = true;

    /**
     * Linux only: accept the degraded {@code unshare} fallback (namespaces, no filesystem
     * confinement) when bubblewrap is absent. Default true; the status endpoint and a WARN on every
     * spawn say it is degraded. false = bubblewrap or nothing.
     */
    private boolean sandboxAllowDegraded = true;

    /**
     * Server names (case-insensitive) that may reach the network from inside the sandbox, or {@code
     * *} for all. Empty (default) = no network for any server. Note {@code npx}/{@code uvx}
     * download their package on first run: a server needs network until its package cache is warm.
     */
    private List<String> sandboxNetworkAllow = new ArrayList<>();

    /**
     * With network allowed, also let the child connect to loopback (127.0.0.1, ::1). Default false:
     * in native mode Postgres and Valkey are published there. macOS only (Linux {@code --share-net}
     * shares the host network namespace as a whole; use container mode to isolate it).
     */
    private boolean sandboxAllowLoopback = false;

    /**
     * Extra absolute paths the sandboxed child may NOT read: a subpath deny on macOS, a tmpfs (or
     * /dev/null for a file) mounted over the path on Linux bubblewrap. Include the platform's own
     * secrets (the deploy/compose directory with .env and core.env) when the checkout is readable.
     */
    private List<String> sandboxExtraDenyRead = new ArrayList<>();

    /** Extra absolute paths bound read-only into the Linux bubblewrap sandbox (e.g. nvm prefix). */
    private List<String> sandboxExtraReadOnly = new ArrayList<>();

    /** {@code auto} (docker, then podman) | {@code docker} | {@code podman}. */
    private String sandboxContainerRuntime = "auto";

    /** Image for node/npx launchers in container mode. */
    private String sandboxContainerImageNode = "node:22-slim";

    /** Image for uv/uvx/python launchers in container mode (must contain uv). */
    private String sandboxContainerImagePython = "ghcr.io/astral-sh/uv:python3.12-bookworm-slim";

    private String sandboxContainerMemory = "512m";
    private int sandboxContainerPids = 256;

    /** {@code uid:gid} for the container process; blank = this process's uid/gid on POSIX. */
    private String sandboxContainerUser = "";

    public StdioSandboxMode getSandbox() {
        return sandbox;
    }

    public void setSandbox(StdioSandboxMode sandbox) {
        this.sandbox = sandbox == null ? StdioSandboxMode.NONE : sandbox;
    }

    public boolean isSandboxRequired() {
        return sandboxRequired;
    }

    public void setSandboxRequired(boolean sandboxRequired) {
        this.sandboxRequired = sandboxRequired;
    }

    public boolean isSandboxAllowDegraded() {
        return sandboxAllowDegraded;
    }

    public void setSandboxAllowDegraded(boolean sandboxAllowDegraded) {
        this.sandboxAllowDegraded = sandboxAllowDegraded;
    }

    public List<String> getSandboxNetworkAllow() {
        return sandboxNetworkAllow;
    }

    public void setSandboxNetworkAllow(List<String> sandboxNetworkAllow) {
        this.sandboxNetworkAllow = sandboxNetworkAllow == null ? new ArrayList<>() : sandboxNetworkAllow;
    }

    public boolean isSandboxAllowLoopback() {
        return sandboxAllowLoopback;
    }

    public void setSandboxAllowLoopback(boolean sandboxAllowLoopback) {
        this.sandboxAllowLoopback = sandboxAllowLoopback;
    }

    public List<String> getSandboxExtraDenyRead() {
        return sandboxExtraDenyRead;
    }

    public void setSandboxExtraDenyRead(List<String> sandboxExtraDenyRead) {
        this.sandboxExtraDenyRead =
                sandboxExtraDenyRead == null ? new ArrayList<>() : sandboxExtraDenyRead;
    }

    public List<String> getSandboxExtraReadOnly() {
        return sandboxExtraReadOnly;
    }

    public void setSandboxExtraReadOnly(List<String> sandboxExtraReadOnly) {
        this.sandboxExtraReadOnly =
                sandboxExtraReadOnly == null ? new ArrayList<>() : sandboxExtraReadOnly;
    }

    public String getSandboxContainerRuntime() {
        return sandboxContainerRuntime;
    }

    public void setSandboxContainerRuntime(String sandboxContainerRuntime) {
        this.sandboxContainerRuntime =
                sandboxContainerRuntime == null || sandboxContainerRuntime.isBlank()
                        ? "auto"
                        : sandboxContainerRuntime.strip().toLowerCase(Locale.ROOT);
    }

    public String getSandboxContainerImageNode() {
        return sandboxContainerImageNode;
    }

    public void setSandboxContainerImageNode(String sandboxContainerImageNode) {
        this.sandboxContainerImageNode = sandboxContainerImageNode;
    }

    public String getSandboxContainerImagePython() {
        return sandboxContainerImagePython;
    }

    public void setSandboxContainerImagePython(String sandboxContainerImagePython) {
        this.sandboxContainerImagePython = sandboxContainerImagePython;
    }

    public String getSandboxContainerMemory() {
        return sandboxContainerMemory;
    }

    public void setSandboxContainerMemory(String sandboxContainerMemory) {
        this.sandboxContainerMemory = sandboxContainerMemory;
    }

    public int getSandboxContainerPids() {
        return sandboxContainerPids;
    }

    public void setSandboxContainerPids(int sandboxContainerPids) {
        this.sandboxContainerPids = sandboxContainerPids;
    }

    public String getSandboxContainerUser() {
        return sandboxContainerUser;
    }

    public void setSandboxContainerUser(String sandboxContainerUser) {
        this.sandboxContainerUser = sandboxContainerUser == null ? "" : sandboxContainerUser.strip();
    }

    /** Whether {@code serverName} may use the network inside the sandbox. */
    public boolean networkAllowedFor(String serverName) {
        for (String entry : sandboxNetworkAllow) {
            if (entry == null) {
                continue;
            }
            String e = entry.strip();
            if (e.equals("*") || (serverName != null && e.equalsIgnoreCase(serverName.strip()))) {
                return true;
            }
        }
        return false;
    }
}
