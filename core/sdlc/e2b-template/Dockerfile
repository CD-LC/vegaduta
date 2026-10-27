# Master Roadmap 2026 item #14 (E2B code execution completion, §B).
# Base template E2B itself recommends for custom templates - includes
# python3 already; node added for the "node" language option runner.py
# supports. See README.md in this directory for build/push instructions.
FROM e2bdev/code-interpreter:latest@sha256:8bea292fd8741f66a2d83f6911b6dea0e0ac20e989879868b1a360a16e78895f

# The base image's last USER is the non-root `user`, so the package
# installs below must switch to root explicitly (apt's lock and
# /var/lib/apt/lists are root-only). The final USER user further down
# switches back before the image is finished.
USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Data-analysis sandbox (gap-analysis item 9): spreadsheet/PDF readers and
# writers plus marimo so the agent can hand back a runnable notebook. pandas
# and matplotlib are installed explicitly (not assumed from the base image),
# and the import check fails the build if any of them is missing. Rebuild +
# re-push the template (README.md) for these to take effect on existing
# tenants, and only then set app.sandbox.data-analysis.enabled=true.
RUN pip install --no-cache-dir pandas matplotlib openpyxl xlsxwriter pypdf marimo \
    && python -c "import pandas, matplotlib, openpyxl, xlsxwriter, pypdf, marimo"

COPY runner.py /runner.py

# Finish as E2B's documented default sandbox account `user` (home
# /home/user). The pinned code-interpreter base image already ends with
# `USER user` / `WORKDIR /home/user`, so the runner and tenant scripts ran
# as `user` before this line too; it is declared here so this Dockerfile
# satisfies docker:S6471 on its own (the rule checks the last effective
# USER) and does not silently depend on the base image's choice, which the
# `USER root` above would otherwise have overridden.
#
# This is NOT a privilege boundary, and deliberately no attempt is made to
# turn it into one here. The base image grants `user ALL=(ALL) NOPASSWD: ALL`
# in /etc/sudoers (its own entrypoint sudo-runs /root/.jupyter/start-up.sh),
# and - decisively - E2B's template build pipeline re-grants it after every
# layer of this Dockerfile has run: the finalize phase executes
# phases/finalize/configure.sh as root in the build VM, which appends
# `user ALL=(ALL:ALL) NOPASSWD: ALL` to /etc/sudoers unless a line starting
# with that text exists (the base image's `(ALL)` line does not match), adds
# `user` to the sudo group and clears its password (e2b-dev/infra,
# packages/orchestrator/pkg/template/build/phases/finalize/configure.sh).
# Narrowing or deleting the sudoers rule in a RUN step below would therefore
# be undone by the platform and only *look* like hardening. Any tenant
# script can `sudo` to root inside the sandbox; isolation comes from the
# single-tenant, disposable microVM, not from this account (README.md,
# "Security model", has the evidence and the only real mitigation plan).
# runner.py only needs /tmp, the system python3/node/bash and read access
# to the root-owned, world-readable /runner.py, all of which work as `user`.
# E2B maps the last USER/WORKDIR to the sandbox defaults, including the
# start command. The useradd is a no-op on this base image (the account
# already exists) and only matters if the base is ever swapped for one
# without it (E2B's finalize phase would create it anyway).
RUN id -u user >/dev/null 2>&1 || useradd --create-home --shell /bin/bash user
USER user
WORKDIR /home/user

# runner.py is the sandbox's start command - passed to `e2b template create`
# as --cmd / --ready-cmd (README.md; e2b.toml records the same two values).
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["python3", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3).status==200 else 1)"]

# No CMD/ENTRYPOINT is declared on purpose: E2B's Dockerfile converter would
# turn one into a start command gated on a fixed 20s wait instead of the
# /health ready check. runner.py listens on 8080, the port
# E2bCodeExecutionClient.java is hardcoded to call.
