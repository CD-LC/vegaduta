{{- define "voice-agent.guard" -}}
{{- include "vegaduta-lib.serviceGuard" . -}}
{{- $lk := (.Values.global | default dict).livekit | default dict -}}
{{- if not $lk.internalUrl -}}
{{- fail "voice-agent needs global.livekit.internalUrl (ws(s):// URL of your LiveKit server, reachable from the pod)" -}}
{{- end -}}
{{- if not $lk.existingSecret -}}
{{- fail "voice-agent needs global.livekit.existingSecret (Secret with LIVEKIT_API_KEY and LIVEKIT_API_SECRET)" -}}
{{- end -}}
{{- end -}}
