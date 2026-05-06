# 动作执行 Trace

动作执行 Trace 用来确认 AutoLXB 是否真的点击、滑动、输入或返回了。视觉模型说“我要点击”不代表动作一定执行成功，最终要看执行 trace。

## `exec_tap_start` / `exec_tap_done`

表示开始点击和点击完成。

```json
{
  "task_id": "task-20260506-001",
  "x": 540,
  "y": 1860,
  "ts": "2026-05-06T09:00:12.700+0800",
  "event": "exec_tap_start"
}
```

```json
{
  "task_id": "task-20260506-001",
  "x": 540,
  "y": 1860,
  "ts": "2026-05-06T09:00:12.760+0800",
  "event": "exec_tap_done"
}
```

| 字段 | 说明 |
| --- | --- |
| `x` / `y` | 实际点击坐标。 |

如果模型输出了点击，但没有看到 `exec_tap_start`，说明动作可能没进入执行阶段。

## `exec_swipe_start` / `exec_swipe_done`

表示开始滑动和滑动完成。

```json
{
  "task_id": "task-20260506-001",
  "x1": 540,
  "y1": 1800,
  "x2": 540,
  "y2": 600,
  "duration": 600,
  "ts": "2026-05-06T09:00:14.000+0800",
  "event": "exec_swipe_start"
}
```

| 字段 | 说明 |
| --- | --- |
| `x1` / `y1` | 滑动起点。 |
| `x2` / `y2` | 滑动终点。 |
| `duration` | 滑动持续时间，单位毫秒。 |

## `exec_input_start`

表示开始输入文本。

```json
{
  "task_id": "task-20260506-001",
  "text": "hello",
  "ts": "2026-05-06T09:00:16.000+0800",
  "event": "exec_input_start"
}
```

| 字段 | 说明 |
| --- | --- |
| `text` | 准备输入的文本。反馈隐私问题时注意打码。 |

## `exec_input_try`

表示尝试一种输入方式。

```json
{
  "task_id": "task-20260506-001",
  "try": 1,
  "method": 0,
  "status": 1,
  "actual_method": 0,
  "ts": "2026-05-06T09:00:16.200+0800",
  "event": "exec_input_try"
}
```

| 字段 | 说明 |
| --- | --- |
| `try` | 第几次尝试。 |
| `method` | 计划使用的输入方式。内部数字，反馈时保留即可。 |
| `actual_method` | 实际使用的输入方式。 |
| `status` | 输入状态。通常 `1` 表示成功。 |

## `exec_input_result`

表示输入动作最终结果。

```json
{
  "task_id": "task-20260506-001",
  "text": "hello",
  "method_auto": true,
  "chosen_method": 0,
  "actual_method": 0,
  "status": 1,
  "ts": "2026-05-06T09:00:16.260+0800",
  "event": "exec_input_result"
}
```

如果 `status` 不是 `1`，通常表示输入失败。中文输入失败时，优先检查是否安装并启用了 ADB Keyboard。

## `exec_wait_start` / `exec_wait_done`

表示任务主动等待。

```json
{
  "task_id": "task-20260506-001",
  "ms": 1000,
  "ts": "2026-05-06T09:00:17.000+0800",
  "event": "exec_wait_start"
}
```

| 字段 | 说明 |
| --- | --- |
| `ms` | 等待时长，单位毫秒。 |

## `exec_back_start` / `exec_back_done`

表示执行系统返回键。

```json
{
  "task_id": "task-20260506-001",
  "ts": "2026-05-06T09:00:18.000+0800",
  "event": "exec_back_start"
}
```

如果任务反复返回或回到错误页面，可以结合视觉执行 trace 看模型为什么选择返回。