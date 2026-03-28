package com.example.lxb_ignition

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxb_ignition.core.AppUpdateChecker
import com.example.lxb_ignition.core.CoreApiParser
import com.example.lxb_ignition.core.DeviceConfigSyncer
import com.example.lxb_ignition.core.DeviceLlmSettings
import com.example.lxb_ignition.core.MapOperationsController
import com.example.lxb_ignition.core.TaskRuntimeController
import com.example.lxb_ignition.map.MapSyncManager
import com.example.lxb_ignition.model.CoreRuntimeStatus
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TaskRuntimeUiStatus
import com.example.lxb_ignition.model.WirelessBootstrapStatus
import com.example.lxb_ignition.schedule.ScheduleDraft
import com.example.lxb_ignition.schedule.ScheduleFormInput
import com.example.lxb_ignition.schedule.ScheduleUseCase
import com.example.lxb_ignition.service.CoreClientGateway
import com.example.lxb_ignition.service.WirelessAdbBootstrapService
import com.lxb.server.cortex.LlmClient
import com.lxb.server.protocol.CommandIds
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
    val llmModel = MutableStateFlow(prefs.getString(KEY_LLM_MODEL, "") ?: "")
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
    private var coreProbeJob: Job? = null

    private val runtimeController by lazy {
        TaskRuntimeController(
            app = application,
            scope = viewModelScope,
            tracePort = TRACE_PUSH_PORT,
            appendLog = ::appendLog,
            appendSystemMessage = ::appendSystemMessage
        )
    }
    val taskRuntimeUiStatus: StateFlow<TaskRuntimeUiStatus>
        get() = runtimeController.status

    private val _coreRuntimeStatus = MutableStateFlow(CoreRuntimeStatus())
    val coreRuntimeStatus: StateFlow<CoreRuntimeStatus> = _coreRuntimeStatus.asStateFlow()

    private val _wirelessBootstrapStatus = MutableStateFlow(WirelessBootstrapStatus())
    val wirelessBootstrapStatus: StateFlow<WirelessBootstrapStatus> = _wirelessBootstrapStatus.asStateFlow()

    // Tasks tab: recent task list (lightweight snapshot).
    private val _taskList = MutableStateFlow<List<TaskSummary>>(emptyList())
    val taskList: StateFlow<List<TaskSummary>> = _taskList.asStateFlow()

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
    private val coreClientGateway = CoreClientGateway()
    private val deviceConfigSyncer = DeviceConfigSyncer(
        app = application,
        coreClientGateway = coreClientGateway,
        defaultConfigPath = DEFAULT_LLM_CONFIG_PATH
    )
    private val mapOperationsController by lazy {
        MapOperationsController(
            viewModelScope = viewModelScope,
            mapSyncManager = mapSyncManager,
            mapRepoRawBaseUrl = mapRepoRawBaseUrl,
            mapTargetPackage = mapTargetPackage,
            mapTargetId = mapTargetId,
            mapSource = mapSource,
            useMap = useMap,
            mapSyncResult = mapSyncResult,
            saveConfig = ::saveConfig,
            syncDeviceConfig = ::syncDeviceLlmConfigFile,
            appendLog = ::appendLog,
            appendSystemMessage = ::appendSystemMessage
        )
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            val msg = mapSyncManager.startupSyncStable(
                rawBaseUrl = mapRepoRawBaseUrl.value.trim(),
                selectedSourceRaw = mapSource.value
            ).getOrElse { "startup stable sync skipped: ${it.message}" }
            appendLog("[MAP] $msg")
        }
    }

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
        return coreClientGateway.probeHandshakeReady(port, timeoutMs)
    }

    private fun publishCoreRuntimeStatus(ready: Boolean) {
        val port = currentLxbPortOrNull() ?: 12345
        _coreRuntimeStatus.value = if (ready) {
            CoreRuntimeStatus(
                ready = true,
                detail = "Connected (${coreClientGateway.host}:$port, handshake ok)"
            )
        } else {
            CoreRuntimeStatus(
                ready = false,
                detail = "Disconnected (${coreClientGateway.host}:$port)"
            )
        }
    }

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
            runtimeController.resetForSubmission()

            val running = withContext(Dispatchers.IO) { probeCoreHandshakeReady(1500) }
            if (!running) {
                val err = "lxb-core is not running, please start the service on the home tab."
                sendResult.value = err
                appendLog("[FSM] $err")
                appendSystemMessage("Server is not running, please start the service first.")
                runtimeController.stopIndicator()
                return@launch
            }

            appendSystemMessage("Server is running, calling Cortex FSM on device...")
            val sync = withContext(Dispatchers.IO) { syncDeviceLlmConfigFile() }
            if (sync.isFailure) {
                val err = "Failed to sync runtime config: ${sync.exceptionOrNull()?.message}"
                sendResult.value = err
                appendLog("[FSM] $err")
                appendSystemMessage(err)
                runtimeController.stopIndicator()
                return@launch
            }

            // Ensure trace listener is running so that chat shows live FSM progress.
            runtimeController.ensureTraceListener()

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    coreClientGateway.withClient(port = port) { client ->
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
                        val parsed = CoreApiParser.parseTaskSubmit(respBytes)
                        Pair(parsed.message, parsed.taskId)
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
                runtimeController.startIndicator(
                    taskId = taskId,
                    phase = "RUNNING",
                    detail = "Task submitted, waiting for trace events..."
                )
            } else {
                runtimeController.stopIndicator()
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
                coreClientGateway.withClient(port = port) { client ->
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
                    runtimeController.markCancelling()
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
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("limit", 50)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_LIST,
                        payload,
                        timeoutMs = 5_000
                    )
                    CoreApiParser.parseTaskList(resp)
                }
            }.getOrElse { e -> Pair("Task list query failed: ${e.message}", emptyList<TaskSummary>()) }

            withContext(Dispatchers.Main) {
                _taskList.value = result.second
                runtimeController.syncFromTaskList(result.second)
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
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("limit", 100)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_LIST,
                        payload,
                        timeoutMs = 5_000
                    )
                    CoreApiParser.parseScheduleList(
                        payload = resp,
                        repeatDaily = REPEAT_DAILY,
                        repeatOnce = REPEAT_ONCE
                    )
                }
            }.getOrElse { e -> Pair("Schedule list query failed: ${e.message}", emptyList<ScheduleSummary>()) }

            withContext(Dispatchers.Main) {
                _scheduleList.value = result.second
                appendLog("[SCHEDULE] ${result.first}")
                appendSystemMessage(result.first)
            }
        }
    }

    private fun buildScheduleDraftOrNull(): ScheduleDraft? {
        val input = ScheduleFormInput(
            name = scheduleName.value,
            task = scheduleTask.value,
            packageName = schedulePackage.value,
            playbook = schedulePlaybook.value,
            recordEnabled = scheduleRecordEnabled.value,
            runAtRaw = scheduleStartAtMs.value,
            repeatModeRaw = scheduleRepeatMode.value,
            repeatWeekdays = scheduleRepeatWeekdays.value
        )
        return ScheduleUseCase.buildDraft(
            input = input,
            nowMs = System.currentTimeMillis(),
            repeatOnce = REPEAT_ONCE,
            repeatWeekly = REPEAT_WEEKLY
        ).getOrElse { e ->
            appendSystemMessage(e.message ?: "Invalid schedule input.")
            null
        }
    }

    private fun buildScheduleUpsertPayload(
        draft: ScheduleDraft,
        scheduleId: String? = null
    ): ByteArray {
        return ScheduleUseCase.buildUpsertPayload(
            draft = draft,
            traceUdpPort = TRACE_PUSH_PORT,
            repeatDaily = REPEAT_DAILY,
            scheduleId = scheduleId
        )
    }

    fun addScheduleOnDevice() {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot add schedule.")
            return
        }
        val draft = buildScheduleDraftOrNull() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_ADD,
                        buildScheduleUpsertPayload(draft = draft),
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseScheduleAdd(resp)
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
        val draft = buildScheduleDraftOrNull() ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_UPDATE,
                        buildScheduleUpsertPayload(
                            draft = draft,
                            scheduleId = sid
                        ),
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseScheduleUpdate(resp, sid)
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
                coreClientGateway.withClient(port = port) { client ->
                    val payloadJson = org.json.JSONObject()
                        .put("schedule_id", scheduleId)
                        .toString()
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_REMOVE,
                        payloadJson.toByteArray(Charsets.UTF_8),
                        timeoutMs = 4_000
                    )
                    CoreApiParser.parseScheduleRemove(resp, scheduleId)
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

    // ----- LLM config and test -----

    private fun currentDeviceLlmSettings(): DeviceLlmSettings {
        return DeviceLlmSettings(
            apiBaseUrl = llmBaseUrl.value,
            apiKey = llmApiKey.value,
            model = llmModel.value,
            autoUnlockBeforeRoute = autoUnlockBeforeRoute.value,
            autoLockAfterTask = autoLockAfterTask.value,
            unlockPin = unlockPin.value,
            useMap = useMap.value,
            mapSource = mapSource.value
        )
    }

    private suspend fun syncDeviceLlmConfigFile(): Result<String> {
        return withContext(Dispatchers.IO) {
            deviceConfigSyncer.sync(
                settings = currentDeviceLlmSettings(),
                port = currentLxbPortOrNull()
            )
        }
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

    fun syncStableMapsNow() = mapOperationsController.syncStableMapsNow()
    fun pullStableByIdentifierNow() = mapOperationsController.pullStableByIdentifierNow()
    fun pullCandidateByIdentifierNow() = mapOperationsController.pullCandidateByIdentifierNow()
    fun checkActiveMapStatus() = mapOperationsController.checkActiveMapStatus()
    fun setMapSource(sourceRaw: String) = mapOperationsController.setMapSource(sourceRaw)
    fun setUseMap(enabled: Boolean) = mapOperationsController.setUseMap(enabled)

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
                        val parsed = AppUpdateChecker.evaluateLatestRelease(
                            httpCode = code,
                            bodyText = text,
                            currentVersionName = BuildConfig.VERSION_NAME,
                            defaultWebLatest = RELEASE_WEB_LATEST
                        )
                        Pair(parsed.message, parsed.targetUrl)
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
            text = UiMessageLocalizer.localize(uiLang.value, text)
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
        unregisterWirelessBootstrapReceiver()
        coreProbeJob?.cancel()
        runtimeController.clear()
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
