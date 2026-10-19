# Azure equivalent of the gcp-network module. Container Apps
# environment gets its own delegated subnet (VNet integration, matching
# gcp-network's Serverless VPC Access connector); Postgres Flexible Server
# gets its own delegated subnet + private DNS zone (matching Cloud SQL's
# private-IP-only posture). No public IP anywhere in this module.

resource "azurerm_virtual_network" "this" {
  name                = "${var.name_prefix}-vnet"
  resource_group_name = var.resource_group_name
  location            = var.location
  address_space       = [var.vnet_cidr]
}

resource "azurerm_subnet" "container_apps" {
  name                 = "${var.name_prefix}-containerapps"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.container_apps_subnet_cidr]

  delegation {
    name = "containerapps-delegation"
    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "database" {
  name                 = "${var.name_prefix}-database"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.database_subnet_cidr]

  delegation {
    name = "postgres-delegation"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# Plain subnet, no delegation — private endpoints (e.g. Azure Cache for
# Redis) can't share a subnet with a service-delegated one, so this gets
# its own. Everything landing here is reachable only from inside the VNet.
resource "azurerm_subnet" "private_endpoints" {
  name                 = "${var.name_prefix}-private-endpoints"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.private_endpoints_subnet_cidr]
}

# Private DNS zone Postgres Flexible Server needs for VNet-integrated
# (private-access) mode — equivalent to gcp-network's private services
# peering + managed DNS.
resource "azurerm_private_dns_zone" "postgres" {
  name                = "${var.name_prefix}.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                  = "${var.name_prefix}-postgres-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  virtual_network_id    = azurerm_virtual_network.this.id
}

# NSG on the Container Apps subnet.
#
# DELIBERATELY NOT a blanket "deny all internet inbound" rule here, unlike
# what you'd reach for on a plain VM subnet (or what gcp-network does with
# its GCP firewall rules). Azure Container Apps' platform itself needs
# specific inbound allowances on this subnet to run the ingress
# controller/health-probing/management-plane traffic — the exact required
# rule set differs for an internal-only vs. external-capable environment
# and is the kind of detail that needs verifying against Microsoft's
# current published requirements at apply time, not asserted from memory
# here. Getting this wrong in the "too restrictive" direction silently
# breaks ingress (including any externally-exposed app) in a way that's
# confusing to debug from the Container App side.
#
# What IS safe to assert and is set below: explicit allow for intra-VNet
# traffic (so core/keycloak/pgbouncer/redis reach each other), and Azure's
# own NSG default rules (AllowVnetInBound priority 65000, DenyAllInBound
# priority 65500) still apply beneath this — i.e. non-VNet inbound is
# already denied by default without needing a custom rule that risks also
# catching the platform's own required traffic.
#
# TODO before production use: pull the current required NSG rules for
# Container Apps VNet integration from
# https://learn.microsoft.com/azure/container-apps/networking and add them
# explicitly here instead of relying on Azure's default deny.
resource "azurerm_network_security_group" "container_apps" {
  name                = "${var.name_prefix}-containerapps-nsg"
  resource_group_name = var.resource_group_name
  location            = var.location

  security_rule {
    name                       = "allow-vnet-inbound"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "VirtualNetwork"
  }
}

resource "azurerm_subnet_network_security_group_association" "container_apps" {
  subnet_id                 = azurerm_subnet.container_apps.id
  network_security_group_id = azurerm_network_security_group.container_apps.id
}
