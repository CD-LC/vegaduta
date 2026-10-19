# modules/aws-network (reference module)

Status: **`tofu validate`d only.** Community-supported reference module; run a real `plan` before applying.

One VPC with `az_count` private and public subnets (default 3), an internet gateway, NAT gateways
(one per AZ by default, `single_nat_gateway = true` for one), an S3 gateway endpoint, an emptied default
security group and optional VPC flow logs to CloudWatch.

- Private subnets (EKS nodes, RDS, ElastiCache) have no public IPs. Public subnets hold load balancers and NAT.
- Subnets carry the `kubernetes.io/role/elb` / `internal-elb` tags (and the cluster tag when `eks_cluster_name`
  is set) for the AWS Load Balancer Controller.
- Nothing here opens an inbound rule.
- With `enable_nat_gateway = false` the nodes cannot pull images or reach LLM provider APIs unless you add VPC
  endpoints or another egress path.

## Wiring into the chart

No chart values: this module feeds the others. `private_subnet_ids` go to `aws-eks.subnet_ids`,
`aws-rds-postgres.subnet_ids` and `aws-elasticache.subnet_ids`; `vpc_id` goes to the latter two. Use the same
cluster name for `eks_cluster_name` here and `cluster_name` in `aws-eks`.

Outputs: `vpc_id`, `private_subnet_ids`, `public_subnet_ids`, `availability_zones`, `nat_public_ips`.
Consumed by `aws-eks`, `aws-rds-postgres`, `aws-elasticache`.
