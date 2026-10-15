# 两人协作

本地修改不会自动同步到 GitHub；需要 commit 和 push。搭档需先接受仓库协作者邀请，或通过自己的 fork 提交 Pull Request。

## 推荐流程（PowerShell）

在仓库根目录执行：

```powershell
git status
git switch main
git pull --ff-only
git switch -c feat/你的修改主题

# 编辑、运行相关测试后，只添加本次文件
git add 文件路径
git commit -m "说明改了什么"
git push -u origin feat/你的修改主题
```

然后在 GitHub 为该分支创建 Pull Request，由搭档检查后合并。存在未提交修改时，先保存自己的修改再切分支；不要使用强制推送覆盖 main。

提交身份在当前仓库设置即可：

```powershell
git config user.name "你的名字"
git config user.email "你的邮箱"
```

## 分工和验证

- 模型负责人：C2FNet 接口、权重校验、预测一致性。
- 平台负责人：前端、Java 后端、数据库。
- 跨模块接口修改写清请求字段、输出含义和失败状态。
- 推理：unittest；前端：npm run build；后端：mvn test。
- 数据库有结构变化时才提供迁移脚本；此次模型接入不要求重建数据库。

权重、数据集、上传图片、结果、虚拟环境及本机密码均不提交。权重由双方单独传递后，用 scripts/download_weights.py --source 导入并校验。仓库中不再提供其他模型的训练模板。
