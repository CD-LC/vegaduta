# ollama

Optional bundled [Ollama](https://ollama.com) (MIT) that serves one local model as the free platform LLM pool.
StatefulSet with a persistent model volume; the model is pulled on first start and the pod turns Ready only
once it is usable. Off by default: enable it with `ollama.enabled=true` and point core at it with the
`core.env` bootstrap values in [`deploy/kind/values-kind-ollama.yaml`](../../../kind/values-kind-ollama.yaml).
Ollama has no authentication; the Service is ClusterIP and the NetworkPolicy admits only the core family.
Size memory for the model (`resources`), not the platform; see [docs/local-models.md](../../../../docs/local-models.md).
