from pathlib import Path
from datetime import datetime, timezone

def create_simulated_mask(image_path: Path, suffix: str) -> dict:
    """稳定的模型适配契约；后续只需替换本函数为真实模型推理。"""
    output = image_path.with_name(f"{image_path.stem}_{suffix}_mask.tif")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.touch(exist_ok=True)
    return {"mask_path": str(output), "width": 0, "height": 0, "class_count": 1, "inference_time_ms": 120, "created_at": datetime.now(timezone.utc).isoformat(), "simulated": True}


def simulated_fallback(image_path: Path, suffix: str, model: str, reason: str) -> dict:
    """真实模型不可用时的降级返回：保留原契约字段，并显式标注降级原因。

    平台侧据此仍能拿到 mask_path 走完流程，同时 `simulated=True` 与 `notes` 让问题可见。
    """
    result = create_simulated_mask(Path(image_path), suffix)
    result.update({
        "preview_path": None,
        "model": model,
        "weights_loaded": False,
        "device": "-",
        "notes": f"已回退仿真实现：{reason}",
    })
    return result
