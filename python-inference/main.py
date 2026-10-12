from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from camouflaged_object_detection import detector as camo_detector
from change_detection import detector as change_detector
from semantic_segmentation import detector as semantic_detector

app = FastAPI(title='UAV Remote Sensing Inference Service')


class Request(BaseModel):
    image_path: str
    # 变化检测需要两期影像；也可把 image_path 指向含 A/B 子目录的文件夹
    image_path_b: Optional[str] = None


MODULES = {
    'camouflaged_object_detection': camo_detector,
    'change_detection': change_detector,
    'semantic_segmentation': semantic_detector,
}
RUNNERS = {name: module.run for name, module in MODULES.items()}


@app.post('/inference/{algorithm}')
def inference(algorithm: str, req: Request):
    if algorithm not in RUNNERS:
        raise HTTPException(404, 'unsupported algorithm')
    try:
        output = RUNNERS[algorithm](
            Path(req.image_path),
            Path(req.image_path_b) if req.image_path_b else None,
        )
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    return {'algorithm': algorithm, **output}


@app.get('/health')
def health():
    from common import runtime
    return {
        'status': 'UP',
        'torch_available': runtime.torch_available(),
        'device': runtime.pick_device(),
        'weights_dir': str(runtime.WEIGHTS_DIR),
    }


@app.get('/algorithms')
def algorithms():
    """给平台侧展示：算法、对应模型、权重是否就绪、当前权重版本。"""
    from common import runtime

    torch_ready = runtime.torch_available()
    items = []
    for name, module in MODULES.items():
        weight_path = runtime.resolve_weights(module.WEIGHT_CANDIDATES)
        items.append({
            'algorithm': name,
            'model': module.MODEL_NAME,
            'weights': str(weight_path) if weight_path else None,
            'weights_ready': weight_path is not None,
            'weights_version': runtime.weight_version(weight_path),
            'runtime_ready': torch_ready,
        })
    return {'device': runtime.pick_device(), 'torch_available': torch_ready, 'algorithms': items}


@app.post('/models/reload')
def reload_models():
    """丢弃三个算法的模型缓存，下次推理时重新加载权重。

    权重文件被替换时服务本身会通过签名检测自动重建，这个接口用于需要立即生效的场合
    （例如训练脚本刚写完权重、想立刻验证）。
    """
    for module in MODULES.values():
        module.reload()
    return {'status': 'RELOADED', 'algorithms': list(MODULES.keys())}
