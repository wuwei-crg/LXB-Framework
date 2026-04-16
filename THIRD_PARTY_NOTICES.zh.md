# 第三方声明

本文档用于说明 AutoLXB 中涉及的第三方参考来源。

## Shizuku

- 项目地址：https://github.com/RikkaApps/Shizuku
- 许可证：Apache License 2.0
- 参考过的上游路径：
  - `manager/src/main/jni/starter.cpp`
  - `starter/src/main/java/moe/shizuku/starter/ServiceStarter.java`

### 参考的部分（思路层）

以下内容属于对 Shizuku 公开实现的思路参考：

1. **Starter 启动模型（`app_process` bootstrap）**
   - 使用小型 native starter 通过 `app_process` 拉起 Java 服务主类。
   - 让启动路径尽量不依赖 APK 前台生命周期。

2. **守护式拉起流程**
   - 在 `exec(app_process)` 前采用 `fork + setsid + chdir("/") + 标准流重定向`。
   - 使用 `--nice-name` 保持进程名稳定可观测。

3. **启动/停止生命周期处理**
   - 启动前先清理旧进程，再执行新一轮拉起。
   - 通过显式 start/stop 控制进程，而不是依赖应用生命周期隐式控制。

### AutoLXB 的独立实现部分

以下模块为 AutoLXB 自主实现，不是 Shizuku 代码复制：

- LXB 协议/TCP 通信与命令体系
- Cortex FSM / Route-Then-Act 执行链路
- 任务调度、地图同步、运行时数据模型
- Wireless bootstrap 的状态机、验活策略与诊断流程

### 合规说明

本声明用于明确“架构思路参考”与“工程实现独立”的边界，避免歧义。  
Apache 2.0 完整文本请见：
http://www.apache.org/licenses/LICENSE-2.0
