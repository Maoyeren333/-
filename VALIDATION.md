# C2FNet 接入验证记录

验证日期：2026-10-15。以下为本机检查结果，GitHub Actions 需在推送后单独查看。

## 修改范围

- 只保留 C2FNet 模型、前端选择项、后端允许列表和权重清单。
- 移除其他模型目录、模拟成果实现及对应的未完成训练工作流。
- 对齐本地预测流程，严格加载完整 checkpoint，模型不可用时返回失败。
- 每次推理输出独立 TIFF / PNG，防止历史结果被覆盖。
- 修正前端创建任务失败时仍显示成功、结果页引用未定义变量的问题。
- 更新 Windows 运行说明、权重导入与校验、协作和 CI。
- 未修改数据库结构，未删除历史数据库记录，未修改原本地模型工程。

## 已通过

| 检查 | 结果 |
| --- | --- |
| 前端 npm run build | 成功；仍有打包体积提示 |
| 后端 mvn test | 5 项通过，包括停用算法、缺图片、真实成果、模拟/未加载权重和推理异常 |
| 推理 unittest（启用真实 checkpoint） | 12 项通过 |
| 完整权重校验 | 大小 114094760 字节；SHA-256 e2d7bc1aea4bd64bf3a066ac9c69dd49937459ab16fbb29ee22885c8249d6df4 |
| 本地模型与平台接口对比 | CAMO 图片按排序首张、中间张、末张抽取 3 张；全部逐像素一致 |

推理测试覆盖：单模型注册、缺权重失败、无 PyTorch 失败、坏图片、拒绝第二输入字段、缓存重载及权重变动检测、重复输出独立保存、GeoTIFF 地理参考及 uint8 值保持、拒绝未转换的 uint16 TIFF、真实 HTTP 路由预测、参数不兼容 checkpoint 拒绝加载。

本机对比报告位于 python-inference/results/integration-check-20261015/comparison.json（运行产物，不提交 Git）。

## 验证环境与边界

本次使用 Windows、Python 3.14.7、PyTorch 2.13.0+cpu、torchvision 0.28.0、NumPy 2.5.2、Pillow 12.3.0；Web/TIFF 依赖安装在仓库的独立 .venv 中，该环境通过 system-site-packages 复用已有 PyTorch，没有修改全局模型环境。

FastAPI 0.143.1、Pydantic 2.14.1、HTTPX 0.28.1、rasterio 1.5.2。模型测试使用 CPU。后端以 JDK 21 编译为 Java 17 目标版本。

没有启动或更改用户 MySQL 数据库，尚未进行浏览器登录、上传、数据库落库、下载的三端全流程验收；后端验证使用模拟依赖。未验证 CUDA、真实无人机影像或大幅面遥感性能。README 推荐的全新 Python 3.13 环境本次未重新创建。

依赖库测试客户端有弃用提示；前端有大文件体积提示，均未导致本次检查失败。
