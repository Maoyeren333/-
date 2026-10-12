# Python 推理服务

FastAPI 服务，负责运行三个遥感解译算法的**真实深度模型**（不再是仿真实现）。

## 目录结构

```
python-inference/
├── main.py                          # FastAPI 入口：/inference/{algorithm}、/health、/algorithms
├── common/
│   ├── adapter.py                   # 仿真降级实现（真实推理不可用时的兜底）
│   └── runtime.py                   # 设备选择、权重解析、影像读写、统一返回体
├── camouflaged_object_detection/
│   ├── detector.py                  # C2FNet 推理
│   └── model/                       # C2FNet.py / Res2Net_v1b.py / tensor_ops.py（官方代码移植）
├── change_detection/
│   ├── detector.py                  # STFF-GA 推理（双时相输入）
│   └── model/                       # network.py / backbones/resnet.py
├── semantic_segmentation/
│   ├── detector.py                  # ST-UNet 推理
│   └── model/                       # vit_seg_modeling*.py / softpool.py（补齐缺失模块）
└── weights/                         # 权重放置目录，见 weights/README.md
```

## 启动

```bash
cd python-inference
python -m pip install -r requirements.txt          # 国内可加 -i https://pypi.tuna.tsinghua.edu.cn/simple
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
```

## 接口

### `POST /inference/{algorithm}`

```json
{ "image_path": "/abs/path/a.png", "image_path_b": "/abs/path/b.png" }
```

- `algorithm` ∈ `camouflaged_object_detection` | `change_detection` | `semantic_segmentation`
- `image_path_b` 仅变化检测需要（双时相影像）
- 返回体在原有契约上扩展：

```json
{
  "mask_path": "..._mask.tif",
  "preview_path": "..._mask.png",
  "width": 256, "height": 256, "class_count": 2,
  "inference_time_ms": 581,
  "created_at": "2026-10-12T12:58:37Z",
  "simulated": false,
  "model": "STFF-GA(ResNet18)",
  "weights_loaded": false,
  "device": "cpu",
  "notes": "未找到权重文件，使用随机初始化权重，结果不具备判读意义"
}
```

- `simulated=true` 表示已降级到仿真实现，`notes` 里带原因（例如未安装 torch）。
- 原契约字段 `mask_path / width / height / class_count / inference_time_ms / created_at` 全部保留。
- GeoTIFF 输入会保留坐标系与仿射变换写出结果 tif；同时输出 PNG 预览供前端展示。

### 变化检测的双时相输入约定

以下三种写法等价：

1. 同时传 `image_path` 与 `image_path_b`；
2. `image_path` 写成 `"a.tif|b.tif"` 或 `"a.tif,b.tif"`；
3. `image_path` 指向一个目录，目录下含 `A/B`、`a/b`、`before/after`、`T1/T2` 子目录
   （例如 WHU-CD-256 的 `test/A`、`test/B`）。

### 其他

- `GET /health`：服务状态、torch 是否可用、设备、权重目录。
- `GET /algorithms`：三个算法对应的模型与权重是否就绪。

## 算法与预处理

| 算法 | 模型 | 输入尺寸 | 归一化 | 输出 |
| --- | --- | --- | --- | --- |
| 伪装目标检测 | C2FNet（Res2Net50） | 352×352 | ImageNet mean/std | 二值前景掩膜（255=目标） |
| 变化检测 | STFF-GA（ResNet18） | 256×256 ×2 期 | mean=std=0.5 | 二值变化掩膜（255=变化） |
| 语义分割 | ST-UNet（R50-ViT-B/16，n_skip=3） | 256×256 | 0–1（不标准化） | 0–5 类别掩膜，预览用 Vaihingen 配色 |

输入尺寸与归一化参数对齐各官方仓库的测试脚本。结果都会缩放回原始影像尺寸后写出。

## 移植说明（相对官方仓库的改动）

- 只调整了导入路径（绝对导入 → 包内相对导入），使官方代码能作为子包复用，网络结构与计算逻辑未改动。
- `C2FNet.__init__` 增加 `pretrained=False` 参数；`STFFGA.__init__` 增加 `backbone_pretrained` 参数，
  用于控制是否联网下载 ImageNet 骨干权重（默认关闭）。
- ST-UNet 官方压缩包缺失 `SoftPool.py`，按 SoftPool 论文等价定义补在
  `semantic_segmentation/model/softpool.py`（窗口内 softmax 加权池化）。
- ST-UNet 的 `vit_seg_modeling_resnet_skip.py` 增加了对 `timm.models.layers` 新版本路径的兼容导入。

## 运行环境提示

本机 `.venv` 曾因项目目录改名（`~/项目/遥感平台` → `~/xiangmu/遥感平台`）导致
`.venv/bin/pip`、`uvicorn` 等脚本的 shebang 失效，已修为新路径；后续若再次移动目录，
需要重新执行一次同样的替换，或直接重建虚拟环境。
