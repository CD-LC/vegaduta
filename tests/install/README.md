# tests/install

End-to-end test of the installer, `scripts/vegaduta`, without Docker running anything.

```sh
bash tests/install/vegaduta-install.test.sh
```

`up --dry-run` does everything `up` does up to the Docker call (secrets, the first-run model question,
the state file, the compose arguments) and prints the plan. The test drives it with flags and, through
`pty_run.py`, on a real pseudo-terminal, because the first-run question only appears when stdin and
stdout are terminals. It checks what each choice leaves in the state file, that a second run reuses it,
that a script or an existing install is never asked, and (when Docker is present) that the chosen compose
files merge into a valid project with `docker compose config`.

The test is hermetic: it ignores an Ollama running on the machine (a curl shim fails requests for port 11434;
`FAKE_OLLAMA=1` makes the shim answer, for the checks that need one), so it gives the same result on a
developer laptop as in CI, and it runs under macOS's stock bash 3.2. A new check must not depend on the host either.

It runs in `make gates` and in the Static gates workflow. A full install on real containers (build,
sign-up, an agent answering) is `.github/workflows/e2e.yml` with `tests/e2e/tests/bundles-connect-chat.spec.ts`.
