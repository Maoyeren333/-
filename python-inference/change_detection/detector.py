"""变化检测（Change Detection）：STFF-GA 真实推理。

模型来源：`Spatio-Temporal Feature Fusion and Guide Aggregation Network for Remote
Sensing Change Detection` (IEEE TGRS 2024, https://github.com/NjustHGWei/STFF-GA)，
网络定义已移植到 `change_detection/model/`，仅调整导入路径并新增骨干预训练开关。

权重：把训练好的 checkpoint 放到 python-inference/weights/stff_ga.pth 即可启用；
缺失时使用随机初始化权重（链路可跑通，结果无判读意义，返回体 weights_loaded=False）。
权重文件被替换后无需重启服务：WeightedModelCache 会检测到签名变化并自动重建模型。

输入：双时相影像（A/B 两期）。见 common/runtime.resolve_change_pair 的三种约定。
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Dict, Optional

from common import runtime
from common.adapter import simulated_fallback

MODEL_NAME = "STFF-GA(ResNet18)"
SUFFIX = "change"
TRAINSIZE = 256  # 与官方 test.py 的 --trainsize 一致
MEAN = (0.5, 0.5, 0.5)
STD = (0.5, 0.5, 0.5)
WEIGHT_CANDIDATES = ("stff_ga.pth", "stff-ga.pth", "stffga.pth", "best.pth")


def _build(weight_path: Optional[Path]) -> Dict[str, Any]:
    from change_detection.model.network import STFFGA

    model = STFFGA(in_ch=3, backbone_pretrained=runtime.ALLOW_DOWNLOAD and weight_path is None)
    weights_loaded = False
    notes = "未找到权重文件，使用随机初始化权重，结果不具备判读意义"
    if weight_path is not None:
        weights_loaded, note = runtime.load_state_dict_flexible(model, weight_path)
        notes = f"已加载 {weight_path.name}" + (f"（{note}）" if note else "")
    elif runtime.ALLOW_DOWNLOAD:
        notes = "仅骨干为 ImageNet 预训练权重（RS_ALLOW_DOWNLOAD=1），检测头仍为随机初始化"
    model.eval()
    return {"model": model, "weights_loaded": weights_loaded, "notes": notes}


_cache = runtime.WeightedModelCache(WEIGHT_CANDIDATES, _build)


def reload() -> None:
    """丢弃缓存，下次请求重新加载权重。"""
    _cache.reset()


def run(image_path: Path, image_path_b: Optional[Path] = None) -> Dict[str, Any]:
    """对双时相影像做变化检测，输出变化区域掩膜（255=变化）。"""
    image_path = Path(image_path)
    started = time.perf_counter()
    try:
        torch = runtime.torch_module()
        if torch is None:
            raise RuntimeError("未安装 torch")

        path_a, path_b = runtime.resolve_change_pair(image_path, Path(image_path_b) if image_path_b else None)

        loaded = _cache.get()
        if "error" in loaded:
            raise RuntimeError(loaded["error"])
        model = loaded["model"]
        device = runtime.pick_device()
        model.to(device)

        rgb_a, meta = runtime.read_image_rgb(path_a)
        rgb_b, _ = runtime.read_image_rgb(path_b)
        if rgb_a.shape != rgb_b.shape:
            raise ValueError(f"两期影像尺寸不一致：{rgb_a.shape} vs {rgb_b.shape}")
        height, width = rgb_a.shape[:2]

        tensor_a = runtime.to_tensor(rgb_a, TRAINSIZE, MEAN, STD).to(device)
        tensor_b = runtime.to_tensor(rgb_b, TRAINSIZE, MEAN, STD).to(device)
        with torch.no_grad():
            _, output_map = model(tensor_a, tensor_b)  # 官方返回 (dfg_map, output_map)
        probability = torch.sigmoid(output_map)[0, 0].detach().cpu().numpy()
        probability = runtime.resize_map((probability * 255.0).astype("uint8"), height, width)
        mask = (probability >= 128).astype("uint8") * 255

        # 影像文件名已包含任务号与时间戳，结果名保持 {输入名}_change_mask.tif 即可唯一
        mask_path, preview_path = runtime.write_mask(mask, path_a, meta, SUFFIX)
        return runtime.build_response(
            mask_path, preview_path, width, height, 2, started,
            model=MODEL_NAME, weights_loaded=loaded["weights_loaded"], device=device,
            notes=f"{loaded['notes']}；输入 A={path_a.name} B={path_b.name}",
            weights_version=loaded.get("weights_version"),
        )
    except Exception as exc:
        return simulated_fallback(image_path, SUFFIX, MODEL_NAME, f"{type(exc).__name__}: {exc}")
