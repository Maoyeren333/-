"""伪装目标检测（Camouflaged Object Detection）：C2FNet 真实推理。

模型来源：`Context-aware Cross-level Fusion Network for Camouflaged Object Detection`
(IJCAI 2021, https://github.com/Ben57882/C2FNet-master)，网络定义已移植到
`camouflaged_object_detection/model/`，只做了导入路径与预训练开关的最小改动。

权重：把官方 checkpoint 放到 python-inference/weights/c2fnet.pth 即可启用；
缺失时使用随机初始化权重（结果无意义，但链路可跑通，返回体 weights_loaded=False）。
权重文件被替换后无需重启服务：WeightedModelCache 会检测到签名变化并自动重建模型。
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Dict, Optional

from common import runtime
from common.adapter import simulated_fallback

MODEL_NAME = "C2FNet(Res2Net50)"
SUFFIX = "camouflaged"
TESTSIZE = 352  # 与官方 MyTest.py 的 --testsize 一致
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)
WEIGHT_CANDIDATES = ("c2fnet.pth", "c2fnet-39.pth", "C2FNet-39.pth", "c2fnet39.pth")


def _build(weight_path: Optional[Path]) -> Dict[str, Any]:
    from camouflaged_object_detection.model.C2FNet import C2FNet

    model = C2FNet(channel=64, pretrained=runtime.ALLOW_DOWNLOAD)
    weights_loaded = False
    notes = "未找到权重文件，使用随机初始化权重，结果不具备判读意义"
    if weight_path is not None:
        weights_loaded, note = runtime.load_state_dict_flexible(model, weight_path)
        notes = f"已加载 {weight_path.name}" + (f"（{note}）" if note else "")
    model.eval()
    return {"model": model, "weights_loaded": weights_loaded, "notes": notes}


_cache = runtime.WeightedModelCache(WEIGHT_CANDIDATES, _build)


def reload() -> None:
    """丢弃缓存，下次请求重新加载权重。"""
    _cache.reset()


def run(image_path: Path, image_path_b: Optional[Path] = None) -> Dict[str, Any]:
    """对单幅影像做伪装目标检测，输出二值前景掩膜。"""
    image_path = Path(image_path)
    started = time.perf_counter()
    try:
        torch = runtime.torch_module()
        if torch is None:
            raise RuntimeError("未安装 torch")

        loaded = _cache.get()
        if "error" in loaded:
            raise RuntimeError(loaded["error"])
        model = loaded["model"]
        device = runtime.pick_device()
        model.to(device)

        rgb, meta = runtime.read_image_rgb(image_path)
        height, width = rgb.shape[:2]
        tensor = runtime.to_tensor(rgb, TESTSIZE, MEAN, STD).to(device)
        with torch.no_grad():
            logits = model(tensor)
        probability = torch.sigmoid(logits)[0, 0].detach().cpu().numpy()
        probability = runtime.resize_map((probability * 255.0).astype("uint8"), height, width)
        mask = (probability >= 128).astype("uint8") * 255

        mask_path, preview_path = runtime.write_mask(mask, image_path, meta, SUFFIX)
        return runtime.build_response(
            mask_path, preview_path, width, height, 2, started,
            model=MODEL_NAME, weights_loaded=loaded["weights_loaded"], device=device,
            notes=loaded["notes"], weights_version=loaded.get("weights_version"),
        )
    except Exception as exc:
        return simulated_fallback(image_path, SUFFIX, MODEL_NAME, f"{type(exc).__name__}: {exc}")
