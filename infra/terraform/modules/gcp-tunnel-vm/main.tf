# The only GCP resource with any path to the public internet in this whole
# design - and even this one has no public IP itself (outbound-only to
# Cloudflare's edge). Everything else (terraform/modules/gcp-compute-run) is
# internal-ingress and reachable only from this VM via the VPC connector.

resource "google_compute_instance" "tunnel" {
  project      = var.project_id
  name         = "${var.name_prefix}-tunnel-vm"
  zone         = var.zone
  machine_type = "e2-micro" # Always Free tier, only in us-west1/us-central1/us-east1

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = 30 # Always Free covers up to 30GB-months standard persistent disk
      type  = "pd-standard"
    }
  }

  network_interface {
    subnetwork = var.subnet_id
    # Deliberately no access_config block - this VM has no public IP.
    # Outbound internet (for the tunnel's connection to Cloudflare, and for
    # apt/cloudflared install at boot) works via Cloud NAT or, at this scale,
    # via the default route - inbound is what's blocked, which is what matters.
  }

  service_account {
    email  = var.service_account_email
    scopes = ["cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  metadata = {
    enable-oslogin         = "TRUE" # SSH only via IAP + OS Login, never a static key
    block-project-ssh-keys = "TRUE"
  }

  metadata_startup_script = <<-EOT
    #!/bin/bash
    set -euo pipefail
    curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | gpg --dearmor -o /usr/share/keyrings/cloudflare-main.gpg
    echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main" | tee /etc/apt/sources.list.d/cloudflared.list
    apt-get update && apt-get install -y cloudflared

    TUNNEL_TOKEN=$(gcloud secrets versions access latest --secret="${var.tunnel_token_secret_id}")
    cloudflared service install "$TUNNEL_TOKEN"
    systemctl enable cloudflared
    systemctl restart cloudflared
  EOT
}
