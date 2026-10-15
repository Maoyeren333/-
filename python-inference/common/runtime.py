"""Shared runtime for the C2FNet service. Errors never produce simulated masks."""
from __future__ import annotations

import hashlib
import os
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
WEIGHTS_DIR = Path(os.environ.get("RS_WEIGHTS_DIR") or PROJECT_ROOT / "weights").expanduser().resolve()
RESULT_DIR = Path(os.environ.get("RS_RESULT_DIR") or PROJECT_ROOT / "results").expanduser().resolve()


def torch_module():
    try:
        import torch
        return torch
    except ImportError:
        return None


def torch_available():
    return torch_module() is not None


def pick_device():
    torch = torch_module()
    preferred = os.environ.get("RS_DEVICE", "auto").lower()
    if preferred not in {"auto", "cpu", "cuda"}:
        raise RuntimeError("RS_DEVICE must be auto, cpu or cuda")
    if preferred == "cuda" and (torch is None or not torch.cuda.is_available()):
        raise RuntimeError("CUDA requested but unavailable; set RS_DEVICE=cpu")
    return "cuda" if preferred == "auto" and torch is not None and torch.cuda.is_available() else ("cpu" if preferred == "auto" else preferred)


def resolve_weights(names):
    for name in names:
        path = WEIGHTS_DIR / name
        if path.is_file():
            return path
    return None


def weight_signature(path):
    if path is None:
        return None
    stat = path.stat()
    return (str(path.resolve()), stat.st_size, stat.st_mtime_ns)


class WeightedModelCache:
    def __init__(self, candidates, builder):
        self.candidates = candidates
        self.builder = builder
        self.lock = threading.Lock()
        self.state = {}

    def get(self):
        with self.lock:
            path = resolve_weights(self.candidates)
            signature = (weight_signature(path), os.environ.get("RS_DEVICE", "auto"))
            if self.state.get("signature") == signature:
                return self.state
            try:
                built = self.builder(path)
                self.state = {**built, "signature": signature, "weights_path": str(path)}
            except Exception as exc:
                self.state = {"signature": signature, "error": f"{type(exc).__name__}: {exc}"}
            return self.state

    def reset(self):
        with self.lock:
            self.state = {}


def load_state_dict_flexible(model, path):
    torch = torch_module()
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    if isinstance(checkpoint, dict):
        for key in ("state_dict", "model"):
            if isinstance(checkpoint.get(key), dict):
                checkpoint = checkpoint[key]
                break
    if not isinstance(checkpoint, dict):
        raise ValueError("Expected a state_dict checkpoint")
    model.load_state_dict({key.removeprefix("module."): value for key, value in checkpoint.items()}, strict=True)


def read_image_rgb(path):
    from PIL import Image
    if not path.is_file():
        raise ValueError(f"Input image does not exist: {path}")
    if path.suffix.lower() in {".tif", ".tiff"}:
        import rasterio
        try:
            with rasterio.open(path) as src:
                if src.count not in (1, 3, 4) or any(dtype != "uint8" for dtype in src.dtypes):
                    raise ValueError("C2FNet accepts uint8 grayscale/RGB/RGBA TIFF; convert other band layouts or bit depths explicitly")
                bands = src.read([1, 2, 3]) if src.count >= 3 else np.repeat(src.read(1)[None], 3, axis=0)
                return np.transpose(bands, (1, 2, 0)), {"crs": src.crs, "transform": src.transform}
        except rasterio.errors.RasterioIOError as exc:
            raise ValueError(f"Cannot read TIFF: {path}") from exc
    try:
        with Image.open(path) as source:
            return np.asarray(source.convert("RGB")), {}
    except (OSError, ValueError) as exc:
        raise ValueError(f"Cannot read image: {path}") from exc


def write_mask(mask, image_path, meta, suffix):
    from PIL import Image
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    stem = f"{image_path.stem[:80]}_{suffix}_{uuid.uuid4().hex}_mask"
    tif_path, png_path = RESULT_DIR / (stem + ".tif"), RESULT_DIR / (stem + ".png")
    try:
        if meta.get("transform") is not None:
            import rasterio
            with rasterio.open(tif_path, "w", driver="GTiff", count=1, dtype="uint8",
                               height=mask.shape[0], width=mask.shape[1],
                               crs=meta.get("crs"), transform=meta["transform"], compress="lzw") as dst:
                dst.write(mask, 1)
        else:
            with tif_path.open("xb") as handle:
                Image.fromarray(mask).save(handle, format="TIFF")
        with png_path.open("xb") as handle:
            Image.fromarray(mask).save(handle, format="PNG")
    except Exception:
        tif_path.unlink(missing_ok=True)
        png_path.unlink(missing_ok=True)
        raise
    return tif_path, png_path


def build_response(mask_path, preview_path, width, height, started_at, *, device, weights_sha256):
    return {
        "mask_path": str(mask_path), "preview_path": str(preview_path),
        "width": int(width), "height": int(height), "class_count": 2,
        "inference_time_ms": int((time.perf_counter() - started_at) * 1000),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "model": "C2FNet(Res2Net50)", "weights_loaded": True, "simulated": False,
        "weights_version": weights_sha256[:8], "weights_sha256": weights_sha256,
        "device": device, "input_size": 352,
        "output_type": "per_image_normalized_grayscale",
        "notes": "0–255 grayscale foreground response; not a calibrated probability or binary mask",
    }
