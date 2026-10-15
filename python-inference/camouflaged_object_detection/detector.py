"""C2FNet adapter matching the validated local CAMO inference protocol."""
from __future__ import annotations

import hashlib
import os
import threading
import time
from pathlib import Path

import numpy as np
from common import runtime

MODEL_NAME = "C2FNet(Res2Net50)"
WEIGHT_CANDIDATES = ("c2fnet.pth", "C2FNet-39.pth")
_inference_lock = threading.Lock()


def _build(weight_path):
    if weight_path is None:
        raise RuntimeError("C2FNet weights missing: place c2fnet.pth in RS_WEIGHTS_DIR")
    torch = runtime.torch_module()
    if torch is None:
        raise RuntimeError("PyTorch is unavailable")
    threads = int(os.environ.get("RS_THREADS", "4"))
    if threads < 1:
        raise ValueError("RS_THREADS must be positive")
    torch.set_num_threads(threads)
    from .model.C2FNet import C2FNet
    from torchvision import transforms
    device = runtime.pick_device()
    model = C2FNet(pretrained=False)
    runtime.load_state_dict_flexible(model, weight_path)
    model.to(device).eval()
    with weight_path.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    transform = transforms.Compose([
        transforms.Resize((352, 352)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])
    return {"model": model, "device": device, "transform": transform, "weights_sha256": digest}


_cache = runtime.WeightedModelCache(WEIGHT_CANDIDATES, _build)


def reload():
    _cache.reset()


def status():
    state = _cache.get()
    ready = "model" in state
    digest = state.get("weights_sha256")
    return {
        "algorithm": "camouflaged_object_detection", "model": MODEL_NAME,
        "weights": state.get("weights_path"), "weights_ready": ready,
        "weights_version": digest[:8] if digest else None, "weights_sha256": digest,
        "runtime_ready": ready, "error": state.get("error"),
        "device": state.get("device", "unavailable"),
    }


def run(image_path: Path):
    from PIL import Image
    image_path = Path(image_path).expanduser().resolve()
    started = time.perf_counter()
    rgb, meta = runtime.read_image_rgb(image_path)
    height, width = rgb.shape[:2]
    with _inference_lock:
        loaded = _cache.get()
        if "error" in loaded:
            raise RuntimeError(loaded["error"])
        torch = runtime.torch_module()
        tensor = loaded["transform"](Image.fromarray(rgb)).unsqueeze(0).to(loaded["device"])
        with torch.inference_mode():
            logits = loaded["model"](tensor)
            logits = torch.nn.functional.interpolate(logits, size=(height, width), mode="bilinear", align_corners=False)
            response = logits.sigmoid().cpu().numpy()[0, 0]
        if not np.isfinite(response).all():
            raise RuntimeError("Model produced non-finite values")
        response = (response - response.min()) / (response.max() - response.min() + 1e-8)
        mask = (response * 255).astype(np.uint8)
    mask_path, preview_path = runtime.write_mask(mask, image_path, meta, "camouflaged")
    return runtime.build_response(mask_path, preview_path, width, height, started,
                                  device=loaded["device"], weights_sha256=loaded["weights_sha256"])
