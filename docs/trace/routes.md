# 任务路线 Trace

任务路线 Trace 用来判断路线有没有命中、回放到哪一步、为什么失败，以及是否降级到视觉执行。

## `task_map_root_lookup`

表示系统正在检查这个任务有没有可用路线。

```json
{
  "task_id": "task-20260506-001",
  "state": "TASK_MAP_ROOT_LOOKUP",
  "route_id": "schedule:morning-checkin",
  "task_map_mode": "manual",
  "ts": "2026-05-06T09:00:01.700+0800",
  "event": "task_map_root_lookup"
}
```

| 字段 | 说明 |
| --- | --- |
| `state` | 当前处于路线检查阶段。 |
| `route_id` | 本次任务用于查找路线的内部标识。 |
| `task_map_mode` | 当前路线模式。`off` 通常表示未开启路线。 |

## `task_map_root_lookup_miss`

表示没有找到可用路线，任务会继续走普通流程。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "reason": "map_missing",
  "ts": "2026-05-06T09:00:01.720+0800",
  "event": "task_map_root_lookup_miss"
}
```

| 字段 | 说明 |
| --- | --- |
| `reason` | 未命中的原因。`map_missing` 表示没有保存过路线，`map_unusable` 表示路线不可用。 |

## `task_map_root_lookup_hit`

表示找到了可用任务路线。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "segment_count": 1,
  "step_count": 4,
  "ts": "2026-05-06T09:00:01.730+0800",
  "event": "task_map_root_lookup_hit"
}
```

| 字段 | 说明 |
| --- | --- |
| `segment_count` | 路线片段数量。 |
| `step_count` | 路线中的步骤数量。 |

## `fsm_routing_task_map_begin`

表示开始回放某一段任务路线。

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "steps": 4,
  "ts": "2026-05-06T09:00:05.100+0800",
  "event": "fsm_routing_task_map_begin"
}
```

| 字段 | 说明 |
| --- | --- |
| `package` | 正在回放路线的目标 App。 |
| `segment_id` | 当前路线片段。普通用户通常不需要关心。 |
| `steps` | 这一段路线包含多少步。 |

## `fsm_routing_task_map_step_start`

表示开始执行路线中的某一步。

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "source_action_id": "action-2",
  "index": 2,
  "op": "TAP",
  "ts": "2026-05-06T09:00:06.300+0800",
  "event": "fsm_routing_task_map_step_start"
}
```

| 字段 | 说明 |
| --- | --- |
| `index` | 当前是路线中的第几步，从 0 开始。 |
| `op` | 动作类型，例如 `TAP`、`SWIPE`、`INPUT`、`BACK`、`WAIT`。 |
| `step_id` / `source_action_id` | 内部调试用，反馈问题时保留即可。 |

## `fsm_routing_task_map_step_end`

表示路线中的某一步执行结束。判断路线失败时，这条最重要。

```json
{
  "index": 2,
  "step_id": "step-2",
  "source_action_id": "action-2",
  "op": "TAP",
  "picked_stage": "locator:text",
  "picked_bounds": [120, 1800, 960, 1920],
  "picked_point": [540, 1860],
  "result": "ok",
  "reason": "",
  "ts": "2026-05-06T09:00:06.520+0800",
  "event": "fsm_routing_task_map_step_end"
}
```

| 字段 | 说明 |
| --- | --- |
| `result` | 这一步是否成功。常见值：`ok`、`tap_fail`、`swipe_fail`、`resolve_fail`、`unsupported`。 |
| `reason` | 失败原因。为空通常表示成功。 |
| `picked_stage` | 系统用什么方式找到了目标，例如文字、结构、兜底点等。 |
| `picked_bounds` | 找到的控件区域。 |
| `picked_point` | 实际点击坐标。 |

如果导入的任务路线里包含需要本机适配的语义点击步骤，这一步还可能出现：

| 字段 | 说明 |
| --- | --- |
| `adaptation_status` | 语义步骤在本机的适配状态，例如已适配或适配失败。 |
| `portable_kind` | 这一步当前是可迁移语义步骤，还是已经沉淀成了本机可执行步骤。 |

这类字段通常只在“导入别的设备导出的任务”后第一次回放路线时出现。

## `llm_prompt_semantic_adaptation`

表示路线里有一个语义点击步骤还没有适配到本机，系统准备调用模型根据截图找到目标位置。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "prompt": "You are adapting a portable Android route step...",
  "ts": "2026-05-06T09:00:06.360+0800",
  "event": "llm_prompt_semantic_adaptation"
}
```

| 字段 | 说明 |
| --- | --- |
| `route_id` | 本次查找和保存路线时使用的内部路线标识。 |
| `segment_id` | 当前路线片段。 |
| `step_id` | 当前需要适配的步骤。 |
| `prompt` | 发给模型的语义定位提示词。内容较长，排查模型定位问题时有用。 |

## `llm_response_semantic_adaptation`

表示语义适配模型已经返回结果。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "response": "{\"result\":\"point\",\"x\":540,\"y\":1860}",
  "ts": "2026-05-06T09:00:07.020+0800",
  "event": "llm_response_semantic_adaptation"
}
```

| 字段 | 说明 |
| --- | --- |
| `response` | 模型原始返回。成功时通常会给出目标点；失败时可能返回无法匹配或目标不明确。 |

## `task_map_semantic_adaptation_materialized`

表示语义点击步骤已经成功适配到本机，并保存成后续可直接回放的本机路线步骤。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "portable_kind": "materialized",
  "adaptation_status": "adapted",
  "ts": "2026-05-06T09:00:07.180+0800",
  "event": "task_map_semantic_adaptation_materialized"
}
```

| 字段 | 说明 |
| --- | --- |
| `portable_kind` | 适配后的步骤类型。成功后通常表示已经变成本机可执行步骤。 |
| `adaptation_status` | 适配结果。成功时通常表示已适配。 |

看到这条 trace 后，同一台设备下次再跑这条任务路线时，通常不需要再次调用模型做语义定位。

## `task_map_semantic_adaptation_failed`

表示语义点击步骤没有适配成功。

```json
{
  "task_id": "task-20260506-001",
  "route_id": "schedule:morning-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "reason": "semantic_adaptation_no_match:target not found",
  "ts": "2026-05-06T09:00:07.200+0800",
  "event": "task_map_semantic_adaptation_failed"
}
```

| 字段 | 说明 |
| --- | --- |
| `reason` | 适配失败原因，例如截图失败、页面控件缺失、模型认为没有匹配目标或目标不唯一。 |

如果这里失败，任务路线通常会继续降级到视觉执行。常见处理方式是：确认导入任务的描述是否清楚、目标页面是否一致、当前页面是否被弹窗或广告遮挡。

## `fsm_routing_task_map_fallback`

表示路线回放失败，任务降级到视觉执行。

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "failed_index": 2,
  "reason": "task_map_locator_missing_fallback",
  "ts": "2026-05-06T09:00:07.000+0800",
  "event": "fsm_routing_task_map_fallback"
}
```

| 字段 | 说明 |
| --- | --- |
| `failed_index` | 第几步失败。可以结合 `step_start` / `step_end` 看具体动作。 |
| `reason` | 降级原因，例如控件找不到、坐标兜底失效、弹窗遮挡等。 |

## `fsm_routing_task_map_done`

表示路线回放成功。

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "steps": 4,
  "ts": "2026-05-06T09:00:08.500+0800",
  "event": "fsm_routing_task_map_done"
}
```

如果后面紧跟 `fsm_routing_task_map_finish_after_replay`，说明路线成功后任务直接结束。
