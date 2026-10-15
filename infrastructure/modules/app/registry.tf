resource "google_artifact_registry_repository" "backend" {
  location      = var.region
  repository_id = "zpi-studio"
  format        = "DOCKER"

  depends_on = [google_project_service.required]
}
