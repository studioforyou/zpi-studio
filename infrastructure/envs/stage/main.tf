module "app" {
  source = "../../modules/app"

  env                  = "stage"
  project_id           = var.project_id
  region               = var.region
  subnet_cidr          = "10.10.0.0/24"
  allow_public_ingress = var.allow_public_ingress

  db_tier              = "db-f1-micro"
  db_availability_type = "ZONAL"

  mail_from = var.mail_from
  # Web panel is not hosted yet: links open the local web panel (npm run dev) from the Android emulator.
  invitation_link_base_url = "http://10.0.2.2:5173/invite"
}
