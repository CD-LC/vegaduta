# vegaduta-sdk (Python)

Standard-library-only client for the VegaDuta REST API. `vegaduta_sdk/generated/` (the client and
the TypedDict models) is generated from `docs/api/openapi.json` by `node sdk/generate.mjs`;
`vegaduta_sdk/runtime.py` is hand-written. See `../README.md` for quickstarts.

```
python -m unittest discover -s tests
```
