# 端侧架构总览（当前版本）

## 适用范围

本文描述 `AutoLXB` 当前主线实现。

主线目标：

1. Android APK 端侧闭环
2. Java 后端运行时（`lxb-core`）
3. 设备侧执行 Route-Then-Act 状态机

本仓库已移除 Python Cortex/WebConsole 历史实现路径。

## 运行时分层

1. App 前端层（`android/LXB-Ignition/app`）
- Chat 形态任务输入
- 配置与任务/定时任务管理
- 基于 trace 事件的实时状态显示

2. 后端服务层（`android/LXB-Ignition/lxb-core`）
- UDP 命令服务（`network/UdpServer.java`）
- Cortex 门面（`cortex/CortexFacade.java`）
- FSM 引擎（`cortex/CortexFsmEngine.java`）
- 任务与调度管理（`cortex/CortexTaskManager.java`）
- 路由地图能力（`cortex/MapManager.java`、`cortex/RouteMap.java`）

3. 系统执行层
- 通过 Shizuku + `app_process` 拉起后端进程
- 端侧执行输入操作、截图、Activity/屏幕状态感知

4. 模型调用层
- 由端侧运行时发起 LLM/VLM 请求
- FSM 消费模型结果并产出可执行指令

## 核心流程

1. 用户在 APK 中提交任务
2. 任务进入 `CortexTaskManager` 队列
3. FSM 按子任务执行：
- `INIT`
- `APP_RESOLVE`
- `ROUTE_PLAN`
- `ROUTING`
- `VISION_ACT`
4. 关键 trace 事件回推到前端 UI
5. 任务结束并持久化结果元数据

## 地图能力

当前形态：

1. 运行时通过 `MapManager` 读取本地 map
2. map 缺失或不可用时，直接降级 `VISION_ACT`

仓库职责拆分：

1. MapBuilder 与 map web 工具迁移到 `LXB-MapBuilder`
2. `AutoLXB` 仅保留运行时 map 消费逻辑

## 调度与持久化

1. schedule 与 task 状态存储在 APK 私有目录
2. 调度器按设定时间触发任务入队
3. 执行线程按队列顺序串行执行，保证可预测性

## Legacy 边界

以下内容在本仓库中不再是当前权威实现：

1. 旧 WebConsole 相关文档与接口
2. Python 文档中的旧 map_builder 路径

后续请优先以本文和根目录 README 为准进行维护。
