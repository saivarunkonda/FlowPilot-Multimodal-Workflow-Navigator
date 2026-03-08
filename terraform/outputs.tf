output "backend_url" {
  value       = google_cloud_run_v2_service.backend.uri
  description = "Public URL of FlowPilot backend"
}

output "executor_function_uri" {
  value       = google_cloudfunctions2_function.executor.service_config[0].uri
  description = "HTTP URI for UI action executor function"
}

output "function_source_bucket" {
  value       = google_storage_bucket.function_source.name
  description = "Bucket to upload Cloud Function source archives"
}
