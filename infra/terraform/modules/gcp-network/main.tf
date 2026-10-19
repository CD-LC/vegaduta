# Everything in this module exists so that only the cloudflared tunnel VM
# (terraform/modules/gcp-tunnel-vm) can reach the internal-ingress Cloud Run
# services in gcp-compute-run - no service is directly public. Verify
# reachability end to end before relying on this: a VM
# reaching an internal Cloud Run service needs Private Google Access + a
# private DNS zone resolving run.app into Google's restricted range, which is
# what the last two resources here set up.

resource "google_project_service" "enabled" {
  for_each = toset([
    "run.googleapis.com",
    "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com",
    "vpcaccess.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "compute.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "binaryauthorization.googleapis.com",
    "cloudkms.googleapis.com",
    "dns.googleapis.com",
  ])
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_compute_network" "this" {
  project                 = var.project_id
  name                    = "${var.name_prefix}-vpc"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.enabled]
}

resource "google_compute_subnetwork" "this" {
  project                  = var.project_id
  name                     = "${var.name_prefix}-subnet"
  region                   = var.region
  network                  = google_compute_network.this.id
  ip_cidr_range            = "10.10.0.0/24"
  private_ip_google_access = true
}

# Cloud SQL private-IP requires a dedicated peered range, separate from the
# app subnet above - this is what google_service_networking_connection peers.
resource "google_compute_global_address" "sql_peering_range" {
  project       = var.project_id
  name          = "${var.name_prefix}-sql-peering-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.this.id
}

resource "google_service_networking_connection" "sql_peering" {
  network                 = google_compute_network.this.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql_peering_range.name]
}

# Serverless VPC Access connector - the bridge every Cloud Run service uses
# for internal-ingress traffic and for reaching Cloud SQL over private IP.
resource "google_vpc_access_connector" "this" {
  project       = var.project_id
  name          = "${var.name_prefix}-connector"
  region        = var.region
  network       = google_compute_network.this.id
  ip_cidr_range = "10.10.8.0/28"
  min_instances = 2
  max_instances = 3
  machine_type  = "e2-micro"
}

# Deny-all default, explicit allow only for the connector's own range and for
# IAP-based SSH (35.235.240.0/20) so the tunnel VM stays debuggable without
# opening a public SSH port on it.
resource "google_compute_firewall" "deny_all_ingress" {
  project   = var.project_id
  name      = "${var.name_prefix}-deny-all-ingress"
  network   = google_compute_network.this.id
  priority  = 65534
  direction = "INGRESS"
  deny { protocol = "all" }
  source_ranges = ["0.0.0.0/0"]
}

resource "google_compute_firewall" "allow_internal" {
  project   = var.project_id
  name      = "${var.name_prefix}-allow-internal"
  network   = google_compute_network.this.id
  priority  = 1000
  direction = "INGRESS"
  allow {
    protocol = "tcp"
  }
  source_ranges = [google_compute_subnetwork.this.ip_cidr_range, "10.10.8.0/28"]
}

resource "google_compute_firewall" "allow_iap_ssh" {
  project   = var.project_id
  name      = "${var.name_prefix}-allow-iap-ssh"
  network   = google_compute_network.this.id
  priority  = 1000
  direction = "INGRESS"
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
  source_ranges = ["35.235.240.0/20"]
}

# Private DNS zone so *.run.app resolves to Google's restricted VIP range
# (199.36.153.4/30) from inside this VPC instead of the public internet path
# - required for the tunnel VM to reach internal-ingress Cloud Run services.
resource "google_dns_managed_zone" "run_app_private" {
  project     = var.project_id
  name        = "${var.name_prefix}-run-app-private"
  dns_name    = "run.app."
  description = "Private zone resolving run.app to Google's restricted VIP range for internal Cloud Run ingress"
  visibility  = "private"
  private_visibility_config {
    networks {
      network_url = google_compute_network.this.id
    }
  }
}

resource "google_dns_record_set" "run_app_restricted_vip" {
  project      = var.project_id
  managed_zone = google_dns_managed_zone.run_app_private.name
  name         = "run.app."
  type         = "A"
  ttl          = 300
  rrdatas      = ["199.36.153.4", "199.36.153.5", "199.36.153.6", "199.36.153.7"]
}
