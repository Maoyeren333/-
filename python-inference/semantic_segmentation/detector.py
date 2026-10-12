"""语义分割（Semantic Segmentation）：ST-UNet 真实推理。

模型来源：`Swin Transformer Embedding UNet for Remote Sensing Image Semantic
Segmentation` (IEEE TGRS 2022, https://github.com/hexin-ustc/ST-UNet-main)，网络定义已
移植到 `semantic_segmentation/model/`。官方压缩包缺少 SoftPool 模块，`model/softpool.py`
按 SoftPool 论文等价定义补齐。

权重的官方保存格式为 {'model': state_dict}，由 runtime.load_state_dict_flexible 自动拆包。
放置到 python-inference/weights/st_unet.pth 即可启用；缺失时使用随机初始化权重。
权重文件被替换后无需重启服务：WeightedModelCache 会检测到签名变化并自动重建模型。
"""

from __future__ import annotations

import copy
import time
from pathlib import Path
from typing import Any, Dict, Optional

from common import runtime
from common.adapter import simulated_fallback

MODEL_NAME = "ST-UNet(R50-ViT-B/16)"
SUFFIX = "semantic"
IMG_SIZE = 256          # 与官方 test.py 的 --img_size 一致
VIT_NAME = "R50-ViT-B_16"
N_SKIP = 3              # 官方 test.py 默认 skip 数
NUM_CLASSES = 6         # 对应 ISPRS Vaihingen 六类
WEIGHT_CANDIDATES = ("st_unet.pth", "st-unet.pth", "stunet.pth", "epoch_99.pth")

# ISPRS Vaihingen 类别配色（与官方数据集标注含义一致，仅用于预览图着色）
COLORMAP = {
    0: (255, 255, 255),   # 不透水面
    1: (0, 0, 255),       # 建筑物
    2: (0, 255, 255),     # 低矮植被
    3: (0, 255, 0),       # 树木
    4: (255, 255, 0),     # 车辆
    5: (255, 0, 0),       # 背景/杂物
}


def _build_config():
    """按官方 test.py 的方式取配置并覆盖关键项，避免共享 ConfigDict 被反复改写。"""
    from semantic_segmentation.model.vit_seg_configs import get_r50_b16_config

    config = copy.deepcopy(get_r50_b16_config())
    config.n_classes = NUM_CLASSES
    config.n_skip = N_SKIP
    config.patches.grid = (int(IMG_SIZE / 16), int(IMG_SIZE / 16))
    return config


def _build(weight_path: Optional[Path]) -> Dict[str, Any]:
    from semantic_segmentation.model.vit_seg_modeling import VisionTransformer

    config = _build_config()
    model = VisionTransformer(config, img_size=IMG_SIZE, num_classes=config.n_classes)
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
    """对单幅影像做多类别语义分割，输出 0..NUM_CLASSES-1 的类别掩膜。"""
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
        # ST-UNet 使用 0-1 输入，不做 ImageNet 标准化（与官方 dataset_synapse.py 一致）
        tensor = runtime.to_tensor(rgb, IMG_SIZE, (0.0, 0.0, 0.0), (1.0, 1.0, 1.0)).to(device)
        with torch.no_grad():
            logits = model(tensor)
        labels = torch.argmax(logits, dim=1)[0].detach().cpu().numpy().astype("uint8")
        labels = runtime.resize_map(labels, height, width, nearest=True)
        present = int(len(set(labels.flatten().tolist())))

        mask_path, preview_path = runtime.write_mask(labels, image_path, meta, SUFFIX, colormap=COLORMAP)
        return runtime.build_response(
            mask_path, preview_path, width, height, NUM_CLASSES, started,
            model=MODEL_NAME, weights_loaded=loaded["weights_loaded"], device=device,
            notes=f"{loaded['notes']}；本图实际出现 {present} 个类别",
            weights_version=loaded.get("weights_version"),
        )
    except Exception as exc:
        return simulated_fallback(image_path, SUFFIX, MODEL_NAME, f"{type(exc).__name__}: {exc}")
