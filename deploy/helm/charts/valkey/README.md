# valkey

Bundled single-node [Valkey](https://valkey.io) (BSD-3-Clause, Redis-protocol compatible; the official
`valkey/valkey` image, not a Bitnami chart). StatefulSet with AOF persistence, generated password
(Secret `<release>-valkey`, key `password`, kept on uninstall). For HA or managed caches, disable it
(`valkey.enabled=false`) and set `global.valkey.host/port/existingSecret`.
