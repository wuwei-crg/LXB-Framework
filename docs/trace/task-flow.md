# 任务流程 Trace

任务流程 Trace 用来判断一条任务走到了哪个阶段。遇到“任务没动”“打开了错误 App”“任务突然失败”时，先看这一类。

## `task_route_key`

表示一次任务的路线身份已经确定。它通常出现在任务刚进入状态机后。

```json
{
  "task_id": "task-20260506-001",
  "source": "schedule",
  "source_id": "morning-checkin",
  "package_name": "com.example.app",
  "user_task": "打开 App，完成签到",
  "user_playbook": "进入首页后点击签到按钮",
  "task_map_mode": "manual",
  "route_id": "schedule:morning-checkin",
  "ts": "2026-05-06T09:00:01.120+0800",
  "event": "task_route_key"
}
```

| 字段 | 说明 |
| --- | --- |
| `task_id` | 本次任务运行的标识。反馈问题时可用它串起同一次任务的 trace。 |
| `source` | 任务来源，例如手动任务、定时任务或通知触发任务。 |
| `source_id` | 来源任务的内部标识。普通用户通常不需要关心。 |
| `package_name` | 任务指定或解析到的目标 App 包名。 |
| `user_task` | 用户填写的任务描述。 |
| `user_playbook` | 用户填写的操作文档。 |
| `task_map_mode` | 是否启用任务路线，以及路线模式。 |
| `route_id` | 本次任务查找路线时使用的内部路线标识。 |
| `ts` | 事件发生时间。 |
| `event` | 固定为 `task_route_key`。 |

## `fsm_state_enter`

表示状态机进入某个阶段。看这一类事件，可以知道任务卡在哪一步。

```json
{
  "task_id": "task-20260506-001",
  "state": "INIT",
  "user_task": "打开 App，完成签到",
  "ts": "2026-05-06T09:00:01.180+0800",
  "event": "fsm_state_enter"
}
```

| 字段 | 说明 |
| --- | --- |
| `task_id` | 本次任务。 |
| `state` | 当前进入的阶段，例如 `INIT`、`APP_RESOLVE`、`ROUTING`、`VISION_ACT`。 |
| `user_task` | 当前阶段处理的任务文本。有些阶段可能没有这个字段。 |
| `event` | 固定为 `fsm_state_enter`。 |

常见 `state`：

| 状态 | 说明 |
| --- | --- |
| `INIT` | 初始化设备状态、屏幕信息、输入能力。 |
| `TASK_DECOMPOSE` | 尝试把任务拆成更小步骤。 |
| `APP_RESOLVE` | 判断应该打开哪个 App。 |
| `ROUTE_PLAN` | 准备普通页面路由。 |
| `PREPARE_DEVICE` | 启动或切换到目标 App。 |
| `ROUTING` | 执行路线或页面跳转。 |
| `VISION_ACT` | 进入视觉模型观察和操作阶段。 |

## `fsm_init_ready`

表示初始化完成。它能帮助判断设备信息和当前前台 App 是否读取成功。

```json
{
  "task_id": "task-20260506-001",
  "device_info": {"width": 1080, "height": 2400, "density": 440},
  "current_activity": {"ok": true, "package": "com.android.launcher", "activity": "Launcher"},
  "app_candidates": 120,
  "page_candidates": 0,
  "text_input_support": {"adb_keyboard_installed": true},
  "ts": "2026-05-06T09:00:01.450+0800",
  "event": "fsm_init_ready"
}
```

| 字段 | 说明 |
| --- | --- |
| `device_info` | 屏幕宽高和密度。定位异常时可参考。 |
| `current_activity` | 当前前台应用和 Activity。 |
| `app_candidates` | core 读取到的候选应用数量。为 0 时可能影响 App 解析。 |
| `page_candidates` | 页面候选数量，通常用于调试。 |
| `text_input_support` | 输入能力状态，例如是否检测到 ADB Keyboard。 |

## `fsm_sub_task_begin` / `fsm_sub_task_end`

表示一个子任务开始或结束。一个用户任务可能会被拆成多个子任务。

```json
{
  "task_id": "task-20260506-001",
  "index": 0,
  "sub_task_id": "default",
  "mode": "single",
  "app_hint": "",
  "app_hint_used": false,
  "ts": "2026-05-06T09:00:02.100+0800",
  "event": "fsm_sub_task_begin"
}
```

```json
{
  "task_id": "task-20260506-001",
  "index": 0,
  "sub_task_id": "default",
  "mode": "single",
  "status": "success",
  "ts": "2026-05-06T09:00:18.400+0800",
  "event": "fsm_sub_task_end"
}
```

| 字段 | 说明 |
| --- | --- |
| `index` | 第几个子任务。 |
| `sub_task_id` | 子任务标识。普通用户无需关心具体值。 |
| `mode` | 子任务类型，例如单步任务。 |
| `status` | 子任务结果，常见为 `success` 或 `failed`。 |

## `fsm_app_resolve_done`

表示目标 App 已经确定。

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "app_name": "示例 App",
  "source": "llm",
  "ts": "2026-05-06T09:00:03.000+0800",
  "event": "fsm_app_resolve_done"
}
```

| 字段 | 说明 |
| --- | --- |
| `package` | 最终选择的目标 App 包名。 |
| `app_name` | App 名称。 |
| `source` | 选择来源，例如模型判断或兜底逻辑。 |

如果这里的 App 不对，通常需要在任务配置里手动选择目标 App，或者把任务描述写得更明确。