# 训练权重并接入平台

平台侧已经做好接入准备：**权重放进 `python-inference/weights/` 就生效，替换文件后无需重启服务**。
这份文档只讲怎么把权重产出来、传上去、以及团队其他人怎么拿到。

## 1. 现状：三个算法都在，只缺权重

| 算法 | 权重文件名（放进 weights/） | 现状 |
| --- | --- | --- |
| 伪装目标检测 C2FNet | `c2fnet.pth` | 官方 checkpoint 在 Google Drive，本机访问不通，需手动下载 |
| 变化检测 STFF-GA | `stff_ga.pth` | 官方未发布权重，需用 WHU-CD-256 训练 |
| 语义分割 ST-UNet | `st_unet.pth` | 官方未发布权重，需用 Vaihingen/Potsdam 训练 |

在没有权重的状态下，服务会用随机初始化权重跑通链路，返回体里 `weights_loaded=false`、
`weights_version=null`，掩膜几乎没有判读意义。**判断是否真的接上了，就看 `weights_loaded` 是否为 `true`。**

## 2. 数据集准备

| 数据集 | 文件 | 用途 |
| --- | --- | --- |
| WHU-CD-256 | `Downloads/遥感平台/change_detection/dataset/WHU-CD-256.zip`（1.8 GB） | 训练/评测 STFF-GA |
| Vaihingen big_images | `Downloads/遥感平台/semantic_segmention/dataset/big_images.zip`（522 MB） | 训练 ST-UNet 需要先切片成 256×256 并转 npz |

WHU-CD-256 解压后已含 `train/ val/ test/` 与 `A/ B/ mask/` 结构，可直接用官方 `utils/data_loader.py`。
Vaihingen 只有大图，需要自己切片；官方 `datasets/dataset_synapse.py` 读的是 npz，切片脚本要按它的字段名生成
（`image` / `label` / `case_name`）。

## 3. 训练

两个官方仓库的 `train.py` 里都有**硬编码的绝对路径**（例如 `/root/data1/CD-Data/LEVIR-CD-256/`），
跑之前必须先改成你自己的路径，这是最容易踩的坑。

变化检测（STFF-GA，官方默认 225 epoch / batch 64 / 256×256）：

```bash
cd <STFF-GA 源码目录>
python train.py --data_name WHU --epoch 225 --batchsize 64 --trainsize 256 --save_path ./checkpoints/
```

语义分割（ST-UNet，官方 Vaihingen 配置 6 类 / 150 epoch / batch 8）：

```bash
cd <ST-UNet 源码目录>
python train.py --root_path <Vai_256_npz 目录> --dataset Vai --num_classes 6 \
  --img_size 256 --max_epochs 150 --batch_size 8 --vit_name R50-ViT-B_16 --n_skip 3
```

训练需要 GPU，CPU 上不现实。GitHub 托管的 runner 没有 GPU，如果要用 Actions 自动化，
需要给仓库注册一台带 GPU 的 self-hosted runner（`.github/workflows/train-weights.yml` 是模板）。

## 4. 产出权重并把格式对齐

平台的加载器（`common/runtime.load_state_dict_flexible`）能自动处理三种情况，所以基本不用改脚本：

- 裸 `state_dict`；
- `{'model': state_dict}` 包装（ST-UNet 官方 `trainer.py` 就是这种）；
- `DataParallel` 的 `module.` 前缀。

只要把文件按上表命名放进 `python-inference/weights/` 即可。建议同时算一下 hash：

```bash
shasum -a 256 stff_ga.pth
```

## 5. 权重怎么发布（GitHub 上不要直接 commit 权重）

GitHub 对仓库的限制会直接挡住"把权重提交进仓库"这条路：

- 单文件超过 **100 MB** 会被拒绝推送；超过 50 MB 会警告；
- Git LFS 免费额度只有 **1 GB 存储 + 1 GB/月流量**，C2FNet/ST-UNet 这类权重很容易撑爆，超出要付费；
- 仓库体积膨胀后，所有人 clone 都会变慢。

推荐做法：**代码进仓库，权重进 Release 附件**（单文件上限 2 GB，不走 LFS 流量）。

```bash
# 1) 上传权重到 Release（用 GitHub CLI，先登录 gh auth login）
gh release create v1.0-weights --title "v1.0 模型权重" --notes "首个可用权重版本"
gh release upload v1.0-weights python-inference/weights/stff_ga.pth \
  python-inference/weights/st_unet.pth python-inference/weights/c2fnet.pth

# 2) 把下载地址与 sha256 填进 python-inference/weights/manifest.json
#    url 用 Release 附件的直链：
#    https://github.com/<用户名>/<仓库>/releases/download/v1.0-weights/stff_ga.pth
```

之后任何人（包括你自己换机器）只要：

```bash
cd python-inference && ./scripts/download_weights.sh --proxy http://127.0.0.1:7892
```

就会自动下载 + 校验 sha256，缺哪个补哪个。国内网络拉 GitHub 慢或不通时，也可以把权重传到
ModelScope 或阿里云 OSS（这台机器实测两者可达），只改 `manifest.json` 里的 `url` 即可。

## 6. "实时更新"是怎么实现的

这套机制让训练成果能立刻接到平台上，不需要改代码、不需要重启：

1. **热加载**：每次推理前对权重文件做一次轻量签名（文件名 + 大小 + mtime）。签名变了就重建模型。
   所以你在训练机上训完，把新文件覆盖进 `weights/`，**下一次解译请求就会用上新权重**。
2. **版本可追溯**：返回体里带 `weights_version`（权重内容 sha256 前 8 位），
   `GET /algorithms` 也会列出每个算法当前使用的权重文件与版本号，能确认这次结果出自哪一版权重。
3. **手动立即生效**：`curl -X POST http://localhost:8000/models/reload` 强制丢弃缓存重建，
   适合训练脚本刚写完权重就想验证的场景。
4. **自动化（可选）**：`.github/workflows/train-weights.yml` 用 self-hosted GPU runner 训练 +
   产出权重。想做成"提交代码→自动训练→自动发布权重"，就在该工作流最后加一步
   `gh release upload`，把新权重挂到带版本号的 Release 上，再更新 `manifest.json`。

## 7. 验收口径

训练完接入后，跑一次解译，确认这三条：

- 解译返回 `weights_loaded: true`，`weights_version` 是 8 位哈希；
- `GET /algorithms` 里该算法的 `weights` 指向你放的文件、`weights_version` 一致；
- 掩膜不再是单一取值（随机权重时会出现"全 255"或"全 0"这种现象），
  变化检测的掩膜应当只覆盖真实变化区域。
