"""推理服务公共运行时：设备选择、权重解析、影像读写与标准返回体。

设计原则：
1. 本模块不依赖 torch 即可导入，保证 FastAPI 服务在没有深度学习依赖时仍能启动（此时各算法回退为仿真实现）。
2. 不改变既有接口契约（`mask_path` / `width` / `height` / `class_count` / `inference_time_ms` / `created_at`），只在其上追加字段。
"""

from __future__ import annotations

import hashlib
import os
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Sequence, Tuple

import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
WEIGHTS_DIR = Path(os.environ.get("RS_WEIGHTS_DIR") or (PROJECT_ROOT / "weights"))
RESULT_DIR = Path(os.environ.get("RS_RESULT_DIR") or (PROJECT_ROOT / "results"))

_torch_cache: Any = None
_torch_checked = False

# 是否允许模型在构建时联网下载官方预训练骨干权重（Res2Net / ResNet18）。
# 默认关闭，避免离线环境下推理请求被网络超时拖住。
ALLOW_DOWNLOAD = os.environ.get("RS_ALLOW_DOWNLOAD", "0") == "1"


def torch_module():
    """惰性导入 torch；不可用时返回 None。"""
    global _torch_cache, _torch_checked
    if not _torch_checked:
        _torch_checked = True
        try:
            import torch  # noqa: WPS433 (运行时按需导入)
            _torch_cache = torch
        except Exception:
            _torch_cache = None
    return _torch_cache


def torch_available() -> bool:
    return torch_module() is not None


def pick_device() -> str:
    """设备选择：RS_DEVICE 优先，其次 CUDA，最后 CPU。"""
    torch = torch_module()
    preferred = os.environ.get("RS_DEVICE", "auto").lower()
    if preferred not in ("", "auto"):
        return preferred
    if torch is not None and torch.cuda.is_available():
        return "cuda"
    return "cpu"


def resolve_weights(names: Sequence[str]) -> Optional[Path]:
    """在权重目录中按候选文件名查找权重；找不到返回 None。"""
    if not WEIGHTS_DIR.exists():
        return None
    lowered = {p.name.lower(): p for p in WEIGHTS_DIR.iterdir() if p.is_file()}
    for name in names:
        hit = lowered.get(name.lower())
        if hit is not None:
            return hit
    return None


def weight_signature(path: Optional[Path]) -> str:
    """权重的轻量签名（文件名 + 大小 + 修改时间）。

    每次推理只需一次 stat，用来判断权重是否被替换；签名变化时重建模型，
    这样训练完把新权重复到 weights/ 就能立即生效，无需重启服务。
    """
    if path is None:
        return "none"
    try:
        info = path.stat()
        return f"{path.name}:{info.st_size}:{info.st_mtime_ns}"
    except OSError:
        return "none"


_version_cache: Dict[str, str] = {}


def weight_version(path: Optional[Path], length: int = 8) -> Optional[str]:
    """权重内容短哈希，用于在接口返回体里标识当前实际使用的版本。

    哈希结果按签名缓存，同一份权重不会重复计算；仅在权重变化时重算一次。
    """
    if path is None:
        return None
    signature = weight_signature(path)
    cached = _version_cache.get(signature)
    if cached is not None:
        return cached
    try:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        version = digest.hexdigest()[:length]
    except OSError:
        return None
    _version_cache[signature] = version
    return version


class WeightedModelCache:
    """带权重变更检测的模型缓存。

    - 第一次调用时构建模型；
    - 权重文件出现、被替换或删除时（签名变化）自动重建，实现热更新；
    - 召回构建失败原因；签名未变时不会反复重试，避免拖慢请求。
    """

    def __init__(self, candidates: Sequence[str], builder: Callable[[Optional[Path]], Dict[str, Any]]):
        self._candidates = tuple(candidates)
        self._builder = builder
        self._lock = threading.Lock()
        self._state: Dict[str, Any] = {}

    def _signature(self) -> str:
        return weight_signature(resolve_weights(self._candidates))

    def get(self) -> Dict[str, Any]:
        signature = self._signature()
        state = self._state
        if state.get("signature") == signature and ("model" in state or "error" in state):
            return state

        with self._lock:
            signature = self._signature()
            state = self._state
            if state.get("signature") == signature and ("model" in state or "error" in state):
                return state
            weight_path = resolve_weights(self._candidates)
            try:
                built = self._builder(weight_path)
                self._state = {
                    **built,
                    "signature": signature,
                    "weights_path": str(weight_path) if weight_path else None,
                    "weights_version": weight_version(weight_path),
                    "loaded_at": datetime.now(timezone.utc).isoformat(),
                }
            except Exception as exc:  # 依赖缺失、结构不兼容等都在这里暴露
                self._state = {"signature": signature, "error": f"{type(exc).__name__}: {exc}"}
            return self._state

    def reset(self) -> None:
        """强制丢弃缓存，下次调用重新构建（训练完想立即生效时可以调）。"""
        with self._lock:
            self._state = {}


def load_state_dict_flexible(model, weight_path: Path) -> Tuple[bool, str]:
    """载入权重，兼容 DataParallel 前缀与 {'model': ...} / {'state_dict': ...} 包装。"""
    torch = torch_module()
    checkpoint = torch.load(str(weight_path), map_location="cpu")
    if isinstance(checkpoint, dict):
        for key in ("model", "state_dict", "net", "weights"):
            if key in checkpoint and hasattr(checkpoint[key], "keys"):
                checkpoint = checkpoint[key]
                break
    cleaned = {}
    for key, value in checkpoint.items():
        cleaned[key[7:] if key.startswith("module.") else key] = value
    missing, unexpected = model.load_state_dict(cleaned, strict=False)
    note = ""
    if missing:
        note = f"缺失 {len(missing)} 个参数"
    if unexpected:
        note += f"{'；' if note else ''}多余 {len(unexpected)} 个参数"
    return (not missing), note


def read_image_rgb(path: Path) -> Tuple[np.ndarray, Dict[str, Any]]:
    """读取影像为 uint8 RGB（H, W, 3）。支持 GeoTIFF 与常见图片格式。

    返回 (rgb, meta)，meta 中携带 crs/transform/count，用于把结果写回同地理参考的 tif。
    """
    meta: Dict[str, Any] = {}
    suffix = path.suffix.lower()
    if suffix in (".tif", ".tiff"):
        try:
            import rasterio

            with rasterio.open(path) as src:
                count = src.count
                if count >= 3:
                    bands = src.read([1, 2, 3])
                else:
                    bands = np.repeat(src.read(1)[None, :, :], 3, axis=0)
                meta["crs"] = src.crs
                meta["transform"] = src.transform
                meta["profile"] = src.profile
                arr = np.transpose(bands, (1, 2, 0)).astype(np.float32)
                rgb = _stretch_to_uint8(arr)
                return rgb, meta
        except Exception:
            meta = {}

    from PIL import Image

    with Image.open(path) as img:
        rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
    return rgb, meta


def _stretch_to_uint8(arr: np.ndarray) -> np.ndarray:
    """把栅格转成 uint8：整型按位深压缩；浮点按 2%-98% 分位拉伸，避免极值压暗。"""
    if arr.dtype == np.uint8:
        return arr
    if np.issubdtype(arr.dtype, np.integer):
        info = np.iinfo(arr.dtype)
        scale = 255.0 / float(info.max)
        return np.clip(arr.astype(np.float32) * scale, 0, 255).astype(np.uint8)
    finite = arr[np.isfinite(arr)]
    if finite.size == 0:
        return np.zeros(arr.shape, dtype=np.uint8)
    lo, hi = np.percentile(finite, [2, 98])
    if hi <= lo:
        lo, hi = float(finite.min()), float(finite.max())
    if hi <= lo:
        return np.zeros(arr.shape, dtype=np.uint8)
    scaled = np.clip((arr - lo) / (hi - lo), 0, 1) * 255.0
    return np.nan_to_num(scaled).astype(np.uint8)


def to_tensor(rgb: np.ndarray, size: Optional[int], mean: Sequence[float], std: Sequence[float]):
    """uint8 RGB → (1, 3, H, W) 归一化 float32 张量。size 为 None 时保持原尺寸。"""
    torch = torch_module()
    from PIL import Image

    image = Image.fromarray(rgb)
    if size is not None:
        image = image.resize((size, size), Image.BILINEAR)
    arr = np.asarray(image, dtype=np.float32) / 255.0
    mean_arr = np.asarray(mean, dtype=np.float32)
    std_arr = np.asarray(std, dtype=np.float32)
    arr = (arr - mean_arr) / std_arr
    tensor = torch.from_numpy(np.transpose(arr, (2, 0, 1))).unsqueeze(0).contiguous()
    return tensor


def resize_map(array: np.ndarray, height: int, width: int, nearest: bool = False) -> np.ndarray:
    """把预测结果缩放回原始影像尺寸。"""
    from PIL import Image

    mode = Image.NEAREST if nearest else Image.BILINEAR
    img = Image.fromarray(array)
    return np.asarray(img.resize((width, height), mode))


def output_paths(image_path: Path, suffix: str) -> Tuple[Path, Path]:
    """结果 tif 与预览 png 路径；保持与仿真实现一致的 *_mask.tif 命名。"""
    target_dir = RESULT_DIR if os.environ.get("RS_RESULT_DIR") else image_path.parent
    target_dir.mkdir(parents=True, exist_ok=True)
    stem = f"{image_path.stem}_{suffix}_mask"
    return target_dir / f"{stem}.tif", target_dir / f"{stem}.png"


def write_mask(mask: np.ndarray, image_path: Path, meta: Dict[str, Any], suffix: str,
               colormap: Optional[Dict[int, Tuple[int, int, int]]] = None) -> Tuple[Path, Path]:
    """写出 mask：GeoTIFF 保留原地理参考，同时输出一张 png 预览便于前端展示。"""
    tif_path, png_path = output_paths(image_path, suffix)
    mask_u8 = mask.astype(np.uint8)

    written_by_rasterio = False
    if meta.get("crs") is not None and meta.get("transform") is not None:
        try:
            import rasterio

            profile = meta.get("profile") or {}
            profile = {
                "driver": "GTiff",
                "count": 1,
                "dtype": "uint8",
                "height": mask_u8.shape[0],
                "width": mask_u8.shape[1],
                "crs": meta["crs"],
                "transform": meta["transform"],
                "compress": "lzw",
            }
            with rasterio.open(tif_path, "w", **profile) as dst:
                dst.write(mask_u8, 1)
            written_by_rasterio = True
        except Exception:
            written_by_rasterio = False

    from PIL import Image

    if not written_by_rasterio:
        Image.fromarray(mask_u8).save(tif_path)

    Image.fromarray(_colorize(mask_u8, colormap)).save(png_path)
    return tif_path, png_path


def _colorize(mask_u8: np.ndarray, colormap: Optional[Dict[int, Tuple[int, int, int]]]) -> np.ndarray:
    if colormap is None or mask_u8.ndim != 2:
        return mask_u8
    rgb = np.zeros((*mask_u8.shape, 3), dtype=np.uint8)
    for value, color in colormap.items():
        rgb[mask_u8 == value] = color
    return rgb


def build_response(mask_path: Path, preview_path: Optional[Path], width: int, height: int,
                   class_count: int, started_at: float, *, model: str, weights_loaded: bool,
                   device: str, notes: str = "", simulated: bool = False,
                   weights_version: Optional[str] = None) -> Dict[str, Any]:
    """统一返回体：保留原契约字段，并补充模型、权重状态与权重版本，便于前端与排障。"""
    return {
        "mask_path": str(mask_path),
        "preview_path": str(preview_path) if preview_path else None,
        "width": int(width),
        "height": int(height),
        "class_count": int(class_count),
        "inference_time_ms": int((time.perf_counter() - started_at) * 1000),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "simulated": bool(simulated),
        "model": model,
        "weights_loaded": bool(weights_loaded),
        "weights_version": weights_version,
        "device": device,
        "notes": notes,
    }


def resolve_change_pair(image_path: Path, image_path_b: Optional[Path]) -> Tuple[Path, Path]:
    """解析变化检测的双时相输入。

支持三种约定：
1. 显式传入 image_path_b；
2. image_path 为形如 "a.tif|b.tif" 或 "a.tif,b.tif" 的成对字符串；
3. image_path 为目录，且目录下包含 A/B、before/after、T1/T2 或 time1/time2 子目录。
"""
    if image_path_b is not None:
        return image_path, image_path_b

    raw = str(image_path)
    for sep in ("|", ","):
        if sep in raw:
            first, second = [part.strip() for part in raw.split(sep, 1)]
            if first and second:
                return Path(first), Path(second)

    if image_path.is_dir():
        for first_name, second_name in (("A", "B"), ("a", "b"), ("before", "after"),
                                        ("T1", "T2"), ("time1", "time2"), ("pre", "post")):
            first_dir, second_dir = image_path / first_name, image_path / second_name
            if first_dir.is_dir() and second_dir.is_dir():
                first_files = sorted(p for p in first_dir.iterdir() if p.is_file()
                                     and p.suffix.lower() in (".tif", ".tiff", ".png", ".jpg", ".jpeg"))
                if first_files:
                    name = first_files[0].name
                    candidate = second_dir / name
                    if candidate.exists():
                        return first_files[0], candidate
                    second_files = sorted(p for p in second_dir.iterdir() if p.is_file())
                    if second_files:
                        return first_files[0], second_files[0]
    raise ValueError(
        "变化检测需要两期影像：请同时传入 image_path_b，或让 image_path 指向包含 A/B 两期子目录的文件夹"
    )
