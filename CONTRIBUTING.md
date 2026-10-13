# 协作说明（两人同时改代码）

目标很简单：**两个人都能上传代码，也都能拿到对方的更新。** 不需要复杂的流程，只需要三件事。

## 一、一次性设置

### 仓库所有者（已有仓库的人）

Settings → **Collaborators** → **Add people** → 填搭档的 GitHub 用户名 → 权限选 **Write** → 发邀请。

### 搭档（要加入的人）

1. 在 GitHub 通知里**接受邀请**（不接受就没有写权限）。

2. 生成自己的 SSH 密钥，加到**自己的** GitHub 账号：

   ```bash
   ssh-keygen -t ed25519 -C "你的邮箱"
   cat ~/.ssh/id_ed25519.pub     # 粘贴到 GitHub → Settings → SSH and GPG keys → New SSH key
   ssh -T git@github.com         # 出现 Hi <你的用户名>! 就通了
   ```

3. 配置提交身份（**必须做**，否则提交记录会显示成仓库所有者的名字）：

   ```bash
   git config --global user.name "你的名字"
   git config --global user.email "你的邮箱"
   ```

4. 克隆并装环境：见 README「搭档上手与代码同步」。

## 二、每天只用到两条命令

```bash
# 开工第一件事：拿对方的更新
git pull --rebase

# 改完立刻上传自己的更新
git add -A
git commit -m "feat: 做了什么"
git push
```

就这样。两人改**不同文件**时，基本不会冲突。

## 三、三条铁律

1. **每次开工先 `git pull --rebase`，改完尽快 `push`。** 攒一天再推是冲突的主要来源。
2. **永远不要对 `main` 使用 `git push --force`。** 会把对方的提交直接冲掉；只在自己的一次性分支上才允许用 `--force-with-lease`。
3. **动了数据库脚本就在提交信息里注明。** 例如「需执行 schema_geolocation.sql」。`ddl-auto: validate` 会拦住结构不一致的启动，对方 `pull` 完只重启后端会直接失败。

## 四、分工建议（最有效地减少冲突）

按文件或模块分活，而不是两人一起改同一个文件：

| 分工示例 | 你 | 搭档 |
| --- | --- | --- |
| 前端 | `Tasks.vue`、`CommandCenter.vue` | `Dashboard.vue`、`Monitoring.vue` |
| 后端 | `TaskController` / `TaskService` | `DashboardController` / `MonitoringService` |
| 推理 | `change_detection` | `semantic_segmentation` |

改到对方负责的文件时，先在 Issues 里说一句，或者在群里打声招呼。

## 五、万一冲突了

说明两人改了同一个文件的同一段。最简单的一路：

```bash
git pull --rebase
# 提示冲突的文件 → 打开文件，找到 <<<<<<< ======= >>>>>>> 标记，保留正确内容、删掉标记
git add <冲突的文件>
git rebase --continue
git push
```

用 VS Code 的源代码管理面板可以图形化解决冲突（左边/右边/两边都要各点一下），比命令行直观。

## 六、各自的运行环境互不干扰

- 数据库：各自建库，启动时带 `DB_NAME`：
  `DB_NAME=uav_remote_sensing_<你的名字> mvn spring-boot:run`
- 同一台机器上同时跑服务时错开端口：后端 `SERVER_PORT=8081`，推理 `.venv/bin/uvicorn main:app --port 8001`，前端 Vite 会自动换到 5174。
- 模型权重不进仓库，谁训练/下载完传到 Release，另一个跑 `cd python-inference && ./scripts/download_weights.sh` 拉齐。
