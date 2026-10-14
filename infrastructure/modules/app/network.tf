# Sieć prywatna środowiska. Cloud Run wychodzi do niej przez Direct VPC egress
# (bez VPC connectora), a Cloud SQL dostaje adres prywatny w tej samej sieci.

resource "google_compute_network" "main" {
  name                    = "zpi-${var.env}"
  auto_create_subnetworks = false

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "run" {
  name                     = "zpi-${var.env}-run"
  region                   = var.region
  network                  = google_compute_network.main.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true
}

# Zakres adresów zarezerwowany dla usług Google (Cloud SQL) połączony peeringiem.
resource "google_compute_global_address" "private_services" {
  name          = "zpi-${var.env}-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}
