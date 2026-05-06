# 通知触发 Trace

通知触发相关的 core trace 会统一使用 `notify_trigger` 这个事件名。它表示“通知模块产生了一条记录”，具体是启动、跳过、命中还是提交失败，需要继续看同一条记录里的其它字段。

你会看到类似这样的结构：

```json
{
  "ts": "2026-05-06T09:00:00.000+0800",
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "post_time": 1778040000000,
  "title": "WuWei",
  "text": "在吗",
  "rule_id": "rule-1",
  "rule_name": "回复微信消息",
  "task_id": ""
}
```

注意：导出的 core trace 里，`event` 字段会显示为 `notify_trigger`。通知模块内部还有更细的记录类型，例如模块启动、冷却跳过、LLM 条件不匹配、任务提交成功等；在 core trace 中主要通过 `package`、`rule_name`、`raw`、`error`、`final_task` 这些字段判断发生了什么。

## 通知基础字段

| 字段 | 说明 |
| --- | --- |
| `package` | 通知来自哪个 App。 |
| `post_time` | 通知发布时间。 |
| `title` | 通知标题，最多保留一段长度。 |
| `text` | 通知正文，最多保留一段长度。 |
| `rule_id` | 命中的规则内部标识。反馈问题时保留即可。 |
| `rule_name` | 规则名称。 |
| `raw` | LLM 条件判断或任务重写的原始输出，可能为空。 |
| `error` | 失败原因或跳过原因。为空通常表示没有错误。 |
| `final_task` | 通知规则最终提交给任务系统的任务描述。只有成功提交任务或重写成功后才常见。 |

## 模块启动记录

表示通知监听模块已经启动。

```json
{
  "event": "notify_trigger",
  "error": "poll_ms=3000",
  "task_id": "",
  "ts": "2026-05-06T09:00:00.000+0800"
}
```

这里 `poll_ms=3000` 表示通知轮询间隔。看到这类事件，说明通知模块本身在运行。

## 基线建立记录

表示通知模块已经建立基线。基线建立后，后续新通知才会被当作候选通知处理。

```json
{
  "event": "notify_trigger",
  "error": "last_post_time=1778040000000",
  "task_id": "",
  "ts": "2026-05-06T09:00:03.000+0800"
}
```

## 任务提交记录

表示通知规则命中，并且已经成功提交自动化任务。

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "post_time": 1778040001234,
  "title": "WuWei",
  "text": "在吗",
  "rule_id": "rule-1",
  "rule_name": "回复微信消息",
  "task_id": "",
  "final_task": "打开微信，找到 WuWei，回复他的最新消息一次",
  "ts": "2026-05-06T09:01:10.000+0800"
}
```

| 字段 | 说明 |
| --- | --- |
| `final_task` | 最终提交给任务系统的任务描述。 |
| `task_id` | 通知模块记录不是某次任务执行内部产生的，所以这里通常为空。通知真正提交后，任务执行阶段会产生自己的任务流程 trace。 |

如果你看到带 `final_task` 的通知记录，但后续没有任务流程 trace，通常需要检查 core 是否正在运行、当前是否已有任务占用队列、任务列表里是否生成了对应任务。

## LLM 条件不匹配记录

表示包名、标题、正文等普通条件通过了，但 LLM 条件判断为不匹配。

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "收到，谢谢",
  "rule_id": "rule-1",
  "rule_name": "回复微信消息",
  "raw": "no",
  "task_id": "",
  "ts": "2026-05-06T09:02:00.000+0800"
}
```

如果你以为通知应该触发，但看到这类事件，说明 LLM 条件写得可能太严格。

## 冷却跳过记录

表示规则命中了，但还在触发间隔内，所以跳过。

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "再发一条",
  "rule_id": "rule-1",
  "rule_name": "回复微信消息",
  "error": "skip",
  "task_id": "",
  "ts": "2026-05-06T09:02:20.000+0800"
}
```

| 字段 | 说明 |
| --- | --- |
| `error` | 这里通常是 `skip`，表示这次通知被规则策略跳过。 |

## 任务提交失败记录

表示规则命中，但提交任务失败。

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "在吗",
  "rule_id": "rule-1",
  "rule_name": "回复微信消息",
  "error": "java.lang.IllegalStateException: task queue full",
  "task_id": "",
  "ts": "2026-05-06T09:03:00.000+0800"
}
```

遇到这类事件，需要结合当时是否已有任务在运行、core 是否正常工作一起排查。
