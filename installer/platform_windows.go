package main

func dockerInstallHint() string {
	return "Install Docker Desktop for Windows: winget install Docker.DockerDesktop (enables WSL2 for you), or https://docs.docker.com/desktop/install/windows-install/"
}

func dockerStartHint() string {
	return "Open Docker Desktop from the Start menu and wait for 'Engine running'."
}

func dockerMemoryHint() string {
	return "Raise the WSL2 VM memory in Docker Desktop > Settings > Resources, or via %USERPROFILE%\\.wslconfig."
}
