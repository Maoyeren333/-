"""SoftPool（softmax 加权池化）的本地实现。

ST-UNet 官方仓库（ST-UNet-main）的 networks/vit_seg_modeling_resnet_skip.py 依赖
`SoftPool` 包（github: alexandrosstergiou/SoftPool），但该文件没有随代码一起发布，
这里按 SoftPool 论文的等价定义补齐：对每个池化窗口内的元素做 softmax 归一化，
再以归一化权重对窗口元素加权求和。窗口内权重和恒为 1，因此输出与窗口数值同量纲。

官方 CUDA 实现与本实现数值等价（官方 CPU 分支同样是 im2col + softmax 加权）。
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.modules.utils import _pair


def soft_pool2d(x: torch.Tensor, kernel_size=2, stride=None, force_inplace: bool = False) -> torch.Tensor:
    """SoftPool2d 的函数式实现。

    Args:
        x: (N, C, H, W)
        kernel_size: 池化窗口，整数或 (kh, kw)
        stride: 步长，默认等于 kernel_size
        force_inplace: 保留签名以兼容官方 API，本实现始终返回新张量
    """
    kernel_size = _pair(kernel_size)
    stride = kernel_size if stride is None else _pair(stride)
    kh, kw = kernel_size
    sh, sw = stride

    if kh <= 0 or kw <= 0:
        raise ValueError("kernel_size 必须为正整数")

    # 用 unfold 取出所有窗口，得到 (N, C, out_h, out_w, kh*kw)
    windows = x.unfold(2, kh, sh).unfold(3, kw, sw)
    n, c, oh, ow = windows.shape[:4]
    flat = windows.reshape(n, c, oh, ow, kh * kw)

    weights = F.softmax(flat, dim=-1)
    out = (weights * flat).sum(dim=-1)
    return out.contiguous()


class SoftPool2d(nn.Module):
    """SoftPool2d 模块化实现，行为对齐官方 SoftPool 包。"""

    def __init__(self, kernel_size=2, stride=None, padding=0):
        super().__init__()
        self.kernel_size = _pair(kernel_size)
        self.stride = self.kernel_size if stride is None else _pair(stride)
        self.padding = _pair(padding)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        if any(p > 0 for p in self.padding):
            x = F.pad(x, (self.padding[1], self.padding[1], self.padding[0], self.padding[0]))
        return soft_pool2d(x, self.kernel_size, self.stride)

    def extra_repr(self) -> str:
        return f"kernel_size={self.kernel_size}, stride={self.stride}, padding={self.padding}"


__all__ = ["soft_pool2d", "SoftPool2d"]
