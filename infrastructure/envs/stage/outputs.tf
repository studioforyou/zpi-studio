output "backend_url" {
  value = module.app.backend_url
}

output "artifact_registry_repository" {
  value = module.app.artifact_registry_repository
}

output "db_private_ip" {
  value = module.app.db_private_ip
}

output "backend_service_account" {
  value = module.app.backend_service_account
}

output "cicd_service_account" {
  value = module.app.cicd_service_account
}

output "github_workload_identity_provider" {
  value = module.app.github_workload_identity_provider
}
