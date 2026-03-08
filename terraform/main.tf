provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  required_services = [
    "run.googleapis.com",
    "cloudfunctions.googleapis.com",
    "artifactregistry.googleapis.com",
    "firestore.googleapis.com",
    "aiplatform.googleapis.com",
    "cloudbuild.googleapis.com"
  ]
}

resource "google_project_service" "services" {
  for_each = toset(local.required_services)
  project  = var.project_id
  service  = each.key
}

resource "google_firestore_database" "default" {
  project                     = var.project_id
  name                        = "(default)"
  location_id                 = var.region
  type                        = "FIRESTORE_NATIVE"
  delete_protection_state     = "DELETE_PROTECTION_DISABLED"
  deletion_policy             = "DELETE"
  depends_on                  = [google_project_service.services]
}

resource "google_cloud_run_v2_service" "backend" {
  name     = "flowpilot-backend"
  location = var.region

  template {
    containers {
      image = var.backend_image
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "GOOGLE_CLOUD_LOCATION"
        value = var.region
      }
      env {
        name  = "ACTION_EXECUTOR_API_KEY"
        value = var.executor_api_key
      }
    }
  }

  depends_on = [google_project_service.services]
}

resource "google_cloud_run_service_iam_member" "backend_invoker" {
  project  = var.project_id
  location = var.region
  service  = google_cloud_run_v2_service.backend.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_storage_bucket" "function_source" {
  name                        = "${var.project_id}-flowpilot-functions"
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = true
  depends_on                  = [google_project_service.services]
}

resource "google_cloudfunctions2_function" "executor" {
  name     = "flowpilot-ui-executor"
  location = var.region

  build_config {
    runtime     = "nodejs20"
    entry_point = "executeWorkflow"
    source {
      storage_source {
        bucket = var.executor_source_bucket
        object = var.executor_source_object
      }
    }
  }

  service_config {
    available_memory      = "512M"
    timeout_seconds       = 120
    ingress_settings      = "ALLOW_ALL"
    max_instance_count    = 3
    environment_variables = {
      EXECUTOR_API_KEY = var.executor_api_key
    }
  }

  depends_on = [google_project_service.services]
}
