output "role_arn" {
  description = "Annotate the core, core-jobs and core-worker ServiceAccounts with eks.amazonaws.com/role-arn=<this> (chart value: serviceAccount.annotations in each chart)."
  value       = aws_iam_role.this.arn
}

output "role_name" {
  value = aws_iam_role.this.name
}

output "helm_values" {
  description = "Per-chart values (the umbrella chart passes sub-chart values by chart name)."
  value = {
    for chart in ["core", "core-jobs", "core-worker"] : chart => {
      serviceAccount = {
        annotations = {
          "eks.amazonaws.com/role-arn" = aws_iam_role.this.arn
        }
      }
    }
  }
}
