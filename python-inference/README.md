# C2FNet 推理服务

安装与启动见 [根目录 README](../README.md)。模型结构位于 camouflaged_object_detection/model，接入代码位于 detector.py；预处理与后处理按已验证的本地 C2FNet 实验对齐。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| RS_WEIGHTS_DIR | 服务目录下 weights | 完整权重目录 |
| RS_RESULT_DIR | 服务目录下 results | TIFF、PNG 输出目录 |
| RS_DEVICE | auto | auto / cpu / cuda，指定不可用的 CUDA 会失败 |
| RS_THREADS | 4 | CPU 运算线程数，须为正整数 |

默认路径按代码位置解析，不依赖启动终端的位置。完整 checkpoint 严格加载；无需 Res2Net 预训练文件。默认单进程缓存模型，请勿随意增加多个 worker 导致重复占用显存。

每次预测返回灰度响应 TIFF 和 PNG，尺寸与原图相同。class_count=2 指背景/前景任务，并不表示输出只有两个灰度值。输出处理为 logits 插值后 sigmoid、逐图归一化，与本地实验一致。

GET /algorithms 和 GET /health 首次调用会实际加载模型，首次访问可能较慢。模型加载失败会保留错误供诊断；修复依赖或设备配置后可重启服务或 POST /models/reload。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements-test.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
$env:C2FNET_TEST_WEIGHTS = (Resolve-Path weights/c2fnet.pth).Path
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

不设置 C2FNET_TEST_WEIGHTS 时只运行轻量接口检查；设置后增加真实模型、重复输出和地理参考验证。测试图片在临时目录中创建。

模型网络来自仓库原有的 C2FNet 移植，论文/原项目来源为 https://github.com/Ben57882/C2FNet-master 。本次接入没有改变网络结构。
