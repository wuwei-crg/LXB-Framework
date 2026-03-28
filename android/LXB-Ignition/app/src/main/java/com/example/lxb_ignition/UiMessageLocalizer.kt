package com.example.lxb_ignition

object UiMessageLocalizer {
    fun localize(uiLang: String, text: String): String {
        if (uiLang != "zh") {
            return text
        }
        return when {
            text.startsWith("Invalid lxb-core port") -> "lxb-core 端口无效，请在配置页检查 TCP 端口。"
            text == "Please enter a task description before sending to PC." -> "发送到 PC 前请先输入任务描述。"
            text == "web_console IP is missing. Please fill it in Config tab." -> "web_console IP 未配置，请在配置页填写。"
            text == "Please enter a task description before running on device." -> "设备端执行前请先输入任务描述。"
            text == "Task received, checking lxb-core server status..." -> "任务已接收，正在检查 lxb-core 服务状态..."
            text == "Server is not running, please start the service first." -> "服务未运行，请先启动服务。"
            text == "Server is running, calling Cortex FSM on device..." -> "服务已运行，正在调用设备端 Cortex FSM..."
            text.startsWith("Task id: ") -> "任务 ID: " + text.removePrefix("Task id: ")
            text == "Cancel requested for current task." -> "已请求取消当前任务。"
            text.startsWith("Failed to send cancel request: ") -> "发送取消请求失败: " + text.removePrefix("Failed to send cancel request: ")
            text == "Schedule task cannot be empty." -> "定时任务描述不能为空。"
            text == "Please pick a valid date and time." -> "请选择有效的日期和时间。"
            text == "run_at must be in the future." -> "run_at 必须是未来时间。"
            text == "Please select at least one weekday for weekly repeat." -> "每周重复模式至少需要选择一个星期几。"
            text == "schedule_id is empty." -> "schedule_id 为空。"
            text == "APP_RESOLVE: selecting the best app for this task..." -> "APP_RESOLVE：正在为任务选择最合适的应用..."
            text == "ROUTE_PLAN: planning navigation route using map..." -> "ROUTE_PLAN：正在基于地图规划路由..."
            text == "ROUTING: executing route on device..." -> "ROUTING：正在设备上执行路由..."
            text == "VISION_ACT: entering vision-action loop (LLM + VLM)." -> "VISION_ACT：进入视觉执行循环（LLM + VLM）。"
            text == "Task finished successfully." -> "任务执行成功。"
            text == "Task finished with failure." -> "任务执行失败。"
            text == "Decomposing the request into sub-tasks..." -> "正在将请求拆分为子任务..."
            text.startsWith("APP_RESOLVE failed: ") -> "APP_RESOLVE 失败: " + text.removePrefix("APP_RESOLVE failed: ")
            text.startsWith("Route planning failed: ") -> "路由规划失败: " + text.removePrefix("Route planning failed: ")
            text.startsWith("Routing failed: ") -> "路由执行失败: " + text.removePrefix("Routing failed: ")
            text == "Cancel requested, FSM will stop at the next safe point." -> "已请求取消，FSM 会在下一个安全点停止。"
            text == "Task cancelled by user." -> "任务已由用户取消。"
            text == "Screenshot captured, calling vision model for next action..." -> "已截图，正在调用视觉模型规划下一步..."
            text.startsWith("Calling LLM + VLM for next step planning...") -> "正在调用 LLM + VLM 规划下一步..."
            text.startsWith("Vision model responded.") -> "视觉模型已返回。"
            text.startsWith("Vision action output was invalid: ") -> "视觉动作输出无效: " + text.removePrefix("Vision action output was invalid: ")
            text == "Vision action output was invalid, stopping this task." -> "视觉动作输出无效，停止当前任务。"
            text == "Repeated ineffective actions detected, stopping to avoid loop." -> "检测到重复无效动作，为避免死循环已停止。"
            text.startsWith("Planner call failed: ") -> "规划调用失败: " + text.removePrefix("Planner call failed: ")
            text.startsWith("Map sync failed: ") -> "地图同步失败: " + text.removePrefix("Map sync failed: ")
            text.startsWith("Pull stable map failed: ") -> "拉取 Stable 地图失败: " + text.removePrefix("Pull stable map failed: ")
            text.startsWith("Pull candidate map failed: ") -> "拉取 Candidate 地图失败: " + text.removePrefix("Pull candidate map failed: ")
            else -> text
        }
    }
}
