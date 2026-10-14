module "app" {
  source = "../../modules/app"

  env                  = "stage"
  project_id           = var.project_id
  region               = var.region
  subnet_cidr          = "10.10.0.0/24"
  allow_public_ingress = var.allow_public_ingress

  db_tier              = "db-f1-micro"
  db_availability_type = "ZONAL"
}
