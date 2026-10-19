package main

func dockerInstallHint() string {
	return "Install Docker Desktop for Mac (https://docs.docker.com/desktop/install/mac-install/), or: brew install --cask docker"
}

func dockerStartHint() string {
	return "Open the Docker Desktop app and wait until it reports the engine is running."
}

func dockerMemoryHint() string {
	return "Docker Desktop > Settings > Resources > Memory, then re-run the installer."
}
