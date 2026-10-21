# Getting help

| You want to | Go to |
|---|---|
| Install or run VegaDūta | [docs/getting-started.md](docs/getting-started.md), [docs/installation.md](docs/installation.md) |
| Find a configuration setting | [docs/configuration.md](docs/configuration.md) |
| Add a tool, MCP server, skill, provider, workflow node or channel | [docs/extending.md](docs/extending.md) |
| Ask a question or share what you built | [GitHub Discussions](https://github.com/CD-LC/vegaduta/discussions) |
| Report a bug or request a feature | [GitHub Issues](https://github.com/CD-LC/vegaduta/issues/new/choose) |
| Report a vulnerability | privately, see [SECURITY.md](SECURITY.md) |

Before opening an issue:

1. Run `./scripts/vegaduta doctor` (on Windows `.\scripts\vegaduta.ps1 doctor`)
   and include its output. If the console opens, also check its
   **Troubleshoot** page (`/troubleshoot`): it tests the API and, on a personal
   install, the local model, and fixes the common problems.
2. Search existing issues and discussions.
3. Include the version (`VEGADUTA_VERSION` in `deploy/compose/.env`, or the
   commit), the install tier, and relevant logs (`./scripts/vegaduta logs core`; the
   Setup app on macOS also writes `~/Library/Logs/vegaduta-setup.log`)
   with keys, tokens and personal data removed.

Support in this repository is community support on a best-effort basis. There
is no guaranteed response time; see the targets in [GOVERNANCE.md](GOVERNANCE.md).

Which versions get fixes: the latest minor release and `main`, with critical
fixes for the previous minor for three months after the next one ships (before
1.0, only the latest release and `main`). The same table is in
[SECURITY.md](SECURITY.md#supported-versions).
