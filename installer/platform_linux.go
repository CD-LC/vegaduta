package main

func dockerInstallHint() string {
	return "Install Docker Engine (https://docs.docker.com/engine/install/) or Docker Desktop for Linux."
}

func dockerStartHint() string {
	return "Start the daemon: sudo systemctl start docker (or open Docker Desktop)."
}

func dockerMemoryHint() string {
	return "Give the Docker engine more memory (Docker Desktop > Settings > Resources, or the VM running the engine)."
}
