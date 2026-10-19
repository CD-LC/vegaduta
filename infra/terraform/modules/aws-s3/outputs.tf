output "bucket_name" {
  description = "global.objectStorage.bucket"
  value       = aws_s3_bucket.this.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.this.arn
}

output "region" {
  description = "global.objectStorage.region"
  value       = aws_s3_bucket.this.region
}

output "helm_values" {
  description = "Values to merge into the vegaduta chart. No access keys: the core ServiceAccount is annotated with the aws-irsa-core role."
  value = {
    global = {
      objectStorage = {
        type            = "s3"
        endpoint        = "https://s3.${aws_s3_bucket.this.region}.amazonaws.com"
        region          = aws_s3_bucket.this.region
        bucket          = aws_s3_bucket.this.bucket
        pathStyleAccess = false
        credentials     = "default"
      }
    }
  }
}
