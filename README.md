# C2FNet 伪装目标检测平台

这是两人协作的影像检测平台：Vue 前端负责上传与预览，Spring Boot 管理账户、任务和结果，FastAPI 调用已验证的 C2FNet 模型。当前只提供伪装目标检测。

## 模型与输出

- 模型：C2FNet，Res2Net50 骨干，输入缩放为 352×352；无需标注图即可预测。
- 使用完整 C2FNet-39.pth 权重，严格检查参数，缺失或不匹配时解译失败。
- 与本地实验一致：RGB / ImageNet 归一化 → 模型 → 将 logits 恢复到原图尺寸 → sigmoid → 逐图最小最大归一化 → uint8 灰度图。
- 输出为原尺寸 TIFF 和 PNG 灰度响应图；不是二值掩膜，也不是经过校准的概率。
- 每次解译生成独立文件，重复运行不会覆盖已有成果。
- JPG/PNG 支持常规 RGB 图片；TIFF 支持 uint8 单通道、RGB、RGBA，保留已有地理参考。其他位深或多光谱数据需先明确转换方式。
- CAMO 上的验证不能直接说明真实航拍场景效果；无人机实时遥测和模型训练不在本仓库中。

## 环境

建议 Python 3.13、Node.js 22、JDK 17 或 21、Maven 3.9、MySQL 8。以下为 Windows PowerShell 操作，每段均注明所在目录。

```powershell
git clone https://github.com/Maoyeren333/-.git remote-sensing-platform
cd remote-sensing-platform
```

### 1. 推理服务

在仓库根目录：

```powershell
cd python-inference
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe scripts/download_weights.py --source "C:\study\semesterwork\遥感平台\camouflaged_object_detection\code\C2FNet-master\checkpoints\C2FNet40\C2FNet-39.pth"
.\.venv\Scripts\python.exe scripts/download_weights.py --check
.\.venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8000
```

导入路径换成自己保存完整权重的位置。权重不随 git clone/pull 下载，当前也没有公开下载链接。无需重训或复制整个模型数据集。

Linux/macOS 使用 python3 创建环境，随后将 .venv\\Scripts\\python.exe 换成 .venv/bin/python。若使用已有 Python 环境，请先确认 requirements.txt 中的依赖可用。

### 2. 数据库和后端

在 MySQL 客户端中依次执行：

1. springboot-backend/src/main/resources/db/schema.sql
2. springboot-backend/src/main/resources/db/schema_extra.sql
3. springboot-backend/src/main/resources/db/schema_auth.sql

上述初始化脚本面向新数据库。已有数据库不要重复执行建表；缺少经纬度列时才执行 schema_geolocation.sql。此次模型接入没有数据库结构变更，也不会删除历史任务或结果。

另开终端，在仓库根目录：

```powershell
cd springboot-backend
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "你的数据库密码"
mvn spring-boot:run
```

默认数据库名 uav_remote_sensing，服务端口 8080。可用 DB_HOST、DB_PORT、DB_NAME、INFERENCE_URL 覆盖配置。首次启动若报告表结构不一致，先核对数据库脚本。

### 3. 前端

另开终端，在仓库根目录：

```powershell
cd vue-frontend
npm ci
npm run dev
```

打开终端显示的本地地址，注册并登录 → 新建 C2FNet 任务 → 上传图片 → 启动解译 → 结果中心预览 → 下载成果。

一个任务可上传多张影像。点击“开始解译”后勾选要处理的图片（默认全选），也可在影像列表点击“解译此图”。每张图片单独生成一条结果；结果中心可通过文件名选择框、上一张/下一张、表格中的“预览此图”切换。批次中某张失败时继续处理其余图片，任务最终标记为失败，成功图片的成果仍可查看。历史的其他算法任务可以查看，但不能再启动；请新建 C2FNet 任务。

## 服务约定

默认三个服务运行在同一台电脑，后端与 Python 必须能访问同一份绝对文件路径。拆分到多机时需要共享存储及一致的路径映射，不能直接沿用单机配置。

- POST /api/tasks/{id}/interpretations：可传 {"image_ids":[1,2]} 选择该任务的影像；未传选择时处理任务所有影像。每张图片单独生成记录，不支持选择其他任务的图片。
- GET /api/interpretations/page?taskId=任务ID：按任务筛选并分页查看结果。
- POST /inference/camouflaged_object_detection：请求体为 {"image_path":"绝对图片路径"}。
- GET /algorithms：只有 C2FNet；就绪状态来自实际模型加载结果。
- GET /health：UP 表示模型可加载，DEGRADED 表示服务存活但模型不可用。
- POST /models/reload：清除模型缓存，下次检查或推理时重新加载。
- 推理返回 mask_path、preview_path、weights_sha256、weights_loaded、simulated、output_type 等字段。
- 缺图片/不支持的 TIFF 返回 400；模型或设备不可用返回 503；写文件失败返回 500。
- 后端仅把 weights_loaded=true 且 simulated=false 的返回作为成功成果。
- 结果中心与 ZIP 导出保持原有接口。普通图片输出 TIFF；只有具有地理参考的输入才输出带地理参考的 GeoTIFF。

推理服务读取本机路径，默认只监听 127.0.0.1，通过已登录的后端调用。不要直接暴露为公网文件访问接口。

## 检查与协作

```powershell
# python-inference 目录，测试依赖：
.\.venv\Scripts\python.exe -m pip install -r requirements-test.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v

# vue-frontend 目录：
npm run build

# springboot-backend 目录：
mvn test
```

真实权重测试需设置 C2FNET_TEST_WEIGHTS 指向完整 checkpoint，详见 [推理说明](python-inference/README.md)。[协作说明](CONTRIBUTING.md) 介绍分支和同步；[权重说明](python-inference/weights/README.md) 介绍导入与校验。本地修改只有提交并推送后才会出现在 GitHub。

See [VALIDATION.md](VALIDATION.md) for completed checks and remaining verification limits.
