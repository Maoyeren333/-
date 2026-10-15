"""Local C2FNet service. Run behind the authenticated platform backend."""
from pathlib import Path

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict

from camouflaged_object_detection import detector
from common import runtime

app = FastAPI(title="C2FNet Camouflaged Object Detection")
ALGORITHM = "camouflaged_object_detection"


class Request(BaseModel):
    model_config = ConfigDict(extra="forbid")
    image_path: str


@app.post("/inference/{algorithm}")
def inference(algorithm: str, req: Request):
    if algorithm != ALGORITHM:
        raise HTTPException(404, "Unsupported algorithm; only C2FNet is available")
    try:
        return {"algorithm": algorithm, **detector.run(Path(req.image_path))}
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(500, f"Image/result I/O failed: {exc}") from exc


@app.get("/health")
def health():
    item = detector.status()
    return {"status": "UP" if item["runtime_ready"] else "DEGRADED",
            "torch_available": runtime.torch_available(),
            "device": item["device"],
            "weights_dir": str(runtime.WEIGHTS_DIR),
            "model_ready": item["runtime_ready"], "error": item["error"]}


@app.get("/algorithms")
def algorithms():
    item = detector.status()
    return {"device": item["device"],
            "torch_available": runtime.torch_available(), "algorithms": [item]}


@app.post("/models/reload")
def reload_models():
    detector.reload()
    return {"status": "RELOADED", "algorithms": [ALGORITHM]}
