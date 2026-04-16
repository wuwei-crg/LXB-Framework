<div align="center">

<img src="resources/logo.jpg" alt="AutoLXB Logo" width="160" />

# AutoLXB

**实验性安卓端自动化框架，专注于高频、线性的日常任务自动执行**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-34A853?logo=android&logoColor=white)]()
[![Latest Release](https://img.shields.io/github/v/release/wuwei-crg/AutoLXB?label=Release)](https://github.com/wuwei-crg/AutoLXB/releases)

[English](README.en.md) | **中文**

</div>

AutoLXB 不让模型自由探索整个手机界面，而是采用 **Route-Then-Act** 流水线：先尽量用确定性的路线完成页面跳转，再把真正需要“动手”的动态界面交给视觉模型处理。

---

## 软件展示与功能概览

![软件展示](resources/software_ch.png)

当前版本的核心能力：

- **直接任务**：输入一句自然语言需求，立即执行一次任务
- **定时任务**：支持单次 / 每天 / 每周执行，并可在列表页直接启用或停用
- **通知触发任务**：根据包名、标题、正文和可选 LLM 条件，从通知中触发任务
- **任务路线编辑**：每个任务可沉淀自己的 task route，并在前端手动删减轨迹动作后保存为最终路线
- **Playbook 兜底**：为暂时没有稳定路线的 App 补充操作说明，帮助 Vision 阶段更稳定落地
- **Root / Wireless ADB 双启动方式**：未 Root 设备走无线调试引导，Root 设备可直接启动 core
- **Trace 日志页**：按条展示 core trace，支持详情查看、结构化字段解析与本地导出

## 工作原理

Route-Then-Act 流水线由以下几部分协同支撑：

- **阶段拆分**：任务被拆成“路线跳转”和“视觉执行”两部分。能稳定靠路线走完的部分尽量不交给视觉模型。
- **FSM 状态机编排**：`INIT -> TASK_DECOMPOSE -> APP_RESOLVE -> ROUTING -> VISION_ACT -> FINISH/FAIL`
- **`app_process` 守护进程**：`lxb-core` 以 shell 级后台进程运行，脱离普通 Android App 生命周期，更适合常驻、定时任务和通知触发
- **设备端前后端分离**：`AutoLXB` 负责启动、配置、任务管理与日志查看；`lxb-core` 负责本机自动化执行


## 当前产品形态

当前 App 主要分为四个页面：

- **首页**：启动 / 停止 core、查看运行状态、进入首次配置
- **任务页**：快速任务、定时任务、通知触发任务、最近运行记录
- **配置页**：操控方式配置、设备端 LLM 配置、解锁与锁屏策略、路线同步与来源、语言设置
- **日志页**：trace 卡片、详情弹窗、trace 导出

当前产品设计上的关键点：

- 首页优先服务“**先启动 core**”这一件事。core 已运行时突出停止；未运行时突出两种启动方式。
- 任务页把“**任务运行状态**”提前，自动化能力分成 **快速任务 / 定时任务 / 通知触发任务 / 最近运行记录** 四块。
- 定时任务和通知触发任务都支持在 **列表页直接开关**，不必进入编辑页。
- 路线编辑页目前是 **纯手动审查模式**：编辑的是“最近一次捕获的轨迹”，不要求必须成功完成过。
- `task route mode` 前端展示为 **开 / 关**：开表示按路线执行。
- 输入策略优先支持 **ADB Keyboard**；未安装时自动回退。
- 触控策略支持 **Shell / UIAutomator** 两条注入路径。
- 中文系统默认中文，其他系统默认英文；手动切换语言后优先使用用户选择。

## 环境要求

开始前请确认：

- Android **11（API 30）** 及以上真机
- 使用 **Wireless ADB** 启动时：已打开开发者选项、USB 调试、无线调试
- 使用 **Root** 启动时：设备已 Root，且能授予 `su` 权限
- 已配置兼容 **OpenAI Chat Completions** 风格的 LLM / VLM 接口
  - 现在会自动补齐 `/chat/completions`
  - 你可以填写更高层的 base URL，App 会实时显示最终真实请求地址

## 快速开始

### 1. 安装与手机准备

1. 从 [Releases](https://github.com/wuwei-crg/AutoLXB/releases) 安装最新 APK
2. 打开手机开发者选项，并确保以下开关已打开：
   - `USB 调试`
   - **必须开启 USB 调试，否则进程可能无法保活**
   - 无 Root 设备还需要打开 `无线调试`
3. 某些国产 ROM 还需要额外设置：

   | ROM | 操作 |
   |-----|------|
   | MIUI / HyperOS（小米、POCO） | 开启 `USB 调试（安全设置）` |
   | ColorOS（OPPO / OnePlus） | 关闭“权限监控” |
   | Flyme（魅族） | 关闭“Flyme 支付保护” |

4. 建议将 `AutoLXB` 的电池策略设为 **无限制**，避免后台任务被系统杀掉

### 2. 启动 AutoLXB Core

- **有 Root**：在首页点击 **Root 启动**，确认系统可以授予 `su` 权限
- **无 Root**：在首页点击 **ADB 启动**，按引导完成一次无线 ADB 配对
- 配对完成后，后续通常只需要保持无线调试打开，再点击 **ADB 启动**，不需要每次重新配对

### 3. 配置模型

进入 `配置 -> 设备端 LLM 配置`，填写：

- `API Base URL`
- `API Key`
- `Model`

请使用 **支持图像识别能力的模型**。配置后点击测试，确认模型可以处理图片并返回结果。

### 4. 创建自动任务

AutoLXB 主要面向“重复、线性、可触发”的手机任务。优先使用下面两类任务：

- **定时任务**：在指定时间或周期自动执行，例如每天早上打开某 App 完成签到
- **通知触发任务**：监听指定 App 的通知，满足条件后自动执行，例如收到某个群聊消息后自动回复

任务描述建议写得具体一点，例如：

```text
打开某 App，进入签到页面，完成签到
打开微信，进入某个群聊，回复刚刚发消息的人
打开外卖 App，进入订单页面，查看骑手位置
```

如果不确定任务描述是否稳定，可以先用 **快速任务** 试跑一次。确认没问题后，再把同样的任务描述保存成定时任务或通知触发任务。

### 5. 可选：保存任务路线

对于经常重复执行的任务，可以开启任务路线。任务执行后，你可以在路线编辑里保留有用步骤、移出无关动作，后续执行时会优先按路线跳转，减少视觉模型消耗。

## 首次配置建议

完成启动后，建议先在 `Config` 中检查以下配置。

### 1. 操控方式配置

这里决定设备如何执行点击、滑动和输入：

- **触控模式**：`Shell` / `UIAutomator`
- **输入模式**：优先推荐安装 **ADB Keyboard**
- **任务期间免打扰**：不处理 / 关闭声音提醒 / 完全静音

### 2. 设备端 LLM 配置

需要填写：

- `API Base URL`
- `API Key`
- `Model`

当前还支持：

- **真实请求 URL 实时预览**
- **多份本地 LLM 配置保存与切换**
- **API Key 密码样式显示**
- **模型测试并同步到设备**

### 3. 解锁与锁屏策略

- 是否在路线执行前自动解锁
- 是否在任务结束后自动锁屏
- 锁屏密码 / PIN（仅在单纯上滑不足以解锁时使用）

### 4. 路线同步与来源

- 设置 MapRepo 地址
- 选择运行时 map source（`stable` / `candidate` / `burn`）
- 按包名或标识拉取路线资产
- 查看当前 active map 状态

## 任务能力

### 直接任务

在首页或任务页的快速任务中输入一句自然语言需求即可，例如：

```text
打开微信，给文件传输助手发送一条消息：hello
打开 Bilibili，发布一条动态，标题是 test，内容是 test
```

### 定时任务

在 `Tasks -> Schedules` 中创建：

- 支持 **单次 / 每天 / 每周**
- 可指定目标包名
- 可附加 Playbook
- 可配置是否录屏
- 可配置是否按路线执行
- 支持在 **列表页直接启用 / 停用**

### 通知触发任务

在 `Tasks -> Notification Triggers` 中创建规则：

- 包名匹配（必填）
- 标题匹配 / 正文匹配（可选）
- LLM 条件判断（可选）
- 触发时段（可选）
- 触发录屏（可选）
- 是否按路线执行
- 支持在 **列表页直接启用 / 停用**

当前通知触发链路：

1. dump 通知
2. 找到新通知
3. 按规则串行匹配
4. 命中后生成最终任务
5. 推送到 core 队列执行

## 任务路线（Task Route）

现在的设计重点已经从“全局 app map”进一步落到“**每个任务自己的路线资产**”。

### 当前逻辑

- 任务执行时，core 会记录最近一次轨迹
- 如果任务成功，也会额外保留最近成功轨迹
- 前端路线编辑页现在默认基于 **最近一次捕获的轨迹** 进行编辑
- 你可以删除噪声动作，然后手动保存为最终 task route
- 保存后的路线可以在后续执行中直接参与 routing

### 目前前端状态

- **AI 辅助优化路线功能已暂时隐藏**
- 当前只保留 **手动审查 / 手动删减 / 手动保存**
- “回放结束后直接结束任务” 开关用于控制：
  - 路线回放成功后，是否直接结束当前任务
  - 还是继续进入后续视觉执行

## Trace 与调试

日志页现在是结构化 trace 视图，而不是简单文本面板：

- 每条 trace 单独显示为卡片
- 点击卡片查看结构化详情
- 默认优先加载最新 trace
- 向上滑动时再按需加载更早记录
- 支持导出本地缓存 trace

如果你在调试 FSM 状态切换、通知触发链路、路线回放或视觉执行失败，这一页会比旧式日志更有用。

## 使用建议

- 将 `AutoLXB` 的电池策略设为 **无限制**
- 未安装 ADB Keyboard 时，中文输入会回退到剪贴板 / shell 输入路径，兼容性可能因 App 而异
- 对于没有稳定路线的 App，尽量写简短明确的 Playbook
- 部分 ROM 更适合 `Shell`，部分更适合 `UIAutomator`，可在配置页切换测试

## 开发者调试（本地联调）

改完代码后，可直接安装 Debug 版到手机：

1. 连接设备，确认 `adb devices` 能看到设备
2. 进入 `android/LXB-Ignition`
3. 执行：

```bash
./gradlew :app:installDebug
```

安装完成后，直接打开手机上的 Debug 版 `AutoLXB` 进行调试。

## 相关仓库

| 仓库 | 说明 |
|------|------|
| [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) | 建图与路线发布工具 |
| [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) | stable / candidate 路线仓库 |

## 致谢

`app_process` 守护进程的设计思路参考了 [Shizuku](https://github.com/RikkaApps/Shizuku)。

AutoLXB 自行实现了 Wireless ADB 配对、连接与启动流程，运行时不依赖 Shizuku。本项目也在 [LINUX DO 社区](https://linux.do/) 持续分享与交流。

第三方声明见：[THIRD_PARTY_NOTICES.zh.md](THIRD_PARTY_NOTICES.zh.md)

## 许可证

MIT，见 [LICENSE](LICENSE)。

## Star 趋势

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/AutoLXB&type=Date)](https://star-history.com/#wuwei-crg/AutoLXB&Date)
