package com.example.lxb_ignition

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxb_ignition.map.MapSyncManager
import com.example.lxb_ignition.service.LocalLinkClient
import com.example.lxb_ignition.service.TaskRuntimeService
import com.example.lxb_ignition.service.WirelessAdbBootstrapService
import com.example.lxb_ignition.storage.AppStatePaths
import com.lxb.server.cortex.LlmClient
import com.lxb.server.protocol.CommandIds
import java.net.ServerSocket
import java.net.SocketTimeoutException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val DEFAULT_LXB_PORT = "12345"

        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_AUTO_UNLOCK_BEFORE_ROUTE = "auto_unlock_before_route"
        private const val KEY_AUTO_LOCK_AFTER_TASK = "auto_lock_after_task"
        private const val KEY_UNLOCK_PIN = "unlock_pin"
        private const val KEY_USE_MAP = "use_map"
        private const val KEY_MAP_REPO_RAW_BASE_URL = "map_repo_raw_base_url"
        private const val KEY_MAP_SOURCE = "map_source"
        // Legacy key migration only (v0.4.0 and earlier).
        private const val KEY_MAP_DEBUG_LOCAL_OVERRIDE = "map_debug_local_override"
        private const val KEY_UI_LANG = "ui_lang"
        private const val DEFAULT_MAP_REPO_RAW_BASE_URL = "https://raw.githubusercontent.com/wuwei-crg/LXB-MapRepo/main"
        private const val RELEASE_API_LATEST = "https://api.github.com/repos/wuwei-crg/LXB-Framework/releases/latest"
        private const val RELEASE_WEB_LATEST = "https://github.com/wuwei-crg/LXB-Framework/releases/latest"
        private const val DEFAULT_LLM_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json"

        // Local TCP port for trace push from lxb-core.
        private const val TRACE_PUSH_PORT = 23456

        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"

        private fun normalizePortString(raw: String?): String {
            val p = raw?.trim()?.toIntOrNull() ?: return DEFAULT_LXB_PORT
            return if (p in 1..65535) p.toString() else DEFAULT_LXB_PORT
        }

        private fun normalizeUiLang(raw: String?): String {
            val v = raw?.trim()?.lowercase() ?: "en"
            return if (v == "zh") "zh" else "en"
        }

        private fun normalizeMapSource(raw: String?): String {
            val v = raw?.trim()?.lowercase() ?: "stable"
            return when (v) {
                "stable" -> "stable"
                "candidate", "candidates" -> "candidate"
                "burn" -> "burn"
                else -> "stable"
            }
        }
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    // Config: lxb-core server
    val lxbPort = MutableStateFlow(
        normalizePortString(prefs.getString(KEY_LXB_PORT, DEFAULT_LXB_PORT))
    )

    // Config: PC web_console (kept for compatibility; Android no longer sends tasks by default)
    val serverIp = MutableStateFlow(prefs.getString(KEY_SERVER_IP, "") ?: "")
    val serverPort = MutableStateFlow(prefs.getString(KEY_SERVER_PORT, "5000") ?: "5000")

    // Config: LLM (device-side direct call)
    val llmBaseUrl = MutableStateFlow(prefs.getString(KEY_LLM_BASE_URL, "") ?: "")
    val llmApiKey = MutableStateFlow(prefs.getString(KEY_LLM_API_KEY, "") ?: "")
    val llmModel = MutableStateFlow(prefs.getString(KEY_LLM_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini")
    val autoUnlockBeforeRoute = MutableStateFlow(prefs.getBoolean(KEY_AUTO_UNLOCK_BEFORE_ROUTE, true))
    val autoLockAfterTask = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LOCK_AFTER_TASK, true))
    val unlockPin = MutableStateFlow(prefs.getString(KEY_UNLOCK_PIN, "") ?: "")
    val useMap = MutableStateFlow(prefs.getBoolean(KEY_USE_MAP, true))
    val mapRepoRawBaseUrl = MutableStateFlow(
        prefs.getString(KEY_MAP_REPO_RAW_BASE_URL, DEFAULT_MAP_REPO_RAW_BASE_URL)
            ?: DEFAULT_MAP_REPO_RAW_BASE_URL
    )
    private fun loadInitialMapSource(): String {
        val saved = prefs.getString(KEY_MAP_SOURCE, null)
        if (!saved.isNullOrBlank()) {
            return normalizeMapSource(saved)
        }
        val legacyDebug = prefs.getBoolean(KEY_MAP_DEBUG_LOCAL_OVERRIDE, false)
        return if (legacyDebug) "candidate" else "stable"
    }
    val mapSource = MutableStateFlow(loadInitialMapSource())
    val mapTargetPackage = MutableStateFlow("")
    val mapTargetId = MutableStateFlow("")
    val llmTestResult = MutableStateFlow("")
    val mapSyncResult = MutableStateFlow("")
    val appUpdateResult = MutableStateFlow("")
    val uiLang = MutableStateFlow(normalizeUiLang(prefs.getString(KEY_UI_LANG, "en")))

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
    private var activeRuntimeTaskId: String = ""
    private var coreProbeJob: Job? = null

    data class CoreRuntimeStatus(
        val ready: Boolean = false,
        val detail: String = "Not connected"
    )

    private val _coreRuntimeStatus = MutableStateFlow(CoreRuntimeStatus())
    val coreRuntimeStatus: StateFlow<CoreRuntimeStatus> = _coreRuntimeStatus.asStateFlow()

    data class WirelessBootstrapStatus(
        val running: Boolean = false,
        val state: String = "IDLE",
        val message: String = "Idle"
    )

    private val _wirelessBootstrapStatus = MutableStateFlow(WirelessBootstrapStatus())
    val wirelessBootstrapStatus: StateFlow<WirelessBootstrapStatus> = _wirelessBootstrapStatus.asStateFlow()

    // Tasks tab: recent task list (lightweight snapshot).
    data class TaskSummary(
        val taskId: String,
        val userTask: String,
        val state: String,
        val finalState: String,
        val reason: String,
        val taskSummary: String,
        val packageName: String,
        val targetPage: String,
        val source: String,
        val scheduleId: String,
        val memoryApplied: Boolean,
        val recordEnabled: Boolean,
        val recordFile: String,
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
        val recordEnabled: Boolean,
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
    val schedulePlaybook = MutableStateFlow("")
    val scheduleRecordEnabled = MutableStateFlow(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mapSyncManager = MapSyncManager(application, httpClient)

    private val wirelessBootstrapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WirelessAdbBootstrapService.ACTION_STATUS) return
            val state = intent.getStringExtra(WirelessAdbBootstrapService.EXTRA_STATE).orEmpty()
            val message = intent.getStringExtra(WirelessAdbBootstrapService.EXTRA_MESSAGE).orEmpty()
            val running = intent.getBooleanExtra(WirelessAdbBootstrapService.EXTRA_RUNNING, false)
            _wirelessBootstrapStatus.value = WirelessBootstrapStatus(
                running = running,
                state = if (state.isNotBlank()) state else _wirelessBootstrapStatus.value.state,
                message = if (message.isNotBlank()) message else _wirelessBootstrapStatus.value.message
            )
        }
    }

    init {
        registerWirelessBootstrapReceiver()
        startCoreProbeLoop()
        // Migrate stale/invalid port values (e.g., "0") to default.
        persistNormalizedLxbPortIfNeeded()
        // Startup map sync:
        // - always sync stable lane
        // - runtime map source follows selected source (stable/candidate/burn)
        viewModelScope.launch(Dispatchers.IO) {
            val msg = mapSyncManager.startupSyncStable(
                rawBaseUrl = mapRepoRawBaseUrl.value.trim(),
                selectedSourceRaw = mapSource.value
            ).getOrElse { "startup stable sync skipped: ${it.message}" }
            appendLog("[MAP] $msg")
        }
    }

    // ----- lxb-core operations -----

    fun startServerWithNative() {
        val port = currentLxbPortOrNull() ?: run {
            appendLog("Invalid lxb-core port")
            appendSystemMessage("Invalid lxb-core port, please check TCP port in Config tab.")
            return
        }
        saveConfig()
        appendLog("[CORE] Native start requested on port $port")
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_START_CORE_NATIVE)
    }

    fun stopServerProcess() {
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_STOP_CORE_NATIVE)
    }

    // Backward-compatible aliases used by old UI call sites.
    fun startServer() = startServerWithNative()
    fun stopServer() = stopServerProcess()

    fun refreshCoreRuntimeStatusNow() {
        viewModelScope.launch(Dispatchers.IO) {
            publishCoreRuntimeStatus(probeCoreHandshakeReady(1500))
        }
    }

    fun startWirelessBootstrapGuide() {
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_START_GUIDE)
        _wirelessBootstrapStatus.value = _wirelessBootstrapStatus.value.copy(
            running = true,
            state = "GUIDE_SETTINGS",
            message = "Opening Developer Options and starting guide..."
        )
    }

    private fun sendWirelessBootstrapAction(action: String) {
        val app = getApplication<Application>()
        runCatching {
            val intent = Intent(app, WirelessAdbBootstrapService::class.java).apply {
                this.action = action
            }
            if (action == WirelessAdbBootstrapService.ACTION_STOP
                || action == WirelessAdbBootstrapService.ACTION_STOP_CORE_NATIVE
            ) {
                app.startService(intent)
            } else {
                ContextCompat.startForegroundService(app, intent)
            }
        }.onFailure { e ->
            val msg = "Wireless bootstrap action failed: ${e.message}"
            appendLog("[WIRELESS_BOOTSTRAP] $msg")
            _wirelessBootstrapStatus.value = _wirelessBootstrapStatus.value.copy(
                state = "FAILED",
                message = msg
            )
        }
    }

    private fun startCoreProbeLoop() {
        if (coreProbeJob?.isActive == true) return
        coreProbeJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val ready = probeCoreHandshakeReady(1200)
                publishCoreRuntimeStatus(ready)
                delay(2000L)
            }
        }
    }

    private fun probeCoreHandshakeReady(timeoutMs: Int): Boolean {
        val port = currentLxbPortOrNull() ?: return false
        return runCatching {
            LocalLinkClient("127.0.0.1", port, timeoutMs).use { client ->
                client.handshake(timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    private fun publishCoreRuntimeStatus(ready: Boolean) {
        val port = currentLxbPortOrNull() ?: 12345
        _coreRuntimeStatus.value = if (ready) {
            CoreRuntimeStatus(
                ready = true,
                detail = "Connected (127.0.0.1:$port, handshake ok)"
            )
        } else {
            CoreRuntimeStatus(
                ready = false,
                detail = "Disconnected (127.0.0.1:$port)"
            )
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
                            .put("lxb_port", currentLxbPortOrDefault())
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
        val port = currentLxbPortOrNull() ?: run {
            sendResult.value = "Invalid lxb-core port"
            appendSystemMessage("Invalid lxb-core port, please check TCP port in Config tab.")
            return
        }
        saveConfig()

        viewModelScope.launch {
            appendUserMessage(req)
            appendSystemMessage("Task received, checking lxb-core server status...")
            sendResult.value = "Submitting task to device..."
            activeRuntimeTaskId = ""
            updateTaskRuntimeIndicator("SUBMITTING", "Submitting task to device...")

            val running = withContext(Dispatchers.IO) { probeCoreHandshakeReady(1500) }
            if (!running) {
                val err = "lxb-core is not running, please start the service on the home tab."
                sendResult.value = err
                appendLog("[FSM] $err")
                appendSystemMessage("Server is not running, please start the service first.")
                stopTaskRuntimeIndicator()
                return@launch
            }

            appendSystemMessage("Server is running, calling Cortex FSM on device...")
            val sync = withContext(Dispatchers.IO) { syncDeviceLlmConfigFile() }
            if (sync.isFailure) {
                val err = "Failed to sync runtime config: ${sync.exceptionOrNull()?.message}"
                sendResult.value = err
                appendLog("[FSM] $err")
                appendSystemMessage(err)
                stopTaskRuntimeIndicator()
                return@launch
            }

            // Ensure trace listener is running so that chat shows live FSM progress.
            ensureTraceUdpListener()

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LocalLinkClient("127.0.0.1", port).use { client ->
                        // Best-effort handshake, do not fail the whole flow if it crashes.
                        runCatching { client.handshake() }

                        val json = org.json.JSONObject()
                            .put("user_task", req)
                            .put("trace_mode", "push")
                            .put("trace_udp_port", TRACE_PUSH_PORT)
                            .put("use_map", useMap.value)
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
                startTaskRuntimeIndicator(taskId, "RUNNING", "Task submitted, waiting for trace events...")
            } else {
                stopTaskRuntimeIndicator()
            }
        }
    }

    fun cancelCurrentTaskOnDevice() {
        val port = currentLxbPortOrNull() ?: run {
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
                    updateTaskRuntimeIndicator("CANCELLING", "Cancel request sent.")
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
        val port = currentLxbPortOrNull() ?: run {
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
                            taskSummary = t.optString("task_summary", ""),
                            packageName = t.optString("package_name", ""),
                            targetPage = t.optString("target_page", ""),
                            source = t.optString("source", ""),
                            scheduleId = t.optString("schedule_id", ""),
                            memoryApplied = t.optBoolean("memory_applied", false),
                            recordEnabled = t.optBoolean("record_enabled", false),
                            recordFile = t.optString("record_file", ""),
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
        val port = currentLxbPortOrNull() ?: run {
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
                            recordEnabled = s.optBoolean("record_enabled", false),
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
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot add schedule.")
            return
        }
        val name = scheduleName.value.trim()
        val task = scheduleTask.value.trim()
        val packageName = schedulePackage.value.trim()
        val playbook = schedulePlaybook.value.trim()
        val recordEnabled = scheduleRecordEnabled.value
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
                        .put("name", name)
                        .put("user_task", task)
                        .put("package", packageName)
                        .put("trace_mode", "push")
                        .put("trace_udp_port", TRACE_PUSH_PORT)
                        .put("run_at", runAt)
                        .put("repeat_mode", repeatMode)
                        .put("repeat_weekdays", repeatWeekdays and 0x7F)
                        .put("repeat_daily", repeatMode == REPEAT_DAILY) // backward compatibility
                        .put("user_playbook", playbook)
                        .put("record_enabled", recordEnabled)
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
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot update schedule.")
            return
        }
        val name = scheduleName.value.trim()
        val task = scheduleTask.value.trim()
        val packageName = schedulePackage.value.trim()
        val playbook = schedulePlaybook.value.trim()
        val recordEnabled = scheduleRecordEnabled.value
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
                        .put("name", name)
                        .put("user_task", task)
                        .put("package", packageName)
                        .put("trace_mode", "push")
                        .put("trace_udp_port", TRACE_PUSH_PORT)
                        .put("run_at", runAt)
                        .put("repeat_mode", repeatMode)
                        .put("repeat_weekdays", repeatWeekdays and 0x7F)
                        .put("repeat_daily", repeatMode == REPEAT_DAILY) // backward compatibility
                        .put("user_playbook", playbook)
                        .put("record_enabled", recordEnabled)
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
        val port = currentLxbPortOrNull() ?: run {
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
        schedulePlaybook.value = schedule.userPlaybook
        scheduleRecordEnabled.value = schedule.recordEnabled
    }

    fun resetScheduleForm() {
        scheduleName.value = ""
        scheduleTask.value = ""
        scheduleStartAtMs.value = (System.currentTimeMillis() + 5 * 60_000L).toString()
        scheduleRepeatMode.value = REPEAT_ONCE
        scheduleRepeatWeekdays.value = 0b0011111
        schedulePackage.value = ""
        schedulePlaybook.value = ""
        scheduleRecordEnabled.value = false
    }

    /**
     * Start a background job that listens for trace events pushed via TCP
     * from lxb-core and maps them to chat messages.
     */
    private fun ensureTraceUdpListener(): Job {
        val existing = activeTraceJob
        if (existing != null && existing.isActive) {
            return existing
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(TRACE_PUSH_PORT).apply {
                    reuseAddress = true
                    soTimeout = 1000
                }
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = client.use {
                        runCatching {
                            it.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                        }.getOrNull()
                    } ?: continue
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull() ?: continue
                    withContext(Dispatchers.Main) {
                        appendChatMessageFromTrace(obj)
                    }
                }
            } catch (e: Exception) {
                appendLog("[TRACE_PUSH] listener error: ${e.message}")
            } finally {
                server?.close()
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
        onTraceRuntimeEvent(obj, event)

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

    private fun startTaskRuntimeIndicator(taskId: String, phase: String, detail: String) {
        activeRuntimeTaskId = taskId
        sendTaskRuntimeServiceAction(
            action = TaskRuntimeService.ACTION_START,
            taskId = taskId,
            phase = phase,
            detail = detail
        )
    }

    private fun updateTaskRuntimeIndicator(phase: String, detail: String = "", taskId: String = "") {
        val tid = if (taskId.isNotEmpty()) taskId else activeRuntimeTaskId
        sendTaskRuntimeServiceAction(
            action = TaskRuntimeService.ACTION_UPDATE,
            taskId = tid,
            phase = phase,
            detail = detail
        )
    }

    private fun stopTaskRuntimeIndicator() {
        sendTaskRuntimeServiceAction(
            action = TaskRuntimeService.ACTION_STOP,
            taskId = activeRuntimeTaskId,
            phase = "STOP",
            detail = ""
        )
        activeRuntimeTaskId = ""
    }

    private fun sendTaskRuntimeServiceAction(action: String, taskId: String, phase: String, detail: String) {
        val app = getApplication<Application>()
        runCatching {
            val intent = Intent(app, TaskRuntimeService::class.java).apply {
                this.action = action
                putExtra(TaskRuntimeService.EXTRA_TASK_ID, taskId)
                putExtra(TaskRuntimeService.EXTRA_PHASE, phase)
                putExtra(TaskRuntimeService.EXTRA_DETAIL, detail)
            }
            if (action == TaskRuntimeService.ACTION_STOP) {
                app.startService(intent)
            } else {
                ContextCompat.startForegroundService(app, intent)
            }
        }.onFailure { e ->
            appendLog("[RUNTIME_INDICATOR] action=$action failed: ${e.message}")
        }
    }

    private fun shouldTrackRuntimeTask(traceTaskId: String): Boolean {
        val tid = traceTaskId.trim()
        if (tid.isEmpty()) {
            return activeRuntimeTaskId.isNotEmpty()
        }
        if (activeRuntimeTaskId.isEmpty()) {
            activeRuntimeTaskId = tid
            return true
        }
        return activeRuntimeTaskId == tid
    }

    private fun onTraceRuntimeEvent(obj: org.json.JSONObject, event: String) {
        val taskId = obj.optString("task_id", "")
        if (!shouldTrackRuntimeTask(taskId)) {
            return
        }
        when (event) {
            "fsm_state_enter" -> {
                when (obj.optString("state", "")) {
                    "APP_RESOLVE" -> updateTaskRuntimeIndicator("APP_RESOLVE", "Selecting app...", taskId)
                    "ROUTE_PLAN" -> updateTaskRuntimeIndicator("ROUTE_PLAN", "Planning route...", taskId)
                    "ROUTING" -> updateTaskRuntimeIndicator("ROUTING", "Executing route...", taskId)
                    "VISION_ACT" -> updateTaskRuntimeIndicator("VISION_ACT", "Running vision-action...", taskId)
                    "FINISH" -> {
                        updateTaskRuntimeIndicator("DONE", "Task finished.", taskId)
                        stopTaskRuntimeIndicator()
                    }
                    "FAIL" -> {
                        updateTaskRuntimeIndicator("FAILED", "Task failed.", taskId)
                        stopTaskRuntimeIndicator()
                    }
                }
            }

            "vision_screenshot_ready" -> updateTaskRuntimeIndicator("VISION_CAPTURE", "Screenshot captured.", taskId)
            "llm_prompt_vision_act" -> updateTaskRuntimeIndicator("VISION_LLM", "Calling LLM/VLM...", taskId)
            "llm_response_vision_act" -> updateTaskRuntimeIndicator("VISION_PARSE", "Model responded.", taskId)
            "vision_settle_begin" -> updateTaskRuntimeIndicator("SETTLING", "Waiting UI stable...", taskId)
            "vision_settle_ready" -> updateTaskRuntimeIndicator("VISION_ACT", "UI stable.", taskId)
            "vision_settle_timeout" -> updateTaskRuntimeIndicator("SETTLING", "UI settle timeout, continue.", taskId)
            "vision_settle_fallback" -> updateTaskRuntimeIndicator("SETTLING", "Dump unavailable, fallback wait.", taskId)
            "fsm_task_cancelled" -> {
                updateTaskRuntimeIndicator("CANCELLED", "Cancelled by user.", taskId)
                stopTaskRuntimeIndicator()
            }

            "planner_call_failed",
            "vision_instruction_invalid",
            "vision_action_loop_detected",
            "exec_action_error",
            "fsm_app_resolve_failed",
            "fsm_route_plan_failed",
            "fsm_routing_failed" -> {
                updateTaskRuntimeIndicator("FAILED", "Execution failed.", taskId)
                stopTaskRuntimeIndicator()
            }
        }
    }

    // ----- LLM config and test -----

    private fun buildDeviceLlmConfigJson(): String {
        return org.json.JSONObject()
            .put("api_base_url", llmBaseUrl.value.trim())
            .put("api_key", llmApiKey.value)
            .put("model", llmModel.value.trim())
            .put("auto_unlock_before_route", autoUnlockBeforeRoute.value)
            .put("auto_lock_after_task", autoLockAfterTask.value)
            .put("unlock_pin", unlockPin.value.trim())
            .put("use_map", useMap.value)
            .put("map_source", mapSource.value)
            .toString()
    }

    private suspend fun syncDeviceLlmConfigFile(): Result<String> {
        val cfgBytes = buildDeviceLlmConfigJson().toByteArray(Charset.forName("UTF-8"))
        val appConfigPath = AppStatePaths.getLlmConfigPath(getApplication())
        return withContext(Dispatchers.IO) {
            runCatching {
                // 1) Always keep one app-side config snapshot for compatibility/debug.
                val appCfg = File(appConfigPath)
                appCfg.parentFile?.mkdirs()
                appCfg.writeBytes(cfgBytes)

                // 2) If core is reachable, sync to the runtime default path used by native bootstrap.
                val port = currentLxbPortOrNull()
                if (port != null && probeCoreHandshakeReady(1200)) {
                    val shellSync = writeLlmConfigViaCoreShell(port, cfgBytes)
                    if (shellSync.isFailure) {
                        throw shellSync.exceptionOrNull()
                            ?: Exception("core shell sync failed")
                    }
                    val shellDetail = shellSync.getOrNull().orEmpty()
                    return@runCatching "app=$appConfigPath; core=$DEFAULT_LLM_CONFIG_PATH; $shellDetail"
                }

                // Core not running: local snapshot is still useful and will be used by paths that read app state dir.
                "app=$appConfigPath; core_offline_skip=true"
            }
        }
    }

    private fun writeLlmConfigViaCoreShell(port: Int, cfgBytes: ByteArray): Result<String> {
        return runCatching {
            val b64 = Base64.encodeToString(cfgBytes, Base64.NO_WRAP)
            val tmpB64 = "$DEFAULT_LLM_CONFIG_PATH.b64"
            val cmd = buildString {
                append("mkdir -p /data/local/tmp; ")
                append("echo '").append(b64).append("' > ").append(tmpB64).append("; ")
                append("(base64 -d ").append(tmpB64).append(" > ").append(DEFAULT_LLM_CONFIG_PATH)
                append(" || toybox base64 -d ").append(tmpB64).append(" > ").append(DEFAULT_LLM_CONFIG_PATH).append("); ")
                append("rm -f ").append(tmpB64).append("; ")
                append("ls -l ").append(DEFAULT_LLM_CONFIG_PATH)
            }

            val req = org.json.JSONObject()
                .put("action", "shell_exec")
                .put("command", cmd)
                .put("timeout_ms", 6000)
                .toString()
                .toByteArray(Charsets.UTF_8)

            LocalLinkClient("127.0.0.1", port, 4000).use { client ->
                runCatching { client.handshake(2000) }
                val payload = client.sendCommand(
                    CommandIds.CMD_SYSTEM_CONTROL,
                    req,
                    timeoutMs = 8_000
                )
                val parsed = parseSystemControlResponse(payload)
                if (!parsed.first) {
                    throw IllegalStateException(parsed.second)
                }
                parsed.second
            }
        }
    }

    private fun parseSystemControlResponse(payload: ByteArray): Pair<Boolean, String> {
        if (payload.isEmpty()) {
            return Pair(false, "empty_response")
        }
        if (payload.size < 3) {
            return Pair(false, "short_response(${payload.size})")
        }
        val status = payload[0].toInt() and 0xFF
        val jsonLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val available = payload.size - 3
        val safeLen = when {
            jsonLen <= 0 -> available
            jsonLen > available -> available
            else -> jsonLen
        }
        val text = String(payload, 3, safeLen, Charsets.UTF_8)
        val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
        if (obj == null) {
            return Pair(status == 1, "invalid_json:${text.take(180)}")
        }
        val ok = (status == 1) && obj.optBoolean("ok", false)
        val detail = buildString {
            val stdout = obj.optString("stdout", "").trim()
            val stderr = obj.optString("stderr", "").trim()
            val err = obj.optString("error", "").trim()
            if (stdout.isNotEmpty()) append("stdout=").append(stdout.take(200))
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("stderr=").append(stderr.take(200))
            }
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("error=").append(err.take(200))
            }
            if (isEmpty()) {
                append("ok=").append(ok)
            }
        }
        return Pair(ok, detail)
    }

    fun syncDeviceConfigOnly() {
        saveConfig()
        viewModelScope.launch {
            llmTestResult.value = "Syncing device config..."
            val sync = syncDeviceLlmConfigFile()
            if (sync.isSuccess) {
                val path = sync.getOrNull().orEmpty()
                val msg = "Device config synced: $path"
                llmTestResult.value = msg
                appendLog("[LLM] $msg")
            } else {
                val msg = "Failed to sync device config: ${sync.exceptionOrNull()?.message}"
                llmTestResult.value = msg
                appendLog("[LLM] $msg")
            }
        }
    }

    fun syncStableMapsNow() {
        val base = mapRepoRawBaseUrl.value.trim()
        if (base.isEmpty()) {
            mapSyncResult.value = "Map repo raw base URL is empty."
            appendSystemMessage("Map sync failed: empty raw base URL.")
            return
        }
        saveConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val msg = mapSyncManager.syncStableAndApplyAll(base, mapSource.value).fold(
                onSuccess = { r ->
                    "Stable sync+apply done: indexed=${r.indexedCount}, applied=${r.appliedPackages}/${r.totalPackages}, failed=${r.failedPackages}"
                },
                onFailure = { e -> "Stable sync+apply failed: ${e.message}" }
            )
            withContext(Dispatchers.Main) {
                mapSyncResult.value = msg
                appendLog("[MAP] $msg")
                appendSystemMessage(msg)
            }
        }
    }

    fun pullStableByIdentifierNow() {
        val base = mapRepoRawBaseUrl.value.trim()
        val pkg = mapTargetPackage.value.trim()
        val mapId = mapTargetId.value.trim()
        if (base.isEmpty()) {
            mapSyncResult.value = "Map repo raw base URL is empty."
            appendSystemMessage("Pull stable map failed: empty raw base URL.")
            return
        }
        if (pkg.isEmpty() || mapId.isEmpty()) {
            mapSyncResult.value = "Package and Map ID are required."
            appendSystemMessage("Pull stable map failed: package/map_id is empty.")
            return
        }
        saveConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val msg = mapSyncManager.pullStableByIdentifier(base, pkg, mapId, mapSource.value).fold(
                onSuccess = { it },
                onFailure = { e -> "Pull stable map failed: ${e.message}" }
            )
            withContext(Dispatchers.Main) {
                mapSyncResult.value = msg
                appendLog("[MAP] $msg")
                appendSystemMessage(msg)
            }
        }
    }

    fun pullCandidateByIdentifierNow() {
        val base = mapRepoRawBaseUrl.value.trim()
        val pkg = mapTargetPackage.value.trim()
        val mapId = mapTargetId.value.trim()
        if (base.isEmpty()) {
            mapSyncResult.value = "Map repo raw base URL is empty."
            appendSystemMessage("Pull candidate map failed: empty raw base URL.")
            return
        }
        if (pkg.isEmpty() || mapId.isEmpty()) {
            mapSyncResult.value = "Package and Map ID are required."
            appendSystemMessage("Pull candidate map failed: package/map_id is empty.")
            return
        }
        saveConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val msg = mapSyncManager.pullCandidateByIdentifier(base, pkg, mapId, mapSource.value).fold(
                onSuccess = { it },
                onFailure = { e -> "Pull candidate map failed: ${e.message}" }
            )
            withContext(Dispatchers.Main) {
                mapSyncResult.value = msg
                appendLog("[MAP] $msg")
                appendSystemMessage(msg)
            }
        }
    }

    fun checkActiveMapStatus() {
        val pkg = mapTargetPackage.value.trim()
        if (pkg.isEmpty()) {
            mapSyncResult.value = "Package is required."
            return
        }
        val msg = mapSyncManager.activeStatus(pkg)
        mapSyncResult.value = msg
        appendLog("[MAP] $msg")
        appendSystemMessage(msg)
    }

    fun setMapSource(sourceRaw: String) {
        val source = mapSyncManager.normalizeSelectedSource(sourceRaw)
        mapSource.value = source
        saveConfig()
        viewModelScope.launch(Dispatchers.IO) {
            val cfgSync = syncDeviceLlmConfigFile().fold(
                onSuccess = { "config_synced" },
                onFailure = { "config_sync_failed(${it.message})" }
            )
            val applyResult = mapSyncManager.applySelectedSourceToAllPackages(source).fold(
                onSuccess = { r ->
                    "read=${r.readPackages}, applied=${r.appliedPackages}, failed=${r.failedPackages}"
                },
                onFailure = { e -> "apply skipped: ${e.message}" }
            )
            val msg = "Map source set to '$source'. $cfgSync; $applyResult"
            withContext(Dispatchers.Main) {
                mapSyncResult.value = msg
                appendLog("[MAP] $msg")
                appendSystemMessage(msg)
            }
        }
    }

    fun setUseMap(enabled: Boolean) {
        useMap.value = enabled
        saveConfig()
        viewModelScope.launch {
            val sync = syncDeviceLlmConfigFile()
            val state = if (enabled) "ON" else "OFF"
            val msg = if (sync.isSuccess) {
                "Map routing $state. Device config synced: ${sync.getOrNull().orEmpty()}"
            } else {
                "Map routing $state, but device config sync failed: ${sync.exceptionOrNull()?.message}"
            }
            mapSyncResult.value = msg
            appendLog("[MAP] $msg")
            appendSystemMessage(msg)
        }
    }

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
            val sync = syncDeviceLlmConfigFile()
            if (sync.isFailure) {
                val msg = "Failed to write device config: ${sync.exceptionOrNull()?.message}"
                llmTestResult.value = msg
                appendLog("[LLM] $msg")
                return@launch
            }
            val llmConfigPath = sync.getOrNull().orEmpty()
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

    fun checkAppUpdateFromGithub() {
        viewModelScope.launch {
            appUpdateResult.value = "Checking latest release..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(RELEASE_API_LATEST)
                        .addHeader("Accept", "application/vnd.github+json")
                        .addHeader("User-Agent", "lxb-ignition-android")
                        .get()
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        val code = resp.code
                        val text = resp.body?.string() ?: ""
                        if (code !in 200..299) {
                            return@use Pair("Update check failed: HTTP $code ${text.take(160)}", "")
                        }

                        val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                            ?: return@use Pair("Update check failed: invalid GitHub response.", "")
                        val tag = obj.optString("tag_name", "").trim()
                        val latestVersion = normalizeVersion(tag)
                        val currentVersion = normalizeVersion(BuildConfig.VERSION_NAME)
                        val htmlUrl = obj.optString("html_url", RELEASE_WEB_LATEST).trim()
                        val apkUrl = findReleaseApkUrl(obj)
                        val cmp = compareVersion(latestVersion, currentVersion)

                        if (latestVersion.isBlank()) {
                            return@use Pair("Update check failed: latest tag is empty.", "")
                        }

                        if (cmp <= 0) {
                            return@use Pair(
                                "Already up to date (current=$currentVersion, latest=$latestVersion).",
                                ""
                            )
                        }

                        val target = if (apkUrl.isNotBlank()) apkUrl else htmlUrl
                        val msg = if (apkUrl.isNotBlank()) {
                            "New version found: v$latestVersion (current=$currentVersion). Opening APK download..."
                        } else {
                            "New version found: v$latestVersion (current=$currentVersion). Opening release page..."
                        }
                        Pair(msg, target)
                    }
                }.getOrElse { e -> Pair("Update check failed: ${e.message}", "") }
            }
            val msg = result.first
            val url = result.second
            if (url.isNotBlank()) {
                openUrl(url)
            }
            appUpdateResult.value = msg
            appendLog("[UPDATE] $msg")
        }
    }

    fun openLatestReleasePage() {
        openUrl(RELEASE_WEB_LATEST)
    }

    private fun findReleaseApkUrl(obj: org.json.JSONObject): String {
        val assets = obj.optJSONArray("assets") ?: return ""
        var fallback = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                if (name.contains("lxb-ignition", ignoreCase = true)) {
                    return url
                }
                if (fallback.isBlank()) {
                    fallback = url
                }
            }
        }
        return fallback
    }

    private fun normalizeVersion(raw: String): String {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        return if (s.isBlank()) "0.0.0" else s
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = parseVersionParts(a)
        val pb = parseVersionParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = if (i < pa.size) pa[i] else 0
            val vb = if (i < pb.size) pb[i] else 0
            if (va != vb) {
                return if (va > vb) 1 else -1
            }
        }
        return 0
    }

    private fun parseVersionParts(v: String): List<Int> {
        val parts = mutableListOf<Int>()
        v.split(".").forEach { token ->
            val digits = buildString {
                for (ch in token) {
                    if (ch.isDigit()) append(ch) else break
                }
            }
            parts.add(digits.toIntOrNull() ?: 0)
        }
        return parts
    }

    private fun openUrl(url: String) {
        val u = url.trim()
        if (u.isBlank()) {
            appUpdateResult.value = "Invalid update URL."
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }.onFailure { e ->
            appUpdateResult.value = "Failed to open browser: ${e.message}"
            appendLog("[UPDATE] Failed to open browser: ${e.message}")
        }
    }

    fun saveConfig() {
        val normalizedPort = normalizePortString(lxbPort.value)
        if (normalizedPort != lxbPort.value) {
            lxbPort.value = normalizedPort
        }
        val normalizedLang = normalizeUiLang(uiLang.value)
        if (normalizedLang != uiLang.value) {
            uiLang.value = normalizedLang
        }
        prefs.edit()
            .putString(KEY_LXB_PORT, normalizedPort)
            .putString(KEY_SERVER_IP, serverIp.value)
            .putString(KEY_SERVER_PORT, serverPort.value)
            .putString(KEY_LLM_BASE_URL, llmBaseUrl.value)
            .putString(KEY_LLM_API_KEY, llmApiKey.value)
            .putString(KEY_LLM_MODEL, llmModel.value)
            .putBoolean(KEY_AUTO_UNLOCK_BEFORE_ROUTE, autoUnlockBeforeRoute.value)
            .putBoolean(KEY_AUTO_LOCK_AFTER_TASK, autoLockAfterTask.value)
            .putString(KEY_UNLOCK_PIN, unlockPin.value)
            .putBoolean(KEY_USE_MAP, useMap.value)
            .putString(KEY_MAP_REPO_RAW_BASE_URL, mapRepoRawBaseUrl.value)
            .putString(KEY_MAP_SOURCE, normalizeMapSource(mapSource.value))
            .putString(KEY_UI_LANG, normalizedLang)
            .apply()
    }

    fun setUiLang(lang: String) {
        uiLang.value = normalizeUiLang(lang)
        saveConfig()
    }

    private fun currentLxbPortOrNull(): Int? {
        val p = lxbPort.value.trim().toIntOrNull() ?: return null
        return if (p in 1..65535) p else null
    }

    private fun currentLxbPortOrDefault(): Int {
        return currentLxbPortOrNull() ?: DEFAULT_LXB_PORT.toInt()
    }

    private fun persistNormalizedLxbPortIfNeeded() {
        val normalized = normalizePortString(lxbPort.value)
        if (normalized != lxbPort.value) {
            lxbPort.value = normalized
            prefs.edit().putString(KEY_LXB_PORT, normalized).apply()
        }
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
            text = localizeUiMessage(text)
        )
        val current = _chatMessages.value.toMutableList()
        current.add(msg)
        if (current.size > 100) {
            current.subList(0, current.size - 100).clear()
        }
        _chatMessages.value = current
    }

    private fun localizeUiMessage(text: String): String {
        if (uiLang.value != "zh") {
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

    override fun onCleared() {
        super.onCleared()
        unregisterWirelessBootstrapReceiver()
        activeTraceJob?.cancel()
        coreProbeJob?.cancel()
        stopTaskRuntimeIndicator()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun registerWirelessBootstrapReceiver() {
        val app = getApplication<Application>()
        val filter = IntentFilter(WirelessAdbBootstrapService.ACTION_STATUS)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(wirelessBootstrapReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(wirelessBootstrapReceiver, filter)
            }
        }.onFailure { e ->
            appendLog("[WIRELESS_BOOTSTRAP] receiver register failed: ${e.message}")
        }
    }

    private fun unregisterWirelessBootstrapReceiver() {
        val app = getApplication<Application>()
        runCatching { app.unregisterReceiver(wirelessBootstrapReceiver) }
    }
}
