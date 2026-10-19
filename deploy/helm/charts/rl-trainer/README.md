# rl-trainer

VegaDuta RL trainer (`services/rl-trainer`, port 8095). Optional (`global.components.rlTrainer`), internal
only, authenticated with `RL_TRAINER_INTERNAL_TOKEN` from the shared internal Secret. An LLM key
(`llmSecret.existingSecret`) is optional. Single replica on a ReadWriteOnce PVC.
