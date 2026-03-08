variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "Primary region"
  type        = string
  default     = "us-central1"
}

variable "backend_image" {
  description = "Container image URI for backend Cloud Run service"
  type        = string
}

variable "executor_source_bucket" {
  description = "GCS bucket containing zipped Cloud Function source"
  type        = string
}

variable "executor_source_object" {
  description = "GCS object path for zipped Cloud Function source"
  type        = string
}

variable "executor_api_key" {
  description = "Shared API key used by backend to call executor"
  type        = string
  sensitive   = true
}
