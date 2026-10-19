output "backend_url" {
  description = "Adres backendu na Cloud Run."
  value       = google_cloud_run_v2_service.backend.uri
}

output "artifact_registry_repository" {
  description = "Repozytorium obrazów Docker, do którego CI wysyła obrazy."
  value       = google_artifact_registry_repository.backend.name
}

output "db_private_ip" {
  description = "Prywatny adres Cloud SQL."
  value       = google_sql_database_instance.main.private_ip_address
}

output "backend_service_account" {
  description = "Service account, na którym działa backend. Potrzebny przy uprawnieniach dla CI."
  value       = google_service_account.backend.email
}

output "cicd_service_account" {
  description = "Service account używany przez GitHub Actions (GCP_SERVICE_ACCOUNT)."
  value       = google_service_account.cicd.email
}

output "github_workload_identity_provider" {
  description = "Pełna nazwa providera WIF dla GitHub Actions (GCP_WORKLOAD_IDENTITY_PROVIDER)."
  value       = google_iam_workload_identity_pool_provider.github.name
}
