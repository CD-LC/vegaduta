"""Minimal FastAPI starter for the app-builder no-code platform.

Run: uvicorn main:app --host 0.0.0.0 --port 8000
"""

from fastapi import FastAPI

app = FastAPI(title="My App")


@app.get("/")
def read_root():
    return {"message": "Hello from your new app"}
