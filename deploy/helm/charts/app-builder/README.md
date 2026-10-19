# app-builder

VegaDuta App Builder (`services/app-builder`, port 8091). Optional (`global.components.appBuilder`).
Public on `<global.hosts.builder>.<global.domain>`; calls core and code-intel in-cluster. Its Fernet key
`APP_BUILDER_ENCRYPTION_KEY` (tenant GitHub PATs) is separate from core's `APP_ENCRYPTION_KEY` and, like it,
must never change once data exists. Single replica on a ReadWriteOnce PVC.
