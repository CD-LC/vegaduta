variable "name_prefix" {
  type    = string
  default = "vegaduta"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.name_prefix))
    error_message = "name_prefix must be 2-31 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "vpc_cidr" {
  type        = string
  default     = "10.0.0.0/16"
  description = "VPC IPv4 CIDR (/16 to /20). Must not overlap peered or on-premises networks. Subnets are carved out of it with cidrsubnet(): private subnets use the lower half, public subnets the upper half."
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 20
    error_message = "vpc_cidr must be a valid IPv4 CIDR between /16 and /20."
  }
}

variable "az_count" {
  type        = number
  default     = 3
  description = "Number of availability zones to span (2-6). RDS and ElastiCache need at least two."
  validation {
    condition     = var.az_count >= 2 && var.az_count <= 6
    error_message = "az_count must be between 2 and 6."
  }
}

variable "enable_nat_gateway" {
  type        = bool
  default     = true
  description = "Create NAT gateways so private subnets (nodes) can reach the internet (image pulls, LLM provider APIs). false = private subnets have no internet route; you then need VPC endpoints for everything the cluster calls."
}

variable "single_nat_gateway" {
  type        = bool
  default     = false
  description = "One shared NAT gateway instead of one per AZ. Cheaper, but an AZ outage takes egress down for all private subnets. Default false (one per AZ)."
}

variable "eks_cluster_name" {
  type        = string
  default     = null
  description = "Optional EKS cluster name. When set, subnets get the kubernetes.io/cluster/<name> tag the AWS load balancer controller uses for discovery (the role/elb tags are always set)."
}

variable "enable_flow_logs" {
  type        = bool
  default     = false
  description = "Send VPC flow logs (ALL traffic) to a CloudWatch Logs group. Off by default because of the ingestion cost."
}

variable "flow_log_retention_days" {
  type        = number
  default     = 90
  description = "CloudWatch retention for the flow log group. Must be a value CloudWatch accepts (e.g. 30, 90, 365)."
}

variable "flow_log_kms_key_arn" {
  type        = string
  default     = null
  description = "Optional KMS key ARN for the flow log group (the key policy must allow the logs.<region>.amazonaws.com service)."
}

variable "tags" {
  type        = map(string)
  default     = {}
  description = "Tags applied to every resource (provider default_tags work too)."
}
