# 视觉执行 Trace

视觉执行 Trace 用来判断模型是否拿到了截图、输出了什么动作、输出是否能被解析，以及动作是否执行成功。

## `vision_screenshot_ready`

表示本轮视觉执行已经成功获取截图。

```json
{
  "task_id": "task-20260506-001",
  "size": 384221,
  "attached": true,
  "ts": "2026-05-06T09:00:10.100+0800",
  "event": "vision_screenshot_ready"
}
```

| 字段 | 说明 |
| --- | --- |
| `size` | 截图大小，单位字节。大于 0 通常表示截图正常。 |
| `attached` | 是否已把截图附给模型请求。 |

如果出现 `vision_screenshot_failed` 或 `vision_screenshot_error`，说明截图阶段失败，视觉模型可能无法观察页面。

## `llm_prompt_vision_act`

表示即将向模型发送视觉执行提示词。

```json
{
  "task_id": "task-20260506-001",
  "state": "VISION_ACT",
  "attempt": 1,
  "prompt": "You are controlling an Android phone...",
  "ts": "2026-05-06T09:00:10.200+0800",
  "event": "llm_prompt_vision_act"
}
```

| 字段 | 说明 |
| --- | --- |
| `state` | 当前阶段，视觉执行里通常是 `VISION_ACT`。 |
| `attempt` | 第几次尝试。解析失败或请求失败时可能重试。 |
| `prompt` | 发给模型的提示词。内容较长，排查复杂问题时有用。 |

## `llm_response_vision_act`

表示模型返回了原始响应。

```json
{
  "task_id": "task-20260506-001",
  "state": "VISION_ACT",
  "attempt": 1,
  "response": "<Observing>当前在首页...</Observing><command>TAP 540 1860</command>",
  "ts": "2026-05-06T09:00:12.300+0800",
  "event": "llm_response_vision_act"
}
```

| 字段 | 说明 |
| --- | --- |
| `response` | 模型原始输出。这里可以看到模型理解了什么、准备执行什么。 |
| `attempt` | 本次响应对应第几次尝试。 |

## `llm_structured_vision_act`

表示模型输出已经被解析成结构化结果。这条比原始响应更适合普通排查。

```json
{
  "task_id": "task-20260506-001",
  "state": "VISION_ACT",
  "data": {
    "Observing": "当前在 App 首页",
    "Ovserve_result": "底部可以看到签到入口",
    "Thinking": "需要进入签到页面",
    "action": "点击签到入口",
    "expected": "进入签到页面",
    "command": "TAP 540 1860"
  },
  "command": "TAP 540 1860",
  "ts": "2026-05-06T09:00:12.360+0800",
  "event": "llm_structured_vision_act"
}
```

| 字段 | 说明 |
| --- | --- |
| `data.Observing` | 模型对当前画面的观察。 |
| `data.Thinking` | 模型为什么要这么做。 |
| `data.action` | 模型准备执行的人类可读动作。 |
| `data.expected` | 模型预期动作后页面会发生什么。 |
| `command` | 实际要交给执行器的命令。 |

## `vision_retry`

表示视觉执行发生了可重试问题，例如模型请求失败、动作执行失败、输出格式不符合要求。

```json
{
  "task_id": "task-20260506-001",
  "state": "VISION_ACT",
  "phase": "parse",
  "attempt": 1,
  "max_attempts": 3,
  "error": "missing <command> tag",
  "retrying": true,
  "ts": "2026-05-06T09:00:12.500+0800",
  "event": "vision_retry"
}
```

| 字段 | 说明 |
| --- | --- |
| `phase` | 出问题的阶段，例如 `planner_call`、`parse`、`command_args`、`action_exec`。 |
| `attempt` | 当前第几次尝试。 |
| `max_attempts` | 最多尝试次数。 |
| `error` / `reason` | 失败原因。不同阶段字段名可能不同。 |
| `retrying` | 是否还会继续重试。 |

## `vision_instruction_invalid`

表示模型输出多次无法解析，视觉执行失败。

```json
{
  "task_id": "task-20260506-001",
  "state": "VISION_ACT",
  "error": "missing <command> tag",
  "ts": "2026-05-06T09:00:15.100+0800",
  "event": "vision_instruction_invalid"
}
```

如果经常出现这类 trace，通常说明模型不太适合当前视觉执行格式，或者模型输出没有按要求返回命令。