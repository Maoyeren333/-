# 无人机遥感智能解译平台

`vue-frontend`：Vue3/Element Plus/ECharts；`springboot-backend`：Spring Boot + MySQL + OpenFeign 调度；`python-inference`：FastAPI 推理服务。MySQL 只保存任务、影像、结果元数据和路径，影像及 tif 保存在磁盘。

执行 `springboot-backend/src/main/resources/db/schema.sql`、`schema_extra.sql` 和 `schema_auth.sql` 初始化数据库；启动 Python 服务 `uvicorn main:app --host 0.0.0.0 --port 8000`；启动 Spring Boot 默认 8080；前端 Vite 已配置 `/api` 代理。账户模块提供注册、登录、退出、个人资料和密码修改，使用 BCrypt 加密密码及服务端 Session。

## 解译算法：已接入真实深度模型

三个 `detector.py` 已从仿真实现替换为官方网络结构的真实推理，论文与官方源码来自 `Downloads/遥感平台`：

| 算法 | 模型 | 输入 | 输出 |
| --- | --- | --- | --- |
| `camouflaged_object_detection` | C2FNet（Res2Net50，IJCAI 2021） | 单幅影像 352×352 | 二值前景掩膜 |
| `change_detection` | STFF-GA（ResNet18，TGRS 2024） | 双时相影像 256×256 | 二值变化掩膜 |
| `semantic_segmentation` | ST-UNet（R50-ViT-B/16，TGRS 2022） | 单幅影像 256×256 | 0–5 类别掩膜 |

- 权重文件放在 `python-inference/weights/`，详见该目录 `README.md`；未放置权重时使用随机初始化权重跑通链路，返回体 `weights_loaded=false` 且 `notes` 说明原因。
- torch 不可用或模型构建失败时自动降级为仿真实现，返回体 `simulated=true`，平台流程不会中断。
- 结果统一写出 `*_mask.tif`（GeoTIFF 输入会保留坐标系与仿射变换）与 `*_mask.png` 预览图。

## 接口

推理服务：

- `POST /inference/{algorithm}`：`{ "image_path": "...", "image_path_b": "..." }` → `mask_path`、`preview_path`、`weights_loaded`、`simulated` 等；`image_path_b` 仅变化检测需要。
- `GET /health`、`GET /algorithms`：运行时状态与各算法权重是否就绪。

后端（Spring Boot）：

- 鉴权：除 `/api/auth/login`、`/api/auth/register` 外，`/api/**` 全部要求登录会话，未登录返回 `401 {"code":401,"message":"登录状态已失效，请重新登录"}`。
- `POST /api/tasks/{id}/interpretations`：启动解译。未传 `image_path` 时自动使用该任务已上传的影像；变化检测按上传顺序取前两张作为双时相输入，缺第二张时返回明确错误。
- `GET /api/tasks?keyword=&status=`：任务列表（支持关键字与状态筛选）；`GET /api/tasks/page?page=&size=&keyword=&status=` 分页版。
- `GET /api/tasks/{id}/detail`：任务详情（任务 + 影像 + 该任务的解译记录）。
- `DELETE /api/tasks/{id}`：删除任务，级联删除其影像、解译记录与磁盘文件。
- `GET /api/tasks/{taskId}/images`、`DELETE /api/tasks/{taskId}/images/{imageId}`：影像列表与删除（含磁盘文件）。
- `GET /api/interpretations?taskId=&status=&algorithm=`：解译记录列表与筛选；`GET /api/interpretations/page` 分页版；`DELETE /api/interpretations/{id}` 删除记录并清理结果文件。
- `GET /api/notifications`：通知中心数据，由推理服务可达性、权重就绪情况、失败/成功解译、待解译任务、缺影像任务实时合成。
- `GET /api/interpretations/{id}/preview`、`/mask`：结果预览 PNG 与 GeoTIFF 成果下载。
- `GET /api/exports`：可导出成果清单（含文件是否存在、大小）；`GET /api/exports/{id}/package` 单条打包 ZIP；`GET /api/exports/package` 全部打包 ZIP。ZIP 内含 `result.tif`、`preview.png`、`metadata.json`。
- `GET /api/dashboard/summary|trend|algorithms`：任务统计、最近 7 日真实趋势、各算法真实调用与成功数。
- `GET /api/command/overview`：指挥中心数据——真实服务状态（含 Python 推理服务健康检查、MySQL 查询计时、Open-Meteo 可达性，带超时）、真实统计与最近活动。
- `GET /api/algorithms`、`/api/algorithms/health`：把推理服务真实状态透传给前端。

前端：驾驶舱图表与指标、指挥中心状态/活动/工作建议、通知中心与角标、全局搜索（含真实任务）、任务统计卡与分页与坐标列、影像列表、结果筛选与分页与删除、任务详情抽屉、成果清单与 ZIP 下载，全部取自上述真实接口。

## 放到 GitHub 与权重接入

### 仓库里放什么、不放什么

- 进仓库：三端源码、三个算法的网络定义、接入约定与 CI 配置。
- 不进仓库：模型权重、上传的影像与解译成果、官方数据集与论文（体积大且各有独立授权）——这些已在 `.gitignore` 里排除。

### 首次推送

```bash
cd /Users/maoyeren/xiangmu/遥感平台
git init -b main
git add .
git commit -m "feat: 无人机遥感智能解译平台"
git remote add origin https://github.com/<用户名>/<仓库>.git

# 本机直连 GitHub 超时，实测需走本机代理（端口以你本地客户端实际监听为准）
git config http.proxy http://127.0.0.1:7892
git push -u origin main
```

代理只配在当前仓库（`git config` 不带 `--global`），不影响其他项目；不想额外配置时，
也可以改用 Gitee / 阿里云 Codeup（这台机器实测直连可达）。

### 训练好的权重怎么接上去、怎么实时更新

完整流程见 [TRAINING.md](TRAINING.md)，要点：

- 权重按约定文件名放进 `python-inference/weights/`（`c2fnet.pth` / `stff_ga.pth` / `st_unet.pth`）；
- 权重**不提交仓库**（GitHub 单文件 100 MB 限制、LFS 免费额度只有 1 GB），改走
  GitHub Release 附件 + `weights/manifest.json` + `scripts/download_weights.sh` 下载并校验 sha256；
- 替换权重文件后服务会通过文件签名变化**自动热加载**，无需重启；返回体带 `weights_version`
  （sha256 前 8 位）可追溯结果出自哪一版权重，也可 `POST /models/reload` 立即生效；
- 想自动化训练，用 `.github/workflows/train-weights.yml` 配一台带 GPU 的 self-hosted runner
  （GitHub 托管 runner 没有 GPU）。

## 配置

`springboot-backend/src/main/resources/application.yml`：

- `inference.url`：推理服务地址，默认 `http://localhost:8000`。
- `storage.images-dir`：原始影像落盘目录，默认 `./data/images`（按进程工作目录解析为绝对路径）。

数据库迁移：`uav_task` 已增加 `latitude` / `longitude` 两列用于监测态势图真实定位，迁移脚本为
`springboot-backend/src/main/resources/db/schema_geolocation.sql`（已应用到本机库，`schema.sql` 也已同步，全新初始化无需单独执行）。

推理服务支持的环境变量见 `python-inference/README.md`（`RS_WEIGHTS_DIR`、`RS_DEVICE`、`RS_RESULT_DIR`、`RS_ALLOW_DOWNLOAD`）。

## 已修复的问题

- 影像上传：`MultipartFile.transferTo` 原先使用相对路径，写文件时被解析到 Tomcat 临时工作目录而必然失败；现改为绝对路径并可通过 `storage.images-dir` 配置。
- 启动解译：前端原先硬编码 `image_path=/data/input.tif`，必然找不到文件；现在由后端按任务已上传影像自动解析，失败原因会回显到前端提示。
- 业务接口无鉴权：原先只有前端路由守卫，未登录也能直接调用业务接口；已补服务端会话校验。
- 结果导出解得失败：导出打包用的 `ObjectMapper` 未注册 Java 时间模块，序列化 `LocalDateTime` 直接 500；已改为注入 Spring 管理的 `ObjectMapper`。
- 组件语言包：未设置 Element Plus locale，确认框显示的是 Cancel / OK；已显式使用 `zh-cn`。

## 功能实现状态

下面是逐项核对后的真实状态，避免再把写死的演示数据当成已实现功能。

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 注册 / 登录 / 退出 / 个人资料 / 改密码 | 已实现 | BCrypt 加密 + 服务端 Session，接口层已强制鉴权 |
| 航拍任务 | 已实现 | 创建、列表、详情、删除、启动异步解译 |
| 影像数据 | 已实现 | 上传、列表、删除（含磁盘文件），可按任务查看 |
| 智能解译 | 已实现 | 三个官方模型真实推理；权重与运行时状态取自推理服务 |
| 结果中心 | 已实现 | 记录列表、算法/状态筛选、预览图、下载、删除 |
| 成果导出 | 已实现 | 真实成果清单 + 单条/全部 ZIP 打包下载 |
| 数据驾驶舱 | 已实现 | 指标、任务队列、趋势图、算法分布、引擎健康度均为真实数据 |
| 智能指挥中心 | 已实现 | 服务状态、统计与最近活动均来自真实探测与数据库 |
| 实时监测 | 已实现 | 天气、空气质量、风险评分来自 Open-Meteo 实时接口；态势点位：环境点为作业区中心，任务点位优先用任务自身录入的经纬度真实定位，未录入坐标的任务以虚线环标为“相对示意” |
| 通知中心 | 已实现 | 顶栏角标与通知列表由真实状态合成（服务可达性、权重就绪、失败/成功解译、待解译任务、缺影像任务） |
| 任务地理坐标 | 已实现 | `uav_task.latitude/longitude` + 新建任务表单坐标录入 + 态势图真实定位 |
| 分页与搜索 | 已实现 | 任务/解译记录服务端分页（10/20/50 可选）、关键字与状态筛选；顶栏全局搜索纳入真实任务 |
| 无人机遥测 | 未实现 | 没有真实遥测数据源，所以删掉了原先伪造的 UAV 点位，而不是继续展示假数据；需要接入设备上报接口才能真正实现 |
