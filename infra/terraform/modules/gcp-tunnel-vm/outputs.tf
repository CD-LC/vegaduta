output "instance_name" {
  value = google_compute_instance.tunnel.name
}

output "internal_ip" {
  value = google_compute_instance.tunnel.network_interface[0].network_ip
}
