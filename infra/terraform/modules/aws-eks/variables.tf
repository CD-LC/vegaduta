variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.name_prefix))
    error_message = "name_prefix must be 2-31 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "cluster_name" {
  type        = string
  default     = null
  description = "EKS cluster name. null = \"<name_prefix>-eks\". Pass the same value to aws-network.eks_cluster_name for load balancer subnet discovery."
}

variable "kubernetes_version" {
  type        = string
  default     = null
  description = "Kubernetes minor version, e.g. \"1.33\". null lets EKS choose its current default at creation time; pin it in production so upgrades are deliberate."
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnets (at least two AZs) for the control plane ENIs and the node group (aws-network.private_subnet_ids)."
  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "subnet_ids needs at least two subnets in different availability zones."
  }
}

variable "endpoint_private_access" {
  type        = bool
  default     = true
  description = "Expose the API server inside the VPC. Nodes use this path."
}

variable "endpoint_public_access" {
  type        = bool
  default     = false
  description = "Also expose the API server on a public endpoint, restricted to public_access_cidrs. Default false (private cluster): reach it from a VPN, bastion or CI runner inside the VPC (or peered network)."
}

variable "public_access_cidrs" {
  type        = list(string)
  default     = []
  description = "CIDRs allowed to reach the public API endpoint. Required (and 0.0.0.0/0 rejected) when endpoint_public_access = true."
  validation {
    condition     = !contains(var.public_access_cidrs, "0.0.0.0/0")
    error_message = "public_access_cidrs must not contain 0.0.0.0/0; list the specific CIDRs that need API access."
  }
}

variable "admin_principal_arns" {
  type        = set(string)
  default     = []
  description = "IAM role/user ARNs granted AmazonEKSClusterAdminPolicy through EKS access entries. Must be known at plan time (static strings). The identity that creates the cluster is admin too while bootstrap_creator_admin = true."
}

variable "enable_network_policy" {
  type        = bool
  default     = true
  description = "Turn on the VPC CNI network policy agent so the chart's default-deny NetworkPolicies are enforced (add-on setting enableNetworkPolicy=true). Set false only if you install another enforcing CNI (Calico, Cilium); otherwise those policies are accepted but silently not enforced."
}

variable "bootstrap_creator_admin" {
  type        = bool
  default     = true
  description = "Give the IAM identity running terraform apply cluster-admin through an access entry. Treat as fixed at creation: the aws provider documents this access_config setting as forcing a new cluster, so flipping it later plans a destroy and recreate. Keep true and add operators in admin_principal_arns; to remove the creator's access afterwards delete its access entry (aws eks delete-access-entry), do not change this flag. While true, do NOT list the creator's own ARN in admin_principal_arns (that entry already exists; EKS answers 409)."
}

variable "secrets_kms_key_arn" {
  type        = string
  default     = null
  description = "Optional customer-managed KMS key ARN for envelope encryption of Kubernetes Secrets in etcd. null = AWS-owned key (EKS already encrypts etcd at rest)."
}

variable "enabled_cluster_log_types" {
  type        = list(string)
  default     = ["api", "audit", "authenticator"]
  description = "Control plane log types sent to CloudWatch Logs."
}

variable "log_retention_days" {
  type        = number
  default     = 90
  description = "Retention of the control plane log group."
}

variable "node_instance_types" {
  type        = list(string)
  default     = ["m6i.xlarge"]
  description = "Instance types of the managed node group."
}

variable "node_ami_type" {
  type        = string
  default     = "AL2023_x86_64_STANDARD"
  description = "AMI family; use AL2023_ARM_64_STANDARD together with Graviton instance types (container images must then be multi-arch)."
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_desired_size" {
  type        = number
  default     = 3
  description = "Initial size. Ignored after creation (changes are not applied) so an autoscaler can own it."
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "node_disk_size_gb" {
  type        = number
  default     = 100
  description = "Encrypted gp3 root volume size per node."
}

variable "node_labels" {
  type    = map(string)
  default = {}
}

variable "enable_ebs_csi_driver" {
  type        = bool
  default     = true
  description = "Install the EBS CSI driver add-on (with its own IRSA role). Needed for PersistentVolumes (bundled Postgres/Valkey/Redpanda). Set false if you use only managed services and no PVCs."
}

variable "tags" {
  type    = map(string)
  default = {}
}
