# modules/aws-elasticache (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

An ElastiCache replication group, Valkey by default (`engine = "redis"` also works).

| Area | Choice |
|---|---|
| Topology | Cluster mode **disabled**: one primary plus `num_cache_clusters - 1` replicas, automatic failover and Multi-AZ when there are two or more nodes. Cluster mode is not supported by the app (the tenant-purge path uses `KEYS`). |
| Encryption | In transit (TLS) and at rest, always on. |
| Auth | An AUTH token, generated with the `random` provider unless you pass `auth_token` (sensitive), stored in a Secrets Manager secret as `{"password": "..."}`. The token is also in Terraform state: use an encrypted remote backend. |
| Network | Private subnets, security group with ingress only from `allowed_security_group_ids` on 6379, no egress rules. |

## Wiring into the chart

```yaml
global:
  valkey:
    host: <output primary_endpoint_address>
    port: 6379
    tls: true                  # sets REDIS_SSL=true; required, in-transit encryption is on
    existingSecret: vegaduta-valkey   # a Secret you create with key "password"
    # username: ""             # default user; ElastiCache RBAC users are not provisioned here
```

Sync `auth_secret_arn` into that Kubernetes Secret (External Secrets Operator or the Secrets Store CSI driver).
Set `valkey.enabled=false` so the bundled Valkey is not deployed.

Property binding for host, password and TLS is unit-tested in core; a real ElastiCache instance has not been
exercised.
