resource "google_cloud_run_v2_service" "backend" {
  name     = "zpi-backend-${var.env}"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.backend.email

    scaling {
      min_instance_count = var.backend_min_instances
      max_instance_count = var.backend_max_instances
    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.run.id
      }
    }

    containers {
      # Placeholder: Cloud Run requires an image to create the service.
      # The real image is deployed by CI/CD (see ignore_changes below).
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      ports {
        container_port = 8080
      }

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.env
      }
      env {
        name  = "SPRING_DATASOURCE_URL"
        value = "jdbc:postgresql://${google_sql_database_instance.main.private_ip_address}:5432/${google_sql_database.app.name}?sslmode=require"
      }
      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = google_sql_user.app.name
      }
      env {
        # Firebase Authentication runs in the same GCP project
        name  = "FIREBASE_PROJECT_ID"
        value = var.project_id
      }
      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  # Obraz podmienia CI/CD. Terraform nie powinien go cofać przy każdym apply.
  lifecycle {
    ignore_changes = [template[0].containers[0].image]
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_version.db_password,
    google_secret_manager_secret_iam_member.backend_db_password,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public" {
  count = var.allow_public_ingress ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.backend.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
