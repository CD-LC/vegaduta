terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.10"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }
}
