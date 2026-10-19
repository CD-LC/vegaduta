# modules/aws-eks (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

An EKS cluster with one managed node group and the IAM OIDC provider that IRSA needs.

| Area | Choice |
|---|---|
| API endpoint | Private by default. `endpoint_public_access = true` requires `public_access_cidrs`; `0.0.0.0/0` is rejected. Terraform itself never talks to the Kubernetes API, but you (or CI) must reach it from inside the VPC, a VPN or a listed CIDR. |
| Auth | Access entries (`authentication_mode = "API"`, no `aws-auth` ConfigMap). The creator is admin while `bootstrap_creator_admin = true`; add operators in `admin_principal_arns` (not the creator itself: its entry exists already and a duplicate is a 409). Do not flip `bootstrap_creator_admin` on a live cluster: the provider treats it as a creation-time setting that forces a new cluster. To drop the creator's access later, delete its access entry with the AWS CLI. |
| Nodes | Managed node group `general`, encrypted gp3 root volume, IMDSv2 required with hop limit 1 (pods cannot reach the node role; they use IRSA). `desired_size` is ignored after creation so an autoscaler can own it. |
| Secrets | Optional envelope encryption of Kubernetes Secrets with `secrets_kms_key_arn`. |
| Logs | `api`, `audit`, `authenticator` to CloudWatch (90 days). |
| Add-ons | `vpc-cni` (network policy agent on by default, `enable_network_policy`, so the chart's default-deny policies are enforced), `kube-proxy`, `coredns`, and the EBS CSI driver with its own IRSA role (needed only for PersistentVolumes). |
| IRSA | `aws_iam_openid_connect_provider` (no thumbprint; needs aws provider >= 5.81). |

Not included: Karpenter / cluster autoscaler, AWS Load Balancer Controller, ingress, a gVisor/Kata sandbox pool.
NetworkPolicy enforcement relies on the VPC CNI network policy agent (add-on setting `enableNetworkPolicy`). That
setting was taken from the AWS docs and never run: confirm with a deny test after the first install.
Pin `kubernetes_version`; `null` takes whatever EKS defaults to at creation.

Outputs for other modules: `cluster_security_group_id` (to `aws-rds-postgres` / `aws-elasticache`
`allowed_security_group_ids`), `oidc_provider_arn` and `oidc_provider_url` (to `aws-irsa-core`).

## Wiring into the chart

The chart takes no values from this module. After `aws eks update-kubeconfig --name <cluster_name>` install the
chart with the values from the other modules (`aws-rds-postgres`, `aws-elasticache`, `aws-s3`, `aws-irsa-core`).
The default-deny NetworkPolicies the chart ships are enforced by the VPC CNI agent this module turns on.
