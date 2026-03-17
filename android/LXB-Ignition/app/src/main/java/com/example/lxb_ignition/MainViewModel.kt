package com.example.lxb_ignition

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxb_ignition.service.LocalLinkClient
import com.example.lxb_ignition.shizuku.ShizukuManager
import com.lxb.server.cortex.LlmClient
import com.lxb.server.protocol.CommandIds
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"

        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"

        // Local UDP port for trace push from lxb-core.
        private const val TRACE_UDP_PORT = 23456

        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val shizukuManager = ShizukuManager(application)

    // Shizuku / lxb-core state
    private val _state = MutableStateFlow(ShizukuManager.State.UNAVAILABLE)
    val state: StateFlow<ShizukuManager.State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    // Config: lxb-core server
    val lxbPort = MutableStateFlow(prefs.getString(KEY_LXB_PORT, "12345") ?: "12345")

    // Config: PC web_console (kept for compatibility; Android no longer sends tasks by default)
    val serverIp = MutableStateFlow(prefs.getString(KEY_SERVER_IP, "") ?: "")
    val serverPort = MutableStateFlow(prefs.getString(KEY_SERVER_PORT, "5000") ?: "5000")

    // Config: LLM (device-side direct call)
    val llmBaseUrl = MutableStateFlow(prefs.getString(KEY_LLM_BASE_URL, "") ?: "")
    val llmApiKey = MutableStateFlow(prefs.getString(KEY_LLM_API_KEY, "") ?: "")
    val llmModel = MutableStateFlow(prefs.getString(KEY_LLM_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini")
    val llmTestResult = MutableStateFlow("")

    // Control tab
    val requirement = MutableStateFlow("")
    val sendResult = MutableStateFlow("")

    // Chat view: one-shot task session (no cross-task context)
    enum class ChatRole { USER, SYSTEM }

    data class ChatMessage(
        val id: Long,
        val role: ChatRole,
        val text: String,
        val ts: Long = System.currentTimeMillis()
    )

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    private var nextMsgId: Long = 1L
    private var activeTraceJob: Job? = null

    // Tasks tab: recent task list (lightweight snapshot).
    data class TaskSummary(
        val taskId: String,
        val userTask: String,
        val state: String,
        val finalState: String,
        val reason: String,
        val packageName: String,
        val targetPage: String,
        val source: String,
        val scheduleId: String,
        val memoryApplied: Boolean,
        val createdAt: Long,
        val finishedAt: Long
    )

    private val _taskList = MutableStateFlow<List<TaskSummary>>(emptyList())
    val taskList: StateFlow<List<TaskSummary>> = _taskList.asStateFlow()

    data class ScheduleSummary(
        val scheduleId: String,
        val name: String,
        val userTask: String,
        val packageName: String,
        val startPage: String,
        val runAtMs: Long,
        val repeatMode: String,
        val repeatWeekdays: Int,
        val nextRunAt: Long,
        val lastTriggeredAt: Long,
        val triggerCount: Long,
        val enabled: Boolean,
        val createdAt: Long,
        val userPlaybook: String
    )

    private val _scheduleList = MutableStateFlow<List<ScheduleSummary>>(emptyList())
    val scheduleList: StateFlow<List<ScheduleSummary>> = _scheduleList.asStateFlow()

    // Tasks tab: schedule form
    val scheduleName = MutableStateFlow("")
    val scheduleTask = MutableStateFlow("")
    val scheduleStartAtMs = MutableStateFlow((System.currentTimeMillis() + 5 * 60_000L).toString())
    val scheduleRepeatMode = MutableStateFlow(REPEAT_ONCE)
    val scheduleRepeatWeekdays = MutableStateFlow(0b0011111) // Mon-Fri
    val schedulePackage = MutableStateFlow("")
    val scheduleStartPage = MutableStateFlow("")
    val schedulePlaybook = MutableStateFlow("")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        shizukuManager.setListener(object : ShizukuManager.Listener {
            override fun onStateChanged(state: ShizukuManager.State, message: String) {
                _state.value = state
                _statusMessage.value = message
            }

            override fun onLogLine(line: String) {
                appendLog(line)
            }
        })
        shizukuManager.attach()
    }

    // ----- Shizuku / lxb-core operations -----

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun startServer() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendLog("Invalid lxb-core port")
            appendSystemMessage("Invalid lxb-core port, please check UDP port in Config tab.")
            return
        }
        saveConfig()
        viewModelScope.launch {
            shizukuManager.startServer(port)
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            shizukuManager.stopServer()
        }
    }

    // ----- Control tab: send task to PC web_console (legacy) -----

    fun sendRequirement() {
        val req = requirement.value.trim()
        if (req.isEmpty()) {
            sendResult.value = "Please enter a task description first."
            appendSystemMessage("Please enter a task description before sending to PC.")
            return
        }
        val ip = serverIp.value.trim()
        val port = serverPort.value.trim()
        if (ip.isEmpty()) {
            sendResult.value = "Please fill web_console IP in Config tab first."
            appendSystemMessage("web_console IP is missing. Please fill it in Config tab.")
            return
        }
        saveConfig()
        viewModelScope.launch {
            sendResult.value = "Sending to PC..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "http://$ip:$port/api/cortex/fsm/start"
                    val json = org.json.JSONObject()
                        .put("user_task", req)
                        .put("lxb_port", lxbPort.value.toIntOrNull() ?: 12345)
                        .toString()
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(body).build()
                    httpClient.newCall(request).execute().use { resp ->
                        "HTTP ${resp.code}: ${resp.body?.string()?.take(200) ?: "(empty body)"}"
                    }
                }.getOrElse { e -> "Send failed: ${e.message}" }
            }
            sendResult.value = result
            appendLog("[REQ] $result")
            appendSystemMessage("Task sent to PC web_console: $result")
        }
    }

    // ----- Control tab: run Cortex FSM on device -----

    fun runRequirementOnDevice() {
        val req = requirement.value.trim()
        if (req.isEmpty()) {
            sendResult.value = "Please enter a task description first."
            appendSystemMessage("Please enter a task description before running on device.")
            return
        }
        val port = lxbPort.value.toIntOrNull() ?: run {
            sendResult.value = "Invalid lxb-core port"
            appendSystemMessage("Invalid lxb-core port, please check UDP port in Config tab.")
            return
        }
        saveConfig()

        viewModelScope.launch {
            appendUserMessage(req)
            appendSystemMessage("Task received, checking lxb-core server status...")
            sendResult.value = "Submitting task to device..."

            val running = withContext(Dispatchers.IO) {
                runCatching { shizukuManager.isServerRunning() }.getOrDefault(false)
            }
            if (!running) {
                val err = "lxb-core is not running, please start the service on the home tab."
                sendResult.value = err
                appendLog("[FSM] $err")
                appendSystemMessage("Server is not running, please start the service first.")
                return@launch
            }

            appendSystemMessage("Server is running, calling Cortex FSM on device...")

            // Ensure UDP trace listener is running so that chat shows live FSM progress.
            ensureTraceUdpListener()

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LocalLinkClient("127.0.0.1", port).use { client ->
                        // Best-effort handshake, do not fail the whole flow if it crashes.
                        runCatching { client.handshake() }

                        val json = org.json.JSONObject()
                            .put("user_task", req)
                            .put("trace_mode", "push")
                            .put("trace_udp_port", TRACE_UDP_PORT)
                            .toString()
                        val payload = json.toByteArray(Charsets.UTF_8)

                        val respBytes = client.sendCommand(
                            CommandIds.CMD_CORTEX_FSM_RUN,
                            payload,
                            timeoutMs = 10_000
                        )
                        val text = respBytes.toString(Charsets.UTF_8)
                        val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                        if (obj == null) {
                            Pair("Invalid response: ${text.take(160)}", "")
                        } else {
                            val ok = obj.optBoolean("ok", false)
                            val status = obj.optString("status", "")
                            val taskId = obj.optString("task_id", "")
                            if (ok && status == "submitted" && taskId.isNotEmpty()) {
                                Pair("Task submitted: $taskId", taskId)
                            } else {
                                Pair("Task submission failed: ${text.take(200)}", "")
                            }
                        }
                    }
                }.getOrElse { e ->
                    Pair("Task submission failed: ${e.message ?: e.toString()}", "")
                }
            }

            val msg = result.first
            val taskId = result.second

            sendResult.value = msg
            appendLog("[FSM] $msg")
            appendSystemMessage(msg)

            if (taskId.isNotEmpty()) {
                appendSystemMessage("Task id: $taskId")
            }
        }
    }

    fun cancelCurrentTaskOnDevice() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot cancel task.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    client.sendCommand(
                        CommandIds.CMD_CORTEX_FSM_CANCEL,
                        ByteArray(0),
                        timeoutMs = 2_000
                    )
                }
            }.onSuccess {
                appendLog("[FSM] Cancel requested.")
                withContext(Dispatchers.Main) {
                    appendSystemMessage("Cancel requested for current task.")
                }
            }.onFailure { e ->
                appendLog("[FSM] Cancel request failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    appendSystemMessage("Failed to send cancel request: ${e.message}")
                }
            }
        }
    }

    fun refreshTaskListOnDevice() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot refresh task list.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    val payload = org.json.JSONObject()
                        .put("limit", 50)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_LIST,
                        payload,
                        timeoutMs = 5_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                        ?: return@runCatching Pair("Invalid task list response: ${text.take(160)}", emptyList<TaskSummary>())
                    if (!obj.optBoolean("ok", false)) {
                        return@runCatching Pair("Task list query failed: ${text.take(160)}", emptyList<TaskSummary>())
                    }
                    val arr = obj.optJSONArray("tasks") ?: org.json.JSONArray()
                    val dedup = linkedMapOf<String, TaskSummary>()
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val id = t.optString("task_id", "")
                        if (id.isEmpty()) continue
                        val summary = TaskSummary(
                            taskId = id,
                            userTask = t.optString("user_task", ""),
                            state = t.optString("state", ""),
                            finalState = t.optString("final_state", ""),
                            reason = t.optString("reason", ""),
                            packageName = t.optString("package_name", ""),
                            targetPage = t.optString("target_page", ""),
                            source = t.optString("source", ""),
                            scheduleId = t.optString("schedule_id", ""),
                            memoryApplied = t.optBoolean("memory_applied", false),
                            createdAt = t.optLong("created_at", 0L),
                            finishedAt = t.optLong("finished_at", 0L)
                        )
                        dedup[id] = summary
                    }
                    val items = dedup.values
                        .sortedByDescending { if (it.createdAt > 0L) it.createdAt else it.finishedAt }
                    Pair("Task list refreshed: ${items.size} items.", items as List<TaskSummary>)
                }
            }.getOrElse { e -> Pair("Task list query failed: ${e.message}", emptyList<TaskSummary>()) }

            withContext(Dispatchers.Main) {
                _taskList.value = result.second
                appendLog("[FSM] ${result.first}")
                appendSystemMessage(result.first)
            }
        }
    }

    fun refreshScheduleListOnDevice() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot refresh schedule list.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    val payload = org.json.JSONObject()
                        .put("limit", 100)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_LIST,
                        payload,
                        timeoutMs = 5_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                        ?: return@runCatching Pair("Invalid schedule list response: ${text.take(160)}", emptyList<ScheduleSummary>())
                    if (!obj.optBoolean("ok", false)) {
                        return@runCatching Pair("Schedule list query failed: ${text.take(160)}", emptyList<ScheduleSummary>())
                    }
                    val arr = obj.optJSONArray("schedules") ?: org.json.JSONArray()
                    val dedup = linkedMapOf<String, ScheduleSummary>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val id = s.optString("schedule_id", "")
                        if (id.isEmpty()) continue
                        val summary = ScheduleSummary(
                            scheduleId = id,
                            name = s.optString("name", ""),
                            userTask = s.optString("user_task", ""),
                            packageName = s.optString("package", ""),
                            startPage = s.optString("start_page", ""),
                            runAtMs = s.optLong("run_at", 0L),
                            repeatMode = s.optString(
                                "repeat_mode",
                                if (s.optBoolean("repeat_daily", false)) REPEAT_DAILY else REPEAT_ONCE
                            ),
                            repeatWeekdays = s.optInt("repeat_weekdays", 0),
                            nextRunAt = s.optLong("next_run_at", 0L),
                            lastTriggeredAt = s.optLong("last_triggered_at", 0L),
                            triggerCount = s.optLong("trigger_count", 0L),
                            enabled = s.optBoolean("enabled", true),
                            createdAt = s.optLong("created_at", 0L),
                            userPlaybook = s.optString("user_playbook", "")
                        )
                        dedup[id] = summary
                    }
                    val items = dedup.values.sortedByDescending { it.nextRunAt }
                    Pair("Schedule list refreshed: ${items.size} items.", items as List<ScheduleSummary>)
                }
            }.getOrElse { e -> Pair("Schedule list query failed: ${e.message}", emptyList<ScheduleSummary>()) }

            withContext(Dispatchers.Main) {
                _scheduleList.value = result.second
                appendLog("[SCHEDULE] ${result.first}")
                appendSystemMessage(result.first)
            }
        }
    }

    fun addScheduleOnDevice() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot add schedule.")
            return
        }
        val task = scheduleTask.value.trim()
        if (task.isEmpty()) {
            appendSystemMessage("Schedule task cannot be empty.")
            return
        }
        val runAt = scheduleStartAtMs.value.trim().toLongOrNull()
        if (runAt == null || runAt <= 0L) {
            appendSystemMessage("Please pick a valid date and time.")
            return
        }
        val repeatMode = scheduleRepeatMode.value.trim().lowercase()
        val repeatWeekdays = scheduleRepeatWeekdays.value
        if (repeatMode == REPEAT_ONCE && runAt <= System.currentTimeMillis()) {
            appendSystemMessage("run_at must be in the future.")
            return
        }
        if (repeatMode == REPEAT_WEEKLY && (repeatWeekdays and 0x7F) == 0) {
            appendSystemMessage("Please select at least one weekday for weekly repeat.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    val payloadJson = org.json.JSONObject()
                        .put("name", scheduleName.value.trim())
                        .put("user_task", task)
                        .put("package", schedulePackage.value.trim())
                        .put("start_page", scheduleStartPage.value.trim())
                        .put("trace_mode", "push")
                        .put("trace_udp_port", TRACE_UDP_PORT)
                        .put("run_at", runAt)
                        .put("repeat_mode", repeatMode)
                        .put("repeat_weekdays", repeatWeekdays and 0x7F)
                        .put("repeat_daily", repeatMode == REPEAT_DAILY) // backward compatibility
                        .put("user_playbook", schedulePlaybook.value.trim())
                        .toString()
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_ADD,
                        payloadJson.toByteArray(Charsets.UTF_8),
                        timeoutMs = 6_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj == null || !obj.optBoolean("ok", false)) {
                        "Add schedule failed: ${text.take(220)}"
                    } else {
                        val scheduleObj = obj.optJSONObject("schedule")
                        val sid = scheduleObj?.optString("schedule_id", "") ?: ""
                        if (sid.isNotEmpty()) {
                            "Schedule added: $sid"
                        } else {
                            "Schedule added."
                        }
                    }
                }
            }.getOrElse { e -> "Add schedule failed: ${e.message}" }

            withContext(Dispatchers.Main) {
                appendLog("[SCHEDULE] $result")
                appendSystemMessage(result)
                if (result.startsWith("Schedule added")) {
                    refreshScheduleListOnDevice()
                }
            }
        }
    }

    fun updateScheduleOnDevice(scheduleId: String) {
        val sid = scheduleId.trim()
        if (sid.isEmpty()) {
            appendSystemMessage("schedule_id is empty.")
            return
        }
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot update schedule.")
            return
        }
        val task = scheduleTask.value.trim()
        if (task.isEmpty()) {
            appendSystemMessage("Schedule task cannot be empty.")
            return
        }
        val runAt = scheduleStartAtMs.value.trim().toLongOrNull()
        if (runAt == null || runAt <= 0L) {
            appendSystemMessage("Please pick a valid date and time.")
            return
        }
        val repeatMode = scheduleRepeatMode.value.trim().lowercase()
        val repeatWeekdays = scheduleRepeatWeekdays.value
        if (repeatMode == REPEAT_ONCE && runAt <= System.currentTimeMillis()) {
            appendSystemMessage("run_at must be in the future.")
            return
        }
        if (repeatMode == REPEAT_WEEKLY && (repeatWeekdays and 0x7F) == 0) {
            appendSystemMessage("Please select at least one weekday for weekly repeat.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    val payloadJson = org.json.JSONObject()
                        .put("schedule_id", sid)
                        .put("name", scheduleName.value.trim())
                        .put("user_task", task)
                        .put("package", schedulePackage.value.trim())
                        .put("start_page", scheduleStartPage.value.trim())
                        .put("trace_mode", "push")
                        .put("trace_udp_port", TRACE_UDP_PORT)
                        .put("run_at", runAt)
                        .put("repeat_mode", repeatMode)
                        .put("repeat_weekdays", repeatWeekdays and 0x7F)
                        .put("repeat_daily", repeatMode == REPEAT_DAILY) // backward compatibility
                        .put("user_playbook", schedulePlaybook.value.trim())
                        .toString()
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_UPDATE,
                        payloadJson.toByteArray(Charsets.UTF_8),
                        timeoutMs = 6_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj == null || !obj.optBoolean("ok", false)) {
                        "Update schedule failed: ${text.take(220)}"
                    } else {
                        "Schedule updated: $sid"
                    }
                }
            }.getOrElse { e -> "Update schedule failed: ${e.message}" }

            withContext(Dispatchers.Main) {
                appendLog("[SCHEDULE] $result")
                appendSystemMessage(result)
                if (result.startsWith("Schedule updated")) {
                    refreshScheduleListOnDevice()
                }
            }
        }
    }

    fun removeScheduleOnDevice(scheduleId: String) {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot remove schedule.")
            return
        }
        if (scheduleId.isBlank()) {
            appendSystemMessage("schedule_id is empty.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                LocalLinkClient("127.0.0.1", port).use { client ->
                    runCatching { client.handshake() }
                    val payloadJson = org.json.JSONObject()
                        .put("schedule_id", scheduleId)
                        .toString()
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_REMOVE,
                        payloadJson.toByteArray(Charsets.UTF_8),
                        timeoutMs = 4_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj == null || !obj.optBoolean("ok", false)) {
                        "Remove schedule failed: ${text.take(220)}"
                    } else if (obj.optBoolean("removed", false)) {
                        "Schedule removed: $scheduleId"
                    } else {
                        "Schedule not found: $scheduleId"
                    }
                }
            }.getOrElse { e -> "Remove schedule failed: ${e.message}" }

            withContext(Dispatchers.Main) {
                appendLog("[SCHEDULE] $result")
                appendSystemMessage(result)
                refreshScheduleListOnDevice()
            }
        }
    }

    fun loadScheduleForm(schedule: ScheduleSummary) {
        scheduleName.value = schedule.name
        scheduleTask.value = schedule.userTask
        scheduleStartAtMs.value = schedule.runAtMs.toString()
        scheduleRepeatMode.value = schedule.repeatMode.ifBlank { REPEAT_ONCE }
        scheduleRepeatWeekdays.value = schedule.repeatWeekdays
        schedulePackage.value = schedule.packageName
        scheduleStartPage.value = schedule.startPage
        schedulePlaybook.value = schedule.userPlaybook
    }

    fun resetScheduleForm() {
        scheduleName.value = ""
        scheduleTask.value = ""
        scheduleStartAtMs.value = (System.currentTimeMillis() + 5 * 60_000L).toString()
        scheduleRepeatMode.value = REPEAT_ONCE
        scheduleRepeatWeekdays.value = 0b0011111
        schedulePackage.value = ""
        scheduleStartPage.value = ""
        schedulePlaybook.value = ""
    }

    fun showTaskSummaryInChat(task: TaskSummary) {
        val text = buildString {
            append("Task summary:\n")
            append("id=").append(task.taskId).append("\n")
            if (task.userTask.isNotEmpty()) {
                append("task=").append(task.userTask).append("\n")
            }
            append("state=").append(task.state)
            if (task.finalState.isNotEmpty()) {
                append(" (final=").append(task.finalState).append(")")
            }
            if (task.reason.isNotEmpty()) {
                append(", reason=").append(task.reason)
            }
            append("\n")
            if (task.packageName.isNotEmpty()) {
                append("package=").append(task.packageName).append("\n")
            }
            if (task.targetPage.isNotEmpty()) {
                append("target_page=").append(task.targetPage).append("\n")
            }
            if (task.source.isNotEmpty()) {
                append("source=").append(task.source).append("\n")
            }
            if (task.scheduleId.isNotEmpty()) {
                append("schedule_id=").append(task.scheduleId).append("\n")
            }
            append("memory_applied=").append(task.memoryApplied).append("\n")
        }
        appendSystemMessage(text.trim())
    }

    /**
     * Start a background job that listens for trace events pushed via UDP
     * from lxb-core and maps them to chat messages.
     */
    private fun ensureTraceUdpListener(): Job {
        val existing = activeTraceJob
        if (existing != null && existing.isActive) {
            return existing
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(TRACE_UDP_PORT)
                val buffer = ByteArray(64 * 1024)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull() ?: continue
                    withContext(Dispatchers.Main) {
                        appendChatMessageFromTrace(obj)
                    }
                }
            } catch (e: Exception) {
                appendLog("[TRACE_PUSH] listener error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
        activeTraceJob = job
        return job
    }

    /**
     * Map a single trace event JSON object into a human-readable chat message.
     * Uses seenKeys to avoid spamming duplicate messages for the same logical stage.
     */
    private fun appendChatMessageFromTrace(obj: org.json.JSONObject) {
        val event = obj.optString("event", "")
        if (event.isEmpty()) return

        when (event) {
            "fsm_state_enter" -> {
                val state = obj.optString("state", "")
                when (state) {
                    "APP_RESOLVE" -> appendSystemMessage("APP_RESOLVE: selecting the best app for this task...")
                    "ROUTE_PLAN" -> appendSystemMessage("ROUTE_PLAN: planning navigation route using map...")
                    "ROUTING" -> appendSystemMessage("ROUTING: executing route on device...")
                    "VISION_ACT" -> appendSystemMessage("VISION_ACT: entering vision-action loop (LLM + VLM).")
                    "FINISH" -> appendSystemMessage("Task finished successfully.")
                    "FAIL" -> appendSystemMessage("Task finished with failure.")
                }
            }

            "fsm_init_ready" -> {
                val deviceInfo = obj.optJSONObject("device_info")
                val width = deviceInfo?.optInt("width", 0) ?: 0
                val height = deviceInfo?.optInt("height", 0) ?: 0
                val apps = obj.optInt("app_candidates", 0)
                appendSystemMessage(
                    "Device ready: ${width}x$height, scanned $apps candidate apps."
                )
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
                appendSystemMessage(msg)
            }

            "fsm_sub_task_end" -> {
                val index = obj.optInt("index", -1)
                val status = obj.optString("status", "")
                val label = if (index >= 0) "#${index + 1}" else ""
                appendSystemMessage("Sub-task $label finished: ${if (status.isNotEmpty()) status else "unknown"}.")
            }

            "fsm_sub_task_summary" -> {
                val summary = obj.optString("summary", "")
                if (summary.isNotEmpty()) {
                    appendSystemMessage("Sub-task summary: $summary")
                }
            }

            "llm_prompt_task_decompose" -> {
                appendSystemMessage("Decomposing the request into sub-tasks...")
            }

            "fsm_task_decompose_done" -> {
                val count = obj.optInt("sub_task_count", -1)
                val type = obj.optString("task_type", "")
                val msg = buildString {
                    append("Task decomposition done")
                    if (count >= 0) append(": $count sub-task(s)")
                    if (type.isNotEmpty()) append(", type=$type")
                    append(".")
                }
                appendSystemMessage(msg)
            }

            "fsm_app_resolve_done" -> {
                val pkg = obj.optString("package", "")
                val source = obj.optString("source", "")
                val sourceLabel = when (source) {
                    "llm" -> "via LLM"
                    "fallback" -> "via fallback heuristic"
                    else -> ""
                }
                val base = if (pkg.isNotEmpty()) {
                    "App selected: $pkg"
                } else {
                    "App selected."
                }
                val msg = if (sourceLabel.isNotEmpty()) {
                    "$base ($sourceLabel)."
                } else {
                    base
                }
                appendSystemMessage(msg)
            }

            "fsm_app_resolve_failed" -> {
                val reason = obj.optString("reason", "unknown")
                appendSystemMessage("APP_RESOLVE failed: $reason")
            }

            "fsm_route_plan_no_map" -> {
                appendSystemMessage(
                    "No navigation map found for this app, skipping routing and entering VISION_ACT directly."
                )
            }

            "fsm_route_plan_done" -> {
                val target = obj.optString("target_page", "")
                val usedFallback = obj.optBoolean("used_fallback", false)
                val base = if (target.isNotEmpty()) {
                    "Route plan completed, target page: $target."
                } else {
                    "Route plan completed."
                }
                val msg = if (usedFallback) {
                    "$base (fallback plan used)."
                } else {
                    base
                }
                appendSystemMessage(msg)
            }

            "fsm_route_plan_failed" -> {
                val reason = obj.optString("reason", "unknown")
                appendSystemMessage("Route planning failed: $reason")
            }

            "fsm_routing_done" -> {
                val steps = obj.optInt("steps", -1)
                val mode = obj.optString("mode", "")
                val modeLabel = if (mode.isNotEmpty()) mode else "map"
                val msg = if (steps >= 0) {
                    "Routing finished: mode=$modeLabel, steps=$steps."
                } else {
                    "Routing finished: mode=$modeLabel."
                }
                appendSystemMessage(msg)
            }

            "fsm_routing_failed" -> {
                val reason = obj.optString("reason", "unknown")
                appendSystemMessage("Routing failed: $reason")
            }

            "fsm_cancel_requested" -> {
                appendSystemMessage("Cancel requested, FSM will stop at the next safe point.")
            }

            "fsm_task_cancelled" -> {
                appendSystemMessage("Task cancelled by user.")
            }

            "vision_screenshot_ready" -> {
                appendSystemMessage("Screenshot captured, calling vision model for next action...")
            }

            "llm_prompt_vision_act" -> {
                val attempt = obj.optInt("attempt", -1)
                if (attempt > 0) {
                    appendSystemMessage("Calling LLM + VLM for next step planning... (attempt $attempt/3)")
                } else {
                    appendSystemMessage("Calling LLM + VLM for next step planning...")
                }
            }

            "llm_response_vision_act" -> {
                val attempt = obj.optInt("attempt", -1)
                if (attempt > 0) {
                    appendSystemMessage("Vision model responded. (attempt $attempt/3)")
                } else {
                    appendSystemMessage("Vision model responded with the next action.")
                }
            }

            "vision_parse_retry" -> {
                val attempt = obj.optInt("attempt", -1)
                val maxAttempts = obj.optInt("max_attempts", -1)
                val err = obj.optString("error", "unknown parse error")
                val msg = buildString {
                    append("Vision output parse failed")
                    if (attempt > 0 && maxAttempts > 0) {
                        append(" (attempt $attempt/$maxAttempts)")
                    }
                    append(": $err. Retrying...")
                }
                appendSystemMessage(msg)
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
                if (parts.isNotEmpty()) {
                    appendSystemMessage("Vision plan: ${parts.joinToString(" | ")}")
                }
            }

            "vision_instruction_invalid" -> {
                val err = obj.optString("error", "")
                if (err.isNotEmpty()) {
                    appendSystemMessage("Vision action output was invalid: $err")
                } else {
                    appendSystemMessage("Vision action output was invalid, stopping this task.")
                }
            }

            "vision_action_loop_detected" -> {
                appendSystemMessage("Repeated ineffective actions detected, stopping to avoid loop.")
            }

            "planner_call_failed" -> {
                val err = obj.optString("err", "unknown")
                appendSystemMessage("Planner call failed: $err")
            }

            "exec_action_error" -> {
                val op = obj.optString("op", "")
                appendSystemMessage("Execution error while running action ${if (op.isNotEmpty()) op else "UNKNOWN"}.")
            }

            "exec_tap_start" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x >= 0 && y >= 0) {
                    appendSystemMessage("Action: TAP at ($x, $y).")
                } else {
                    appendSystemMessage("Action: TAP.")
                }
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
                if (dur >= 0) {
                    desc.append(" in ${dur}ms")
                }
                desc.append(".")
                appendSystemMessage(desc.toString())
            }

            "exec_input_start" -> {
                val text = obj.optString("text", "")
                if (text.isNotEmpty()) {
                    appendSystemMessage("Action: INPUT text \"$text\".")
                } else {
                    appendSystemMessage("Action: INPUT text.")
                }
            }

            "exec_wait_start" -> {
                val ms = obj.optInt("ms", -1)
                if (ms > 0) {
                    appendSystemMessage("Action: WAIT for ${ms}ms.")
                } else {
                    appendSystemMessage("Action: WAIT.")
                }
            }

            "exec_back_start" -> {
                appendSystemMessage("Action: BACK key pressed.")
            }
        }
    }

    // ----- LLM config and test -----

    fun testLlmAndSyncConfig() {
        val baseUrl = llmBaseUrl.value.trim()
        val model = llmModel.value.trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            llmTestResult.value = "Please fill LLM API Base URL and Model first."
            return
        }

        saveConfig()

        viewModelScope.launch {
            llmTestResult.value = "Testing LLM..."

            // 1) Write device-side config file (shell readable)
            val cfgJson = org.json.JSONObject()
                .put("api_base_url", baseUrl)
                .put("api_key", llmApiKey.value)
                .put("model", model)
                .toString()
            val cfgBytes = cfgJson.toByteArray(Charset.forName("UTF-8"))
            val llmConfigPath = shizukuManager.getLlmConfigPath()

            val syncResult = withContext(Dispatchers.IO) {
                shizukuManager.writeConfigFile(llmConfigPath, cfgBytes)
            }
            if (syncResult.isFailure) {
                val msg = "Failed to write device config: ${syncResult.exceptionOrNull()?.message}"
                llmTestResult.value = msg
                appendLog("[LLM] $msg")
                return@launch
            }
            appendLog("[LLM] Config synced to $llmConfigPath")

            // 2) Directly call cloud LLM from APK to validate the config
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = org.json.JSONObject()
                        .put("model", model)
                        .put("max_tokens", 16)
                        .put(
                            "messages",
                            org.json.JSONArray().put(
                                org.json.JSONObject()
                                    .put("role", "user")
                                    .put("content", "Please reply with ok only.")
                            )
                        )
                        .toString()
                    val body = json.toRequestBody("application/json".toMediaType())
                    val endpoint = LlmClient.buildEndpointUrl(baseUrl)
                    val builder = Request.Builder().url(endpoint).post(body)
                    val key = llmApiKey.value.trim()
                    if (key.isNotEmpty()) {
                        builder.addHeader("Authorization", "Bearer $key")
                    }
                    val request = builder.build()
                    httpClient.newCall(request).execute().use { resp ->
                        val code = resp.code
                        val text = resp.body?.string() ?: ""
                        if (code !in 200..299) {
                            "HTTP $code: ${text.take(200)}"
                        } else {
                            val ok = text.contains("ok", ignoreCase = true)
                            if (ok) {
                                "LLM call succeeded: response contains ok (HTTP $code)"
                            } else {
                                "LLM call succeeded (HTTP $code), but response does not clearly contain ok: ${text.take(120)}"
                            }
                        }
                    }
                }.getOrElse { e -> "LLM call failed: ${e.message}" }
            }

            llmTestResult.value = result
            appendLog("[LLM] $result")
        }
    }

    fun saveConfig() {
        prefs.edit()
            .putString(KEY_LXB_PORT, lxbPort.value)
            .putString(KEY_SERVER_IP, serverIp.value)
            .putString(KEY_SERVER_PORT, serverPort.value)
            .putString(KEY_LLM_BASE_URL, llmBaseUrl.value)
            .putString(KEY_LLM_API_KEY, llmApiKey.value)
            .putString(KEY_LLM_MODEL, llmModel.value)
            .apply()
    }

    private fun appendLog(line: String) {
        val current = _logLines.value.toMutableList()
        current.add(line)
        if (current.size > 500) {
            current.subList(0, current.size - 500).clear()
        }
        _logLines.value = current
    }

    private fun appendUserMessage(text: String) {
        val msg = ChatMessage(
            id = nextMsgId++,
            role = ChatRole.USER,
            text = text
        )
        val current = _chatMessages.value.toMutableList()
        current.add(msg)
        if (current.size > 100) {
            current.subList(0, current.size - 100).clear()
        }
        _chatMessages.value = current
    }

    private fun appendSystemMessage(text: String) {
        val msg = ChatMessage(
            id = nextMsgId++,
            role = ChatRole.SYSTEM,
            text = text
        )
        val current = _chatMessages.value.toMutableList()
        current.add(msg)
        if (current.size > 100) {
            current.subList(0, current.size - 100).clear()
        }
        _chatMessages.value = current
    }

    override fun onCleared() {
        super.onCleared()
        shizukuManager.detach()
        activeTraceJob?.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }
}
