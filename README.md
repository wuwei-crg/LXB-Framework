<div align="center">

<img src="resources/logo.jpg" alt="LXB Logo" width="160" />

# LXB-Framework

**实验性安卓端自动化框架，专注于高频、线性的日常任务自动执行**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-34A853?logo=android&logoColor=white)]()
[![Latest Release](https://img.shields.io/github/v/release/wuwei-crg/LXB-Framework?label=Release)](https://github.com/wuwei-crg/LXB-Framework/releases)

[English](README.en.md) | **中文**

</div>

框架不依赖大模型去"自由探索"页面，而是采用 **Route-Then-Act** 流水线：预构建的导航地图负责确定性的页面跳转，视觉大模型只在真正"干活"时才介入。

---

## 软件展示与功能概述

![软件展示](resources/software_ch.png)

基于上述流水线设计，框架目前提供三种任务执行模式：

| 模式 | 说明 | 示例 |
|------|------|------|
| **对话任务** | 输入自然语言需求，立即执行一次 | `帮我在咖啡 App 下单一杯超大杯生椰拿铁` |
| **定时任务** | 设定触发时间（一次性 / 每天 / 每周），守护进程自动唤醒执行 | `工作日早上 08:30，自动帮我下单今日咖啡` |
| **Playbook 兜底** | 为暂无导航地图的 App 编写操作说明书，流水线按步执行 | — |

## 工作原理

Route-Then-Act 流水线的背后，由以下三个核心机制协同支撑：

- **流水线拆分** — 任务被切分为路由阶段（基于地图，无视觉推理）和动作阶段（VLM 处理动态 UI），各司其职。
- **FSM 状态机编排** — 完整的状态机（INIT → TASK_DECOMPOSE → APP_RESOLVE → ROUTE_PLAN → ROUTING → VISION_ACT → FINISH/FAIL）让执行流程可控、可追踪。
- **`app_process` 守护进程** — 后端以 shell 级进程运行，独立于 Android 应用生命周期，无需依赖系统脆弱的 Service 保活机制，支持可靠的后台执行和定时任务。

![整体架构](resources/architecture_overall.png)

![Framework 内部架构](resources/architecture_LXB-Framework.png)

## 环境要求

在开始之前，请确认以下条件已满足：

- Android **11（API 30）** 及以上真机（模拟器亦可，但部分 App 可能有模拟器检测）
- 开启手机**开发者选项**和**无线调试**（无需 Root，无需额外安装其他 App）
- 兼容 **OpenAI API 格式**的 LLM/VLM 接口（`/v1/chat/completions`），支持任意模型提供商

## 快速开始

满足环境要求后，按以下步骤完成首次部署：

1. **开启开发者选项与调试开关**
   - 进入手机「设置 → 开发者选项」，打开「USB 调试」和「无线调试」
   - **必须开启 USB 调试，否则进程无法保活**

2. **按机型检查开发者选项限制**（部分 ROM 必做）

   | ROM | 操作 |
   |-----|------|
   | MIUI / HyperOS（小米、POCO） | 开启「USB 调试（安全设置）」（与「USB 调试」是两个独立选项） |
   | ColorOS（OPPO / OnePlus） | 关闭「权限监控」 |
   | Flyme（魅族） | 关闭「Flyme 支付保护」 |

3. **安装 APK** — 从 [Releases](https://github.com/wuwei-crg/LXB-Framework/releases) 下载最新版 `lxb-ignition-vX.Y.Z.apk` 并安装

4. **配对设备** — 打开 LXB-Ignition，按照 App 内引导完成配对。手机屏幕上会显示一个六位配对码，在 App 弹出的通知栏中输入即可完成首次配对，后续启动会自动重连

5. **启动守护进程** — 配对成功后，App 自动将后端 DEX 推送到设备并通过 `app_process` 启动守护进程。界面状态指示变为 **运行中** 即表示就绪

6. **配置模型接口** — 进入 `Config` 页签，填写以下信息：

   | 参数 | 说明 | 示例 |
   |------|------|------|
   | API Base URL | 模型接口地址（兼容 OpenAI 格式） | `https://api.openai.com/v1` |
   | API Key | 对应的密钥 | `sk-...` |
   | Model | 模型名称 | `gpt-4o-mini`、`qwen-plus` |

7. **（可选）同步地图** — 在 `Config` 中配置 MapRepo 地址，启用后框架会自动拉取稳定地图。未配置时降级为纯视觉模式

## 运行第一个任务

部署完成后，在首页对话框中输入需求即可启动任务，例如：

```
打开 Bilibili，发一条动态，内容为 test，标题为 test
打开微信，给文件传输助手发一条消息：hello
```

执行过程中界面会实时显示当前 FSM 状态（ROUTE_PLAN → ROUTING → VISION_ACT）。

## 定时任务

除了手动触发，框架还支持定时自动执行。在 `Tasks` 页签中创建定时任务：

- 设置触发时间（一次性、每天或每周）
- 指定目标 App 包名
- 编写任务需求
- 可选：为无地图的 App 附上 **Playbook**

得益于 `app_process` 守护进程，定时任务在息屏或 App 被杀后依然会准时执行。

## 为新 App 构建导航地图

如前所述，导航地图是 Route-Then-Act 流水线中路由阶段的基础。地图通过 [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) 构建，通过 [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) 分发。完整建图流程见 MapBuilder README。已有的稳定地图可直接在 `Config` 页签中通过 MapRepo 地址一键同步。

## 使用建议

- 将 LXB-Ignition 的电池策略设为 **无限制**（尤其是 MIUI / ColorOS / HyperOS / Honor 等系统）。
- 对于暂无地图的 App，提前编写简短的 **Playbook** 可以显著提升动作执行的稳定性。

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) | 建图与地图发布工具 |
| [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) | stable / candidate 导航地图仓库 |

## 致谢

`app_process` 守护进程的设计思路参考了 [Shizuku](https://github.com/RikkaApps/Shizuku)。LXB-Framework 自行实现了 Wireless ADB 配对与连接，运行时不依赖 Shizuku。
本项目也在 [LINUX DO 社区](https://linux.do/) 进行分享与交流，感谢社区的支持与反馈。

第三方声明见：[THIRD_PARTY_NOTICES.zh.md](THIRD_PARTY_NOTICES.zh.md)

## 许可证

MIT，见 [LICENSE](LICENSE)。

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/LXB-Framework&type=Date)](https://star-history.com/#wuwei-crg/LXB-Framework&Date)
