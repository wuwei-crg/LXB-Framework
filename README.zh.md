<div align="center">

# LXB-Framework

### 基于视觉语言模型的 Android 自动化框架

**Route-Then-Act**：构建导航地图，路由到目标页面，然后使用 VLM 指导执行任务。

[![Python](https://img.shields.io/badge/Python-3.9+-blue.svg)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/wuwei-crg/LXB-Framework.svg?style=social)](https://github.com/wuwei-crg/LXB-Framework)
[![文档](https://img.shields.io/badge/docs-latest-brightgreen.svg)](docs/zh)

---

[English](README.md) | [中文文档](README.zh.md)

</div>

---

> **在正式开始之前，先叠个甲**
>
> 这是一个本科生的探索性项目，Work in Progress，别抱太高期望。
> 代码目前比较粗糙，很多 Corner Case 没有覆盖，后续也不一定会持续维护更新。
> 只是在折腾的过程中发现"VLM + XML 无障碍树"这个组合挺有意思的，
> 就把思路和实现都记录下来，希望能给同样在这个方向探索的人提供一点参考。
>
> 如果你是相关领域的研究者或工程师，发现了问题或者有更好的想法，
> 欢迎开 Issue 或提 PR，非常感谢。求轻喷——孩子还小，怕生。

---

## 概述

LXB-Framework 是一个面向 Android 自动化的工程体系，核心目标是：

1. **自动构建可复用的应用导航地图**（LXB-MapBuilder）
2. **先路由到目标页面，再使用 VLM 指导执行任务**（LXB-Cortex）

### 核心特性

- **地图驱动自动化**：一次构建应用导航地图，多次复用执行任务
- **Route-Then-Act 模式**：确定性导航 + AI 引导执行
- **VLM-XML 融合**：结合视觉语言模型语义理解与 XML 层次结构实现可靠定位
- **检索优先定位**：使用 resource_id/text 而非硬编码坐标
- **Web 控制台**：统一的调试、建图和任务执行界面

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                     用户界面层                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Web 控制台   │  │ Python API   │  │  示例代码    │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
└─────────┼──────────────────┼──────────────────┼─────────────┘
          │                  │                  │
┌─────────┴──────────────────┴──────────────────┴─────────────┐
│                     核心模块层                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ LXB-Cortex   │  │LXB-MapBuilder│  │  LXB-Link    │      │
│  │   (FSM)      │  │  (VLM+XML)   │  │  (协议)      │      │
│  └──────────────┘  └──────────────┘  └──────┬───────┘      │
└─────────────────────────────────────────────┼───────────────┘
                                               │
┌─────────────────────────────────────────────┴───────────────┐
│                    Android 设备端                             │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              LXB-Server (Shizuku 服务)               │   │
│  │  无障碍服务 + 输入注入                                │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 模块介绍

| 模块 | 描述 | 代码路径 |
|------|------|----------|
| **LXB-Link** | 设备通信客户端，可靠 UDP 协议 | `src/lxb_link/` |
| **LXB-Server** | Android 端服务，输入注入和 UI 感知 | `android/LXB-Ignition/` |
| **LXB-MapBuilder** | 使用 VLM+XML 自动构建应用导航地图 | `src/auto_map_builder/` |
| **LXB-Cortex** | Route-Then-Act 自动化引擎，FSM 运行时 | `src/cortex/` |
| **LXB-WebConsole** | Web 调试和任务执行界面 | `web_console/` |

## 快速开始

### 前置要求

- Python 3.9+
- 已安装 Shizuku 的 Android 设备
- VLM API 访问权限（推荐 Qwen-VL-Plus 或 GPT-4o）

### 安装

```bash
# 克隆仓库
git clone https://github.com/wuwei-crg/LXB-Framework.git
cd LXB-Framework

# 安装依赖
pip install -r requirements.txt
```

### 基本使用

```python
from lxb_link import LXBLinkClient

# 连接设备
client = LXBLinkClient("192.168.1.100", 12345)
client.connect()
client.handshake()

# 截图
screenshot = client.screenshot()

# 查找并点击元素
nodes = client.find_node("设置", match_type="text")
if nodes:
    x, y = nodes[0]["bounds"]
    client.tap(x, y)
```

### 启动 Web 控制台

```bash
cd web_console
python app.py
```

然后访问 `http://localhost:5000/`

## 文档

### 模块文档

- [LXB-Link](docs/zh/lxb_link.md) - 设备通信协议
- [LXB-Server](docs/zh/lxb_server.md) - Android 服务架构
- [LXB-MapBuilder](docs/zh/lxb_map_builder.md) - 地图构建引擎
- [LXB-Cortex](docs/zh/lxb_cortex.md) - Route-Then-Act 执行
- [LXB-WebConsole](docs/zh/lxb_web_console.md) - Web 控制台界面

### 指南

- [快速开始指南](docs/zh/quickstart.md)
- [配置参考](docs/zh/configuration.md)
- [示例代码](examples/)

## 设计理念

### Route-Then-Act

不是每个动作都使用 VLM，LXB-Framework 采用：

1. **构建地图**：应用导航结构的地图
2. **确定性路由**：使用地图到达目标页面
3. **任务执行**：在目标页上使用 VLM 指导执行

这种方式减少了 VLM API 调用，提高了可靠性，并实现了任务可重现性。

### 检索优先定位

使用语义属性（resource_id、text）而非硬编码坐标来定位元素，确保在不同设备和屏幕尺寸上的可靠性。

### VLM-XML 融合

- **VLM** 提供语义理解（这是什么按钮？）
- **XML** 提供精确定位（resource_id、bounds）
- **融合** 两者结合实现可靠的自动化定位符

## 项目结构

```
LXB-Framework/
├── android/LXB-Ignition/    # Android 服务 (Shizuku)
├── docs/
│   ├── zh/                  # 中文文档
│   └── en/                  # 英文文档
├── examples/                # 使用示例
├── src/
│   ├── cortex/              # Route-Then-Act 引擎
│   ├── auto_map_builder/    # 地图构建引擎
│   └── lxb_link/            # 设备通信
└── web_console/             # Web 界面
```

## 贡献

欢迎贡献！请随时提交 Pull Request。

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

---

<div align="center">

**[文档](docs/zh)** | **[示例](examples/)** | **[问题反馈](https://github.com/wuwei-crg/LXB-Framework/issues)**

</div>
