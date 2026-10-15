# C2FNet 完整权重

唯一启用的模型是 C2FNet（Res2Net50）。权重文件不进入 Git。

在 python-inference 目录执行：

```powershell
python scripts/download_weights.py --source "你的路径/C2FNet-39.pth"
python scripts/download_weights.py --check
```

脚本依据 manifest.json 校验文件大小和完整 SHA-256。默认目标为 weights/c2fnet.pth，也支持 RS_WEIGHTS_DIR。当前清单没有公开下载地址；搭档需单独取得这份权重后导入。

- 大小：114094760 字节。
- SHA-256：e2d7bc1aea4bd64bf3a066ac9c69dd49937459ab16fbb29ee22885c8249d6df4。
- 无需额外的 Res2Net 骨干权重，也不会联网下载骨干。
- 缺失或不兼容时，接口返回失败，不生成模拟成果。
- 替换权重后按路径、文件大小和修改时间自动重新加载；也可以重启服务，或调用 POST /models/reload 清除缓存。
- 更新到另一份完整权重时，应同步更新清单和验证记录。
