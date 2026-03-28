package com.example.lxb_ignition.core

import org.json.JSONObject

data class RuntimeUpdate(
    val phase: String,
    val detail: String,
    val stopAfter: Boolean = false
)

data class TraceMapResult(
    val event: String,
    val taskId: String,
    val messages: List<String>,
    val runtimeUpdate: RuntimeUpdate? = null
)

object TraceEventMapper {
    fun map(obj: JSONObject): TraceMapResult? {
        val event = obj.optString("event", "")
        if (event.isEmpty()) return null
        val taskId = obj.optString("task_id", "")
        val messages = mutableListOf<String>()
        var runtime: RuntimeUpdate? = null

        when (event) {
            "fsm_state_enter" -> {
                val state = obj.optString("state", "")
                when (state) {
                    "APP_RESOLVE" -> {
                        messages += "APP_RESOLVE: selecting the best app for this task..."
                        runtime = RuntimeUpdate("APP_RESOLVE", "Selecting app...")
                    }
                    "ROUTE_PLAN" -> {
                        messages += "ROUTE_PLAN: planning navigation route using map..."
                        runtime = RuntimeUpdate("ROUTE_PLAN", "Planning route...")
                    }
                    "ROUTING" -> {
                        messages += "ROUTING: executing route on device..."
                        runtime = RuntimeUpdate("ROUTING", "Executing route...")
                    }
                    "VISION_ACT" -> {
                        messages += "VISION_ACT: entering vision-action loop (LLM + VLM)."
                        runtime = RuntimeUpdate("VISION_ACT", "Running vision-action...")
                    }
                    "FINISH" -> {
                        messages += "Task finished successfully."
                        runtime = RuntimeUpdate("DONE", "Task finished.", stopAfter = true)
                    }
                    "FAIL" -> {
                        messages += "Task finished with failure."
                        runtime = RuntimeUpdate("FAILED", "Task failed.", stopAfter = true)
                    }
                }
            }

            "fsm_init_ready" -> {
                val deviceInfo = obj.optJSONObject("device_info")
                val width = deviceInfo?.optInt("width", 0) ?: 0
                val height = deviceInfo?.optInt("height", 0) ?: 0
                val apps = obj.optInt("app_candidates", 0)
                messages += "Device ready: ${width}x$height, scanned $apps candidate apps."
            }

            "fsm_sub_task_begin" -> {
                val index = obj.optInt("index", -1)
                val mode = obj.optString("mode", "")
                val appHint = obj.optString("app_hint", "")
                val msg = buildString {
                    append("Sub-task ")
                    append(if (index >= 0) "#${index + 1}" else "started")
                    if (mode.isNotEmpty()) append(" ($mode)")
                    if (appHint.isNotEmpty()) append(", app_hint=$appHint")
                    append(".")
                }
                messages += msg
            }

            "fsm_sub_task_end" -> {
                val index = obj.optInt("index", -1)
                val status = obj.optString("status", "")
                val label = if (index >= 0) "#${index + 1}" else ""
                messages += "Sub-task $label finished: ${if (status.isNotEmpty()) status else "unknown"}."
            }

            "fsm_sub_task_summary" -> {
                val summary = obj.optString("summary", "")
                if (summary.isNotEmpty()) messages += "Sub-task summary: $summary"
            }

            "llm_prompt_task_decompose" -> messages += "Decomposing the request into sub-tasks..."

            "fsm_task_decompose_done" -> {
                val count = obj.optInt("sub_task_count", -1)
                val type = obj.optString("task_type", "")
                val msg = buildString {
                    append("Task decomposition done")
                    if (count >= 0) append(": $count sub-task(s)")
                    if (type.isNotEmpty()) append(", type=$type")
                    append(".")
                }
                messages += msg
            }

            "fsm_app_resolve_done" -> {
                val pkg = obj.optString("package", "")
                val source = obj.optString("source", "")
                val sourceLabel = when (source) {
                    "llm" -> "via LLM"
                    "fallback" -> "via fallback heuristic"
                    else -> ""
                }
                val base = if (pkg.isNotEmpty()) "App selected: $pkg" else "App selected."
                messages += if (sourceLabel.isNotEmpty()) "$base ($sourceLabel)." else base
            }

            "fsm_app_resolve_failed" -> {
                val reason = obj.optString("reason", "unknown")
                messages += "APP_RESOLVE failed: $reason"
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "fsm_route_plan_no_map" -> {
                messages += "No navigation map found for this app, skipping routing and entering VISION_ACT directly."
            }

            "fsm_route_plan_done" -> {
                val target = obj.optString("target_page", "")
                val usedFallback = obj.optBoolean("used_fallback", false)
                val base = if (target.isNotEmpty()) "Route plan completed, target page: $target." else "Route plan completed."
                messages += if (usedFallback) "$base (fallback plan used)." else base
            }

            "fsm_route_plan_failed" -> {
                val reason = obj.optString("reason", "unknown")
                messages += "Route planning failed: $reason"
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "fsm_routing_done" -> {
                val steps = obj.optInt("steps", -1)
                val mode = obj.optString("mode", "")
                val modeLabel = if (mode.isNotEmpty()) mode else "map"
                messages += if (steps >= 0) "Routing finished: mode=$modeLabel, steps=$steps." else "Routing finished: mode=$modeLabel."
            }

            "fsm_routing_failed" -> {
                val reason = obj.optString("reason", "unknown")
                messages += "Routing failed: $reason"
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "fsm_cancel_requested" -> messages += "Cancel requested, FSM will stop at the next safe point."
            "fsm_task_cancelled" -> {
                messages += "Task cancelled by user."
                runtime = RuntimeUpdate("CANCELLED", "Cancelled by user.", stopAfter = true)
            }

            "vision_screenshot_ready" -> {
                messages += "Screenshot captured, calling vision model for next action..."
                runtime = RuntimeUpdate("VISION_CAPTURE", "Screenshot captured.")
            }
            "llm_prompt_vision_act" -> {
                val attempt = obj.optInt("attempt", -1)
                messages += if (attempt > 0) {
                    "Calling LLM + VLM for next step planning... (attempt $attempt/3)"
                } else {
                    "Calling LLM + VLM for next step planning..."
                }
                runtime = RuntimeUpdate("VISION_LLM", "Calling LLM/VLM...")
            }
            "llm_response_vision_act" -> {
                val attempt = obj.optInt("attempt", -1)
                messages += if (attempt > 0) {
                    "Vision model responded. (attempt $attempt/3)"
                } else {
                    "Vision model responded with the next action."
                }
                runtime = RuntimeUpdate("VISION_PARSE", "Model responded.")
            }
            "vision_settle_begin" -> runtime = RuntimeUpdate("SETTLING", "Waiting UI stable...")
            "vision_settle_ready" -> runtime = RuntimeUpdate("VISION_ACT", "UI stable.")
            "vision_settle_timeout" -> runtime = RuntimeUpdate("SETTLING", "UI settle timeout, continue.")
            "vision_settle_fallback" -> runtime = RuntimeUpdate("SETTLING", "Dump unavailable, fallback wait.")

            "vision_parse_retry" -> {
                val attempt = obj.optInt("attempt", -1)
                val maxAttempts = obj.optInt("max_attempts", -1)
                val err = obj.optString("error", "unknown parse error")
                val msg = buildString {
                    append("Vision output parse failed")
                    if (attempt > 0 && maxAttempts > 0) append(" (attempt $attempt/$maxAttempts)")
                    append(": $err. Retrying...")
                }
                messages += msg
            }

            "llm_structured_vision_act" -> {
                val data = obj.optJSONObject("data")
                val action = data?.optString("action", "") ?: ""
                val expected = data?.optString("expected", "") ?: ""
                val command = obj.optString("command", "")
                val parts = mutableListOf<String>()
                if (action.isNotEmpty()) parts.add("action=$action")
                if (expected.isNotEmpty()) parts.add("expected=$expected")
                if (command.isNotEmpty()) parts.add("command=$command")
                if (parts.isNotEmpty()) messages += "Vision plan: ${parts.joinToString(" | ")}"
            }

            "vision_instruction_invalid" -> {
                val err = obj.optString("error", "")
                messages += if (err.isNotEmpty()) {
                    "Vision action output was invalid: $err"
                } else {
                    "Vision action output was invalid, stopping this task."
                }
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "vision_action_loop_detected" -> {
                messages += "Repeated ineffective actions detected, stopping to avoid loop."
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "planner_call_failed" -> {
                val err = obj.optString("err", "unknown")
                messages += "Planner call failed: $err"
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "exec_action_error" -> {
                val op = obj.optString("op", "")
                messages += "Execution error while running action ${if (op.isNotEmpty()) op else "UNKNOWN"}."
                runtime = RuntimeUpdate("FAILED", "Execution failed.", stopAfter = true)
            }

            "exec_tap_start" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                messages += if (x >= 0 && y >= 0) "Action: TAP at ($x, $y)." else "Action: TAP."
            }
            "exec_swipe_start" -> {
                val x1 = obj.optInt("x1", -1)
                val y1 = obj.optInt("y1", -1)
                val x2 = obj.optInt("x2", -1)
                val y2 = obj.optInt("y2", -1)
                val dur = obj.optInt("duration", -1)
                val desc = StringBuilder("Action: SWIPE")
                if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                    desc.append(" from ($x1, $y1) to ($x2, $y2)")
                }
                if (dur >= 0) desc.append(" in ${dur}ms")
                desc.append(".")
                messages += desc.toString()
            }
            "exec_input_start" -> {
                val text = obj.optString("text", "")
                messages += if (text.isNotEmpty()) "Action: INPUT text \"$text\"." else "Action: INPUT text."
            }
            "exec_wait_start" -> {
                val ms = obj.optInt("ms", -1)
                messages += if (ms > 0) "Action: WAIT for ${ms}ms." else "Action: WAIT."
            }
            "exec_back_start" -> messages += "Action: BACK key pressed."
        }
        return TraceMapResult(
            event = event,
            taskId = taskId,
            messages = messages,
            runtimeUpdate = runtime
        )
    }
}
