# 模型权重放置目录

三个算法都已改为真实模型推理，但**预训练权重文件不在本仓库内**（官方权重体积大且授权独立）。
不放置权重时服务仍可正常运行：会使用随机初始化权重完成真实的前向推理，返回体里
`weights_loaded=false`、`notes` 说明原因，结果不具备判读意义。

## 放置方式

把权重文件放进本目录，按下面的文件名命名即可被自动识别：

| 算法 | 推荐文件名 | 模型 |
| --- | --- | --- |
| `camouflaged_object_detection` | `c2fnet.pth` | C2FNet（Res2Net50 骨干） |
| `change_detection` | `stff_ga.pth` | STFF-GA（ResNet18 骨干） |
| `semantic_segmentation` | `st_unet.pth` | ST-UNet（R50-ViT-B/16） |

兼容的候选文件名（任选其一）：

- 伪装目标检测：`c2fnet.pth`、`c2fnet-39.pth`、`C2FNet-39.pth`、`c2fnet39.pth`
- 变化检测：`stff_ga.pth`、`stff-ga.pth`、`stffga.pth`、`best.pth`
- 语义分割：`st_unet.pth`、`st-unet.pth`、`stunet.pth`、`epoch_99.pth`

权重可以是裸 `state_dict`，也可以是 `{'model': ...}` / `{'state_dict': ...}` 包装，
`module.` 前缀会被自动去除；键不完全匹配不会中断推理，但会在 `notes` 里给出缺失/多余参数个数。

## 权重来源

- **伪装目标检测（C2FNet）**：官方仓库 README 的 Google Drive 链接
  `checkpoints/C2FNet40/C2FNet-39.pth`。本机网络访问 Google Drive 不通，需要手动下载后放入本目录。
- **变化检测（STFF-GA）**：官方仓库（NjustHGWei/STFF-GA）未发布训练权重，需自行训练，
  或在你有训练成果时放入 `stff_ga.pth`。
- **语义分割（ST-UNet）**：官方 `test.py` 引用的本机路径权重（`epoch_99.pth`）未公开，
  其保存格式为 `{'model': state_dict}`；若有该文件可直接放入本目录。

## 相关环境变量

- `RS_WEIGHTS_DIR`：自定义权重目录（默认就是本目录）。
- `RS_DEVICE`：`auto`（默认，优先 CUDA 否则 CPU）、`cpu`、`cuda`、`mps`。
- `RS_RESULT_DIR`：把结果 mask 统一写到指定目录（默认写回输入影像所在目录）。
- `RS_ALLOW_DOWNLOAD=1`：允许构建模型时联网下载官方预训练骨干
  （C2FNet 的 Res2Net50、STFF-GA 的 ResNet18）；默认关闭，避免离线环境请求被网络超时拖住。

## 一键下载与校验（manifest.json）

本目录的 `manifest.json` 记录每个权重的文件名、下载地址与 sha256。填好后：

```bash
cd python-inference
./scripts/download_weights.sh                          # 下载缺失项并校验
./scripts/download_weights.sh --proxy http://127.0.0.1:7892   # GitHub 直连不通时走代理
./scripts/download_weights.sh --check                  # 只体检不下载
```

权重可以放在 GitHub Release 附件、ModelScope 或对象存储上，只要把直链填进 `url` 即可。

## 热更新与版本追溯

- 权重文件被**新增 / 替换 / 删除**后，服务会在下一次推理时通过文件签名（文件名 + 大小 + mtime）
  发现变化并重建模型，**不需要重启**。
- 返回体中的 `weights_version` 是权重内容 sha256 的前 8 位；`GET /algorithms` 也会列出
  每个算法当前使用的权重文件与版本号。
- 需要立即生效时：`curl -X POST http://localhost:8000/models/reload`。

训练与发布的完整流程见项目根目录 [TRAINING.md](../../TRAINING.md)。
