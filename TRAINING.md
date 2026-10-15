# C2FNet 权重接入

本仓库负责已训练 C2FNet 的推理与平台集成，不提供可直接运行的训练流水线。无需再次训练，直接使用已验证的完整 C2FNet-39.pth。

1. 在 python-inference 目录运行 python scripts/download_weights.py --source "完整 checkpoint 路径"。
2. 运行 python scripts/download_weights.py --check。
3. 启动推理服务，查看 GET /algorithms 的 weights_ready、runtime_ready 和 weights_sha256。
4. 使用与本地实验相同的图片对比灰度结果，再在平台创建任务验证。

如果将来更换权重，需要与当前网络结构严格兼容，并重新验证预测结果和数据集指标；同步更新 weights/manifest.json 中的校验值。原先针对其他模型的未完成训练工作流已移除。

权重不会提交到 Git。当前没有配置公开下载 URL，另一台电脑需单独导入权重。详见 [权重说明](python-inference/weights/README.md)。
