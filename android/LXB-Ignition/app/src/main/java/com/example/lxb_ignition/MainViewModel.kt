package com.example.lxb_ignition

import android.app.Application
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.CoreRuntimeStatus
import com.example.lxb_ignition.model.NotificationTriggerRuleSummary
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskMapDetail
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TracePage
import com.example.lxb_ignition.model.TraceEntry
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class TraceExportUiState(
        val exporting: Boolean = false,
        val status: String = "idle",
        val savedPath: String = "",
        val lineCount: Int = 0,
        val error: String = ""
    )

    data class AdbKeyboardUiState(
        val checked: Boolean = false,
        val installed: Boolean = false,
        val label: String = "",
        val packageName: String = "",
        val currentImeId: String = "",
        val usingNow: Boolean = false
    )

    data class LlmProfile(
        val id: String,
        val name: String,
        val apiBaseUrl: String,
        val apiKey: String,
        val model: String,
        val updatedAt: Long
    )

    private data class VisionProbeChallenge(
        val answer: String,
        val imagePng: ByteArray
    )

    companion object {
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val DEFAULT_LXB_PORT = "12345"

        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_PROFILES_JSON = "llm_profiles_json"
        private const val KEY_ACTIVE_LLM_PROFILE_ID = "active_llm_profile_id"
        private const val KEY_AUTO_UNLOCK_BEFORE_ROUTE = "auto_unlock_before_route"
        private const val KEY_AUTO_LOCK_AFTER_TASK = "auto_lock_after_task"
        private const val KEY_UNLOCK_PIN = "unlock_pin"
        private const val KEY_USE_MAP = "use_map"
        private const val KEY_MAP_REPO_RAW_BASE_URL = "map_repo_raw_base_url"
        private const val KEY_MAP_SOURCE = "map_source"
        // Legacy key migration only (v0.4.0 and earlier).
        private const val KEY_MAP_DEBUG_LOCAL_OVERRIDE = "map_debug_local_override"
        private const val KEY_UI_LANG = "ui_lang"
        private const val KEY_UI_LANG_MANUAL = "ui_lang_manual"
        private const val KEY_TOUCH_MODE = "touch_mode"
        private const val KEY_TASK_DND_MODE = "task_dnd_mode"
        private const val DEFAULT_MAP_REPO_RAW_BASE_URL = "https://raw.githubusercontent.com/wuwei-crg/LXB-MapRepo/main"
        private const val RELEASE_API_LATEST = "https://api.github.com/repos/wuwei-crg/AutoLXB/releases/latest"
        private const val RELEASE_WEB_LATEST = "https://github.com/wuwei-crg/AutoLXB/releases/latest"
        private const val ADB_KEYBOARD_RELEASE_LATEST = "https://github.com/senzhk/ADBKeyBoard/releases/latest"
        private const val DEFAULT_LLM_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json"

        // Local TCP port for trace push from lxb-core.
        private const val TRACE_PUSH_PORT = 23456
        private const val ADB_KEYBOARD_PACKAGE = "com.android.adbkeyboard"
        private const val ADB_KEYBOARD_OPENATX_PACKAGE = "com.github.uiautomator"

        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val TOUCH_MODE_SHELL = "shell"
        const val TOUCH_MODE_UIAUTOMATION = "uiautomation"
        const val TASK_DND_MODE_SKIP = "skip"
        const val TASK_DND_MODE_OFF = "off"
        const val TASK_DND_MODE_NONE = "none"
        const val TASK_MAP_MODE_OFF = "off"
        const val TASK_MAP_MODE_AI = "ai"
        const val TASK_MAP_MODE_MANUAL = "manual"
        const val NOTIFY_ACTION_USE_MAP_INHERIT = "inherit"
        const val NOTIFY_ACTION_USE_MAP_TRUE = "true"
        const val NOTIFY_ACTION_USE_MAP_FALSE = "false"

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

        private fun normalizeTouchMode(raw: String?): String {
            val v = raw?.trim()?.lowercase() ?: TOUCH_MODE_SHELL
            return if (v == TOUCH_MODE_UIAUTOMATION || v == "uiauto") {
                TOUCH_MODE_UIAUTOMATION
            } else {
                TOUCH_MODE_SHELL
            }
        }

        private fun normalizeTaskDndMode(raw: String?): String {
            val v = raw?.trim()?.lowercase() ?: TASK_DND_MODE_NONE
            return when (v) {
                TASK_DND_MODE_SKIP -> TASK_DND_MODE_SKIP
                TASK_DND_MODE_OFF -> TASK_DND_MODE_OFF
                TASK_DND_MODE_NONE -> TASK_DND_MODE_NONE
                else -> TASK_DND_MODE_NONE
            }
        }

        private fun normalizeNotifyActionUseMap(raw: String?): String {
            return when (raw?.trim()?.lowercase()) {
                NOTIFY_ACTION_USE_MAP_TRUE -> NOTIFY_ACTION_USE_MAP_TRUE
                NOTIFY_ACTION_USE_MAP_FALSE -> NOTIFY_ACTION_USE_MAP_FALSE
                else -> NOTIFY_ACTION_USE_MAP_INHERIT
            }
        }

        private fun normalizeTaskMapMode(raw: String?): String {
            return when (raw?.trim()?.lowercase()) {
                TASK_MAP_MODE_AI, TASK_MAP_MODE_MANUAL -> TASK_MAP_MODE_MANUAL
                else -> TASK_MAP_MODE_OFF
            }
        }
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()
    private val _traceLines = MutableStateFlow<List<TraceEntry>>(emptyList())
    val traceLines: StateFlow<List<TraceEntry>> = _traceLines.asStateFlow()
    private val _traceHasMoreBefore = MutableStateFlow(false)
    val traceHasMoreBefore: StateFlow<Boolean> = _traceHasMoreBefore.asStateFlow()
    private val _traceLoadingOlder = MutableStateFlow(false)
    val traceLoadingOlder: StateFlow<Boolean> = _traceLoadingOlder.asStateFlow()
    private val _traceExportUiState = MutableStateFlow(TraceExportUiState())
    val traceExportUiState: StateFlow<TraceExportUiState> = _traceExportUiState.asStateFlow()
    private val _adbKeyboardUiState = MutableStateFlow(AdbKeyboardUiState())
    val adbKeyboardUiState: StateFlow<AdbKeyboardUiState> = _adbKeyboardUiState.asStateFlow()

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
    val llmProfileDraftName = MutableStateFlow("")
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
    val llmProfileResult = MutableStateFlow("")
    val coreConfigResult = MutableStateFlow("")
    val mapSyncResult = MutableStateFlow("")
    val appUpdateResult = MutableStateFlow("")
    private fun detectSystemUiLang(): String {
        val config = getApplication<Application>().resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        } ?: Locale.getDefault()
        return if (locale.language.equals("zh", ignoreCase = true)) "zh" else "en"
    }

    private fun loadInitialUiLang(): String {
        val manual = prefs.getBoolean(KEY_UI_LANG_MANUAL, false)
        return if (manual) {
            normalizeUiLang(prefs.getString(KEY_UI_LANG, detectSystemUiLang()))
        } else {
            detectSystemUiLang()
        }
    }

    val uiLang = MutableStateFlow(loadInitialUiLang())
    val touchMode = MutableStateFlow(normalizeTouchMode(prefs.getString(KEY_TOUCH_MODE, TOUCH_MODE_SHELL)))
    val taskDndMode = MutableStateFlow(normalizeTaskDndMode(prefs.getString(KEY_TASK_DND_MODE, TASK_DND_MODE_NONE)))
    private val _llmProfiles = MutableStateFlow<List<LlmProfile>>(emptyList())
    val llmProfiles: StateFlow<List<LlmProfile>> = _llmProfiles.asStateFlow()
    private val _activeLlmProfileId = MutableStateFlow(prefs.getString(KEY_ACTIVE_LLM_PROFILE_ID, "") ?: "")
    val activeLlmProfileId: StateFlow<String> = _activeLlmProfileId.asStateFlow()

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
    private var updateCheckedOnLaunch = false
    private var taskMapDetailRequestKey: String = ""

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
    private val _wirelessDebuggingEnabled = MutableStateFlow(false)
    val wirelessDebuggingEnabled: StateFlow<Boolean> = _wirelessDebuggingEnabled.asStateFlow()
    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()
    private val _rootDetail = MutableStateFlow("not checked")
    val rootDetail: StateFlow<String> = _rootDetail.asStateFlow()

    // Tasks tab: recent task list (lightweight snapshot).
    private val _taskList = MutableStateFlow<List<TaskSummary>>(emptyList())
    val taskList: StateFlow<List<TaskSummary>> = _taskList.asStateFlow()
    private val _taskMapDetail = MutableStateFlow<TaskMapDetail?>(null)
    val taskMapDetail: StateFlow<TaskMapDetail?> = _taskMapDetail.asStateFlow()
    private val _taskMapDetailLoading = MutableStateFlow(false)
    val taskMapDetailLoading: StateFlow<Boolean> = _taskMapDetailLoading.asStateFlow()
    private val _taskMapSaving = MutableStateFlow(false)
    val taskMapSaving: StateFlow<Boolean> = _taskMapSaving.asStateFlow()
    private val _installedAppList = MutableStateFlow<List<AppPackageOption>>(emptyList())
    val installedAppList: StateFlow<List<AppPackageOption>> = _installedAppList.asStateFlow()

    private val _scheduleList = MutableStateFlow<List<ScheduleSummary>>(emptyList())
    val scheduleList: StateFlow<List<ScheduleSummary>> = _scheduleList.asStateFlow()
    private val _notifyRuleList = MutableStateFlow<List<NotificationTriggerRuleSummary>>(emptyList())
    val notifyRuleList: StateFlow<List<NotificationTriggerRuleSummary>> = _notifyRuleList.asStateFlow()

    // Tasks tab: schedule form
    val scheduleName = MutableStateFlow("")
    val scheduleTask = MutableStateFlow("")
    val scheduleStartAtMs = MutableStateFlow((System.currentTimeMillis() + 5 * 60_000L).toString())
    val scheduleRepeatMode = MutableStateFlow(REPEAT_ONCE)
    val scheduleRepeatWeekdays = MutableStateFlow(0b0011111) // Mon-Fri
    val schedulePackage = MutableStateFlow("")
    val schedulePlaybook = MutableStateFlow("")
    val scheduleEnabled = MutableStateFlow(true)
    val scheduleRecordEnabled = MutableStateFlow(false)
    val scheduleTaskMapMode = MutableStateFlow(TASK_MAP_MODE_OFF)

    // Tasks tab: notification trigger form
    val notifyRuleId = MutableStateFlow("")
    val notifyName = MutableStateFlow("")
    val notifyEnabled = MutableStateFlow(true)
    val notifyPriority = MutableStateFlow("100")
    val notifyPackageMode = MutableStateFlow("any")
    val notifyPackageListRaw = MutableStateFlow("")
    val notifyTextMode = MutableStateFlow("contains")
    val notifyTitlePattern = MutableStateFlow("")
    val notifyBodyPattern = MutableStateFlow("")
    val notifyLlmConditionEnabled = MutableStateFlow(false)
    val notifyLlmCondition = MutableStateFlow("")
    val notifyLlmYesToken = MutableStateFlow("yes")
    val notifyLlmNoToken = MutableStateFlow("no")
    val notifyLlmTimeoutMs = MutableStateFlow("3000")
    val notifyTaskRewriteEnabled = MutableStateFlow(false)
    val notifyTaskRewriteInstruction = MutableStateFlow("")
    val notifyTaskRewriteTimeoutMs = MutableStateFlow("4000")
    val notifyTaskRewriteFailPolicy = MutableStateFlow("fallback_raw_task")
    val notifyCooldownMs = MutableStateFlow("60")
    val notifyActiveTimeStart = MutableStateFlow("")
    val notifyActiveTimeEnd = MutableStateFlow("")
    val notifyStopAfterMatched = MutableStateFlow(true)
    val notifyActionType = MutableStateFlow("run_task")
    val notifyActionUserTask = MutableStateFlow("")
    val notifyActionPackage = MutableStateFlow("")
    val notifyActionUserPlaybook = MutableStateFlow("")
    val notifyActionRecordEnabled = MutableStateFlow(false)
    val notifyActionTaskMapMode = MutableStateFlow(TASK_MAP_MODE_OFF)
    val notifyActionUseMapMode = MutableStateFlow(NOTIFY_ACTION_USE_MAP_INHERIT)

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
        refreshRootAvailability()
        refreshWirelessDebuggingEnabled()
        refreshAdbKeyboardStatus()
        loadLlmProfilesFromPrefs()
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

    fun startServerWithRootDirect() {
        if (!_rootAvailable.value) {
            appendSystemMessage("Root is not available on this device.")
            return
        }
        val port = currentLxbPortOrNull() ?: run {
            appendLog("Invalid lxb-core port")
            appendSystemMessage("Invalid lxb-core port, please check TCP port in Config tab.")
            return
        }
        saveConfig()
        appendLog("[CORE] Root-direct start requested on port $port")
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_START_CORE_ROOT_DIRECT)
    }

    fun refreshRootAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, detail) = detectRootAvailability()
            _rootAvailable.value = ok
            _rootDetail.value = detail
        }
    }

    private fun detectRootAvailability(): Pair<Boolean, String> {
        return runCatching {
            val process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Pair(false, "check timeout")
            }
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("").trim()
            val first = output.lineSequence().firstOrNull()?.trim().orEmpty()
            val ok = process.exitValue() == 0 && first == "0"
            if (ok) {
                Pair(true, "su uid=0")
            } else {
                Pair(false, if (output.isBlank()) "su unavailable" else output.take(80))
            }
        }.getOrElse { e ->
            Pair(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopServerProcess() {
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_STOP_CORE_UNIFIED)
    }

    // Backward-compatible aliases used by old UI call sites.
    fun startServer() = startServerWithNative()
    fun stopServer() = stopServerProcess()

    fun refreshCoreRuntimeStatusNow() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshWirelessDebuggingEnabled()
            publishCoreRuntimeStatus(probeCoreHandshakeReady(1500))
        }
    }

    fun refreshWirelessDebuggingEnabled() {
        val app = getApplication<Application>()
        val enabled = runCatching {
            Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)
        _wirelessDebuggingEnabled.value = enabled
    }

    fun refreshAdbKeyboardStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val currentImeId = runCatching {
                Settings.Secure.getString(app.contentResolver, "default_input_method").orEmpty().trim()
            }.getOrDefault("")

            val detected = when {
                isPackageInstalled(app, ADB_KEYBOARD_PACKAGE) -> {
                    AdbKeyboardUiState(
                        checked = true,
                        installed = true,
                        label = "ADB Keyboard",
                        packageName = ADB_KEYBOARD_PACKAGE,
                        currentImeId = currentImeId,
                        usingNow = currentImeId.startsWith("$ADB_KEYBOARD_PACKAGE/")
                    )
                }

                isPackageInstalled(app, ADB_KEYBOARD_OPENATX_PACKAGE) -> {
                    AdbKeyboardUiState(
                        checked = true,
                        installed = true,
                        label = "OpenATX AdbKeyboard",
                        packageName = ADB_KEYBOARD_OPENATX_PACKAGE,
                        currentImeId = currentImeId,
                        usingNow = currentImeId.startsWith("$ADB_KEYBOARD_OPENATX_PACKAGE/")
                    )
                }

                else -> {
                    AdbKeyboardUiState(
                        checked = true,
                        installed = false,
                        currentImeId = currentImeId
                    )
                }
            }
            _adbKeyboardUiState.value = detected
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

    fun openWirelessDebuggingSettings() {
        sendWirelessBootstrapAction(WirelessAdbBootstrapService.ACTION_OPEN_WIRELESS_DEBUGGING)
    }

    private fun sendWirelessBootstrapAction(action: String) {
        val app = getApplication<Application>()
        runCatching {
            val intent = Intent(app, WirelessAdbBootstrapService::class.java).apply {
                this.action = action
            }
            if (action == WirelessAdbBootstrapService.ACTION_STOP
                || action == WirelessAdbBootstrapService.ACTION_STOP_CORE_NATIVE
                || action == WirelessAdbBootstrapService.ACTION_STOP_CORE_UNIFIED
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
                refreshWirelessDebuggingEnabled()
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
                            .put("task_map_mode", TASK_MAP_MODE_MANUAL)
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

    fun refreshTaskListOnDevice(silent: Boolean = false) {
        val port = currentLxbPortOrNull() ?: run {
            if (!silent) {
                appendSystemMessage("Invalid lxb-core port, cannot refresh task list.")
            }
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
                if (!silent) {
                    appendLog("[FSM] ${result.first}")
                    appendSystemMessage(result.first)
                }
            }
        }
    }

    fun clearTaskMapDetail() {
        taskMapDetailRequestKey = ""
        _taskMapDetailLoading.value = false
        _taskMapSaving.value = false
        _taskMapDetail.value = null
    }

    fun loadTaskMapDetail(task: TaskSummary) {
        loadTaskMapDetailByQuery(
            taskId = task.taskId,
            routeId = task.routeId
        )
    }

    fun loadTaskMapDetailByQuery(
        taskId: String = "",
        routeId: String = "",
        source: String = "",
        sourceId: String = "",
        packageName: String = "",
        userTask: String = "",
        userPlaybook: String = "",
        mode: String = ""
    ) {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot load task route details.")
            clearTaskMapDetail()
            return
        }
        val requestTaskId = taskId.trim()
        val requestRouteId = routeId.trim()
        val requestSource = source.trim()
        val requestSourceId = sourceId.trim()
        val requestPackageName = packageName.trim()
        val requestUserTask = userTask.trim()
        val requestUserPlaybook = userPlaybook.trim()
        val requestMode = mode.trim()
        val requestKey = listOf(
            requestTaskId,
            requestRouteId,
            requestSource,
            requestSourceId,
            requestPackageName,
            requestUserTask,
            requestUserPlaybook,
            requestMode
        ).joinToString("|")
        taskMapDetailRequestKey = requestKey
        _taskMapDetailLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "get")
                        .put("task_id", requestTaskId)
                        .put("route_id", requestRouteId)
                        .put("source", requestSource)
                        .put("source_id", requestSourceId)
                        .put("package_name", requestPackageName)
                        .put("user_task", requestUserTask)
                        .put("user_playbook", requestUserPlaybook)
                        .put("mode", requestMode)
                        .put("include_details", true)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_MAP,
                        payload,
                        timeoutMs = 5_000
                    )
                    CoreApiParser.parseTaskMapDetail(resp)
                }
            }.getOrElse { e -> Pair("Task route detail query failed: ${e.message}", null) }

            withContext(Dispatchers.Main) {
                if (taskMapDetailRequestKey == requestKey) {
                    _taskMapDetail.value = result.second
                    _taskMapDetailLoading.value = false
                }
                appendLog("[TASK_MAP] ${result.first}")
            }
        }
    }

    fun saveManualTaskMapForTask(task: TaskSummary, deleteActionIds: List<String>) {
        saveManualTaskMapByKey(
            routeId = task.routeId,
            taskId = task.taskId,
            deleteActionIds = deleteActionIds,
            finishAfterReplay = false
        ) {
            refreshTaskListOnDevice(silent = true)
            loadTaskMapDetail(task)
        }
    }

    fun saveManualTaskMapByKey(
        routeId: String,
        taskId: String = "",
        deleteActionIds: List<String>,
        finishAfterReplay: Boolean,
        onSaved: (() -> Unit)? = null
    ) {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot save task route.")
            return
        }
        val requestTaskId = taskId.trim()
        val requestRouteId = routeId.trim()
        if (requestTaskId.isEmpty() && requestRouteId.isEmpty()) {
            appendSystemMessage("Route ID is empty.")
            return
        }
        _taskMapSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val deleteArr = org.json.JSONArray()
                    deleteActionIds.forEach { id ->
                        val v = id.trim()
                        if (v.isNotEmpty()) {
                            deleteArr.put(v)
                        }
                    }
                    val payload = org.json.JSONObject()
                        .put("action", "save_manual")
                        .put("task_id", requestTaskId)
                        .put("route_id", requestRouteId)
                        .put("delete_action_ids", deleteArr)
                        .put("finish_after_replay", finishAfterReplay)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_MAP,
                        payload,
                        timeoutMs = 5_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj != null && obj.optBoolean("ok", false)) {
                        val steps = obj.optInt("step_count", 0)
                        val segments = obj.optInt("segment_count", 0)
                        val finishMode = if (obj.optBoolean("finish_after_replay", finishAfterReplay)) "on" else "off"
                        "Task route saved manually: $segments segments, $steps steps, finish_after_replay=$finishMode."
                    } else {
                        "Save task route failed: ${text.take(220)}"
                    }
                }
            }.getOrElse { e -> "Save task route failed: ${e.message}" }
            withContext(Dispatchers.Main) {
                _taskMapSaving.value = false
                appendSystemMessage(msg)
                onSaved?.invoke()
            }
        }
    }

    fun deleteTaskMapForTask(task: TaskSummary) {
        deleteTaskMapByQuery(
            routeId = task.routeId
        ) {
            _taskMapDetail.value = _taskMapDetail.value?.copy(taskMap = null, hasMap = false)
            refreshTaskListOnDevice(silent = true)
        }
    }

    fun exportPortableTaskMapByKey(
        routeId: String,
        taskId: String = "",
        source: String = "",
        sourceId: String = "",
        packageName: String = "",
        userTask: String = "",
        userPlaybook: String = "",
        mode: String = ""
    ) {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot export portable task route.")
            return
        }
        val requestRouteId = routeId.trim()
        if (requestRouteId.isEmpty() && taskId.trim().isEmpty()) {
            appendSystemMessage("Route ID is empty.")
            return
        }
        _taskMapSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "export_portable")
                        .put("task_id", taskId.trim())
                        .put("route_id", requestRouteId)
                        .put("source", source.trim())
                        .put("source_id", sourceId.trim())
                        .put("package_name", packageName.trim())
                        .put("user_task", userTask.trim())
                        .put("user_playbook", userPlaybook.trim())
                        .put("mode", mode.trim())
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_MAP,
                        payload,
                        timeoutMs = 5_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj != null && obj.optBoolean("ok", false)) {
                        val bundleJson = obj.optString("bundle_json", "")
                        if (bundleJson.isBlank()) {
                            "Portable route export failed: empty bundle."
                        } else {
                            val path = writePortableTaskRouteExportFile(bundleJson)
                            val locatorCount = obj.optInt("locator_step_count", 0)
                            val semanticCount = obj.optInt("semantic_step_count", 0)
                            "Portable route exported to $path (locator=$locatorCount, semantic=$semanticCount)."
                        }
                    } else {
                        "Portable route export failed: ${text.take(220)}"
                    }
                }
            }.getOrElse { e -> "Portable route export failed: ${e.message}" }
            withContext(Dispatchers.Main) {
                _taskMapSaving.value = false
                appendSystemMessage(msg)
            }
        }
    }

    fun importPortableTaskMapFromUri(
        targetPackageName: String,
        uri: Uri,
        onImported: ((String) -> Unit)? = null
    ) {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot import portable task route.")
            return
        }
        _taskMapSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching {
                val bundleJson = readPortableRouteBundle(uri)
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "import_portable")
                        .put("target_route_id", "")
                        .put("target_package_name", targetPackageName.trim())
                        .put("bundle_json", bundleJson)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_MAP,
                        payload,
                        timeoutMs = 8_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj != null && obj.optBoolean("ok", false)) {
                        val pending = obj.optInt("pending_adaptation_count", 0)
                        val executable = obj.optInt("materialized_count", 0)
                        val importedTaskId = obj.optString("task_id", obj.optString("route_id", ""))
                        "Portable route imported as new task $importedTaskId (pending_adaptation=$pending, executable=$executable)."
                    } else {
                        "Portable route import failed: ${text.take(220)}"
                    }
                }
            }.getOrElse { e -> "Portable route import failed: ${e.message}" }
            withContext(Dispatchers.Main) {
                _taskMapSaving.value = false
                appendSystemMessage(msg)
                if (!msg.startsWith("Portable route import failed")) {
                    val importedTaskId = Regex("new task ([^ ]+)").find(msg)?.groupValues?.getOrNull(1).orEmpty()
                    onImported?.invoke(importedTaskId)
                }
            }
        }
    }

    fun deleteTaskMapByQuery(
        routeId: String = "",
        taskId: String = "",
        source: String = "",
        sourceId: String = "",
        packageName: String = "",
        userTask: String = "",
        userPlaybook: String = "",
        mode: String = "",
        onDeleted: (() -> Unit)? = null
    ) {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot delete task route.")
            return
        }
        val requestRouteId = routeId.trim()
        val requestTaskId = taskId.trim()
        val requestSource = source.trim()
        val requestSourceId = sourceId.trim()
        val requestPackageName = packageName.trim()
        val requestUserTask = userTask.trim()
        val requestUserPlaybook = userPlaybook.trim()
        val requestMode = mode.trim()
        if (
            requestRouteId.isEmpty() &&
            requestTaskId.isEmpty() &&
            requestSource.isEmpty() &&
            requestSourceId.isEmpty()
        ) {
            appendSystemMessage("Route ID is empty.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "delete")
                        .put("route_id", requestRouteId)
                        .put("task_id", requestTaskId)
                        .put("source", requestSource)
                        .put("source_id", requestSourceId)
                        .put("package_name", requestPackageName)
                        .put("user_task", requestUserTask)
                        .put("user_playbook", requestUserPlaybook)
                        .put("mode", requestMode)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_TASK_MAP,
                        payload,
                        timeoutMs = 5_000
                    )
                    val text = resp.toString(Charsets.UTF_8)
                    val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                    if (obj != null && obj.optBoolean("ok", false)) {
                        if (obj.optBoolean("deleted", false)) "Task route deleted." else "Task route was already missing."
                    } else {
                        "Delete task route failed: ${text.take(220)}"
                    }
                }
            }.getOrElse { e -> "Delete task route failed: ${e.message}" }
            withContext(Dispatchers.Main) {
                appendSystemMessage(msg)
                onDeleted?.invoke()
            }
        }
    }

    fun refreshTraceLogOnDevice(silent: Boolean = false, maxLines: Int = 80) {
        refreshTraceTailOnDevice(silent = silent, limit = maxLines)
    }

    fun refreshTraceTailOnDevice(silent: Boolean = false, limit: Int = 80) {
        val port = currentLxbPortOrNull() ?: run {
            if (!silent) {
                appendSystemMessage("Invalid lxb-core port, cannot refresh core trace.")
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = pullTracePage(
                port = port,
                mode = "tail",
                limit = limit
            )

            withContext(Dispatchers.Main) {
                _traceLines.value = result.second.entries
                _traceHasMoreBefore.value = result.second.hasMoreBefore
                _traceLoadingOlder.value = false
                if (!silent) {
                    appendSystemMessage(result.first)
                }
            }
        }
    }

    fun loadOlderTraceOnDevice(silent: Boolean = true, limit: Int = 80) {
        if (_traceLoadingOlder.value) return
        val beforeSeq = _traceLines.value.firstOrNull()?.seq ?: return
        val port = currentLxbPortOrNull() ?: run {
            if (!silent) {
                appendSystemMessage("Invalid lxb-core port, cannot load older trace.")
            }
            return
        }
        _traceLoadingOlder.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = pullTracePage(
                port = port,
                mode = "before",
                limit = limit,
                beforeSeq = beforeSeq
            )

            withContext(Dispatchers.Main) {
                val merged = LinkedHashMap<Long, TraceEntry>()
                result.second.entries.forEach { merged[it.seq] = it }
                _traceLines.value.forEach { merged[it.seq] = it }
                _traceLines.value = merged.values.sortedBy { it.seq }
                _traceHasMoreBefore.value = result.second.hasMoreBefore
                _traceLoadingOlder.value = false
                if (!silent) {
                    appendSystemMessage(result.first)
                }
            }
        }
    }

    fun pollNewerTraceOnDevice(silent: Boolean = true, limit: Int = 80) {
        val currentNewest = _traceLines.value.lastOrNull()?.seq ?: 0L
        if (currentNewest <= 0L) {
            refreshTraceTailOnDevice(silent = silent, limit = limit)
            return
        }
        val port = currentLxbPortOrNull() ?: run {
            if (!silent) {
                appendSystemMessage("Invalid lxb-core port, cannot refresh core trace.")
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = pullTracePage(
                port = port,
                mode = "after",
                limit = limit,
                afterSeq = currentNewest
            )

            withContext(Dispatchers.Main) {
                if (result.second.entries.isNotEmpty()) {
                    val merged = LinkedHashMap<Long, TraceEntry>()
                    _traceLines.value.forEach { merged[it.seq] = it }
                    result.second.entries.forEach { merged[it.seq] = it }
                    _traceLines.value = merged.values.sortedBy { it.seq }
                }
                if (_traceLines.value.isEmpty()) {
                    _traceHasMoreBefore.value = result.second.hasMoreBefore
                }
                if (!silent) {
                    appendSystemMessage(result.first)
                }
            }
        }
    }

    fun exportAllTraceToDevice() {
        if (_traceExportUiState.value.exporting) return
        val port = currentLxbPortOrNull() ?: run {
            _traceExportUiState.value = TraceExportUiState(
                exporting = false,
                status = "failure",
                error = "Invalid lxb-core port"
            )
            return
        }
        _traceExportUiState.value = TraceExportUiState(exporting = true, status = "exporting")
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val entries = pullAllTraceEntries(port = port, limit = 200)
                if (entries.isEmpty()) {
                    return@runCatching TraceExportUiState(
                        exporting = false,
                        status = "empty"
                    )
                }
                val savedPath = writeTraceExportFile(entries)
                TraceExportUiState(
                    exporting = false,
                    status = "success",
                    savedPath = savedPath,
                    lineCount = entries.size
                )
            }.getOrElse { e ->
                TraceExportUiState(
                    exporting = false,
                    status = "failure",
                    error = e.message ?: e.javaClass.simpleName
                )
            }
            withContext(Dispatchers.Main) {
                _traceExportUiState.value = outcome
            }
            when (outcome.status) {
                "success" -> appendLog("[TRACE] Exported ${outcome.lineCount} lines to ${outcome.savedPath}")
                "empty" -> appendLog("[TRACE] Export skipped: no cached trace available.")
                "failure" -> appendLog("[TRACE] Export failed: ${outcome.error}")
            }
        }
    }

    fun refreshInstalledAppSnapshotOnDevice() {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot refresh installed app snapshot.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    // 1 = user apps only (align with list_app filter).
                    val resp = client.sendCommand(
                        CommandIds.CMD_LIST_APPS,
                        byteArrayOf(1),
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseInstalledApps(resp)
                }
            }.getOrElse { e ->
                Pair("Installed app snapshot failed: ${e.message}", emptyList<AppPackageOption>())
            }

            withContext(Dispatchers.Main) {
                _installedAppList.value = result.second
                appendLog("[APPS] ${result.first}")
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

    fun refreshNotifyRuleListOnDevice() {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot refresh notify rule list.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "list_rules")
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_NOTIFY,
                        payload,
                        timeoutMs = 5_000
                    )
                    CoreApiParser.parseNotifyRuleList(resp)
                }
            }.getOrElse { e ->
                Pair(
                    "Notify rule list query failed: ${e.message}",
                    emptyList<NotificationTriggerRuleSummary>()
                )
            }

            withContext(Dispatchers.Main) {
                _notifyRuleList.value = result.second
                appendLog("[NOTIFY] ${result.first}")
                appendSystemMessage(result.first)
            }
        }
    }

    fun upsertNotifyRuleOnDevice(editingRuleId: String = "") {
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot upsert notify rule.")
            return
        }
        val rule = buildNotifyRulePayloadOrNull(editingRuleId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "upsert_rule")
                        .put("rule", rule)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_NOTIFY,
                        payload,
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseNotifyRuleUpsert(resp)
                }
            }.getOrElse { e -> Pair("Upsert notify rule failed: ${e.message}", "") }

            withContext(Dispatchers.Main) {
                appendLog("[NOTIFY] ${result.first}")
                appendSystemMessage(result.first)
                if (result.second.isNotBlank()) {
                    notifyRuleId.value = result.second
                }
                if (result.first.startsWith("Notify rule added") || result.first.startsWith("Notify rule updated")) {
                    refreshNotifyRuleListOnDevice()
                }
            }
        }
    }

    fun removeNotifyRuleOnDevice(ruleId: String) {
        val rid = ruleId.trim()
        if (rid.isEmpty()) {
            appendSystemMessage("rule_id is empty.")
            return
        }
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot remove notify rule.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "remove_rule")
                        .put("rule_id", rid)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_NOTIFY,
                        payload,
                        timeoutMs = 4_000
                    )
                    CoreApiParser.parseNotifyRuleRemove(resp, rid)
                }
            }.getOrElse { e -> "Remove notify rule failed: ${e.message}" }

            withContext(Dispatchers.Main) {
                appendLog("[NOTIFY] $result")
                appendSystemMessage(result)
                refreshNotifyRuleListOnDevice()
            }
        }
    }

    fun loadNotifyRuleForm(rule: NotificationTriggerRuleSummary) {
        notifyRuleId.value = rule.id
        notifyName.value = rule.name
        notifyEnabled.value = rule.enabled
        notifyPriority.value = rule.priority.toString()
        notifyPackageMode.value = normalizeNotifyPackageMode(rule.packageMode)
        notifyPackageListRaw.value = rule.packageList.firstOrNull().orEmpty()
        notifyTextMode.value = normalizeNotifyTextMode(rule.textMode)
        notifyTitlePattern.value = rule.titlePattern
        notifyBodyPattern.value = rule.bodyPattern
        notifyLlmConditionEnabled.value = rule.llmConditionEnabled
        notifyLlmCondition.value = rule.llmCondition
        notifyLlmYesToken.value = if (rule.llmYesToken.isNotBlank()) rule.llmYesToken else "yes"
        notifyLlmNoToken.value = if (rule.llmNoToken.isNotBlank()) rule.llmNoToken else "no"
        notifyLlmTimeoutMs.value = rule.llmTimeoutMs.toString()
        notifyTaskRewriteEnabled.value = rule.taskRewriteEnabled
        notifyTaskRewriteInstruction.value = rule.taskRewriteInstruction
        notifyTaskRewriteTimeoutMs.value = rule.taskRewriteTimeoutMs.toString()
        notifyTaskRewriteFailPolicy.value = normalizeNotifyRewriteFailPolicy(rule.taskRewriteFailPolicy)
        notifyCooldownMs.value = (rule.cooldownMs / 1000L).toString()
        notifyActiveTimeStart.value = rule.activeTimeStart
        notifyActiveTimeEnd.value = rule.activeTimeEnd
        notifyStopAfterMatched.value = rule.stopAfterMatched
        notifyActionType.value = if (rule.actionType.isNotBlank()) rule.actionType else "run_task"
        notifyActionUserTask.value = rule.actionUserTask
        notifyActionPackage.value = rule.actionPackage
        notifyActionUserPlaybook.value = rule.actionUserPlaybook
        notifyActionRecordEnabled.value = rule.actionRecordEnabled
        notifyActionTaskMapMode.value = normalizeTaskMapMode(rule.actionTaskMapMode)
        notifyActionUseMapMode.value = when (rule.actionUseMap) {
            true -> NOTIFY_ACTION_USE_MAP_TRUE
            false -> NOTIFY_ACTION_USE_MAP_FALSE
            null -> NOTIFY_ACTION_USE_MAP_INHERIT
        }
    }

    fun resetNotifyRuleForm() {
        notifyRuleId.value = ""
        notifyName.value = ""
        notifyEnabled.value = true
        notifyPriority.value = "100"
        notifyPackageMode.value = "any"
        notifyPackageListRaw.value = ""
        notifyTextMode.value = "contains"
        notifyTitlePattern.value = ""
        notifyBodyPattern.value = ""
        notifyLlmConditionEnabled.value = false
        notifyLlmCondition.value = ""
        notifyLlmYesToken.value = "yes"
        notifyLlmNoToken.value = "no"
        notifyLlmTimeoutMs.value = "60000"
        notifyTaskRewriteEnabled.value = true
        notifyTaskRewriteInstruction.value = ""
        notifyTaskRewriteTimeoutMs.value = "60000"
        notifyTaskRewriteFailPolicy.value = "fallback_raw_task"
        notifyCooldownMs.value = "60"
        notifyActiveTimeStart.value = ""
        notifyActiveTimeEnd.value = ""
        notifyStopAfterMatched.value = true
        notifyActionType.value = "run_task"
        notifyActionUserTask.value = ""
        notifyActionPackage.value = ""
        notifyActionUserPlaybook.value = ""
        notifyActionRecordEnabled.value = false
        notifyActionTaskMapMode.value = TASK_MAP_MODE_OFF
        notifyActionUseMapMode.value = NOTIFY_ACTION_USE_MAP_INHERIT
    }

    private fun buildNotifyRulePayloadOrNull(editingRuleId: String): org.json.JSONObject? {
        val id = if (editingRuleId.isNotBlank()) editingRuleId.trim() else notifyRuleId.value.trim()
        val actionTask = notifyActionUserTask.value.trim()
        if (actionTask.isEmpty()) {
            appendSystemMessage("Task description is empty.")
            return null
        }

        val selectedPackage = notifyPackageListRaw.value.trim()
        if (selectedPackage.isEmpty()) {
            appendSystemMessage("Package is required.")
            return null
        }
        val packageList = if (selectedPackage.isEmpty()) emptyList() else listOf(selectedPackage)
        val packageMode = if (packageList.isEmpty()) "any" else "allowlist"
        val cooldownSec = notifyCooldownMs.value.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 60L
        val action = org.json.JSONObject()
            .put("type", "run_task")
            .put("user_task", actionTask)
            .put("package", notifyActionPackage.value.trim())
            .put("user_playbook", notifyActionUserPlaybook.value.trim())
            .put("record_enabled", notifyActionRecordEnabled.value)
            .put("task_map_mode", normalizeTaskMapMode(notifyActionTaskMapMode.value))

        when (normalizeNotifyActionUseMap(notifyActionUseMapMode.value)) {
            NOTIFY_ACTION_USE_MAP_TRUE -> action.put("use_map", true)
            NOTIFY_ACTION_USE_MAP_FALSE -> action.put("use_map", false)
        }

        val rule = org.json.JSONObject()
            .put("name", notifyName.value.trim())
            .put("enabled", notifyEnabled.value)
            .put("priority", notifyPriority.value.trim().toIntOrNull() ?: 100)
            .put("package_mode", packageMode)
            .put("package_list", org.json.JSONArray(packageList))
            .put("text_mode", "contains")
            .put("title_pattern", notifyTitlePattern.value.trim())
            .put("body_pattern", notifyBodyPattern.value.trim())
            .put("llm_condition_enabled", notifyLlmConditionEnabled.value)
            .put("llm_condition", notifyLlmCondition.value.trim())
            .put("llm_yes_token", "yes")
            .put("llm_no_token", "no")
            .put("llm_timeout_ms", 60000L)
            .put("task_rewrite_enabled", true)
            .put("task_rewrite_instruction", "")
            .put("task_rewrite_timeout_ms", 60000L)
            .put("task_rewrite_fail_policy", "fallback_raw_task")
            .put("cooldown_ms", cooldownSec * 1000L)
            .put("active_time_start", notifyActiveTimeStart.value.trim())
            .put("active_time_end", notifyActiveTimeEnd.value.trim())
            .put("stop_after_matched", true)
            .put("action", action)
        if (id.isNotBlank()) {
            rule.put("id", id)
        }
        return rule
    }

    private fun normalizeNotifyPackageMode(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "allowlist" -> "allowlist"
            "blocklist" -> "blocklist"
            else -> "any"
        }
    }

    private fun normalizeNotifyTextMode(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "regex" -> "regex"
            else -> "contains"
        }
    }

    private fun normalizeNotifyRewriteFailPolicy(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "skip" -> "skip"
            else -> "fallback_raw_task"
        }
    }

    private fun buildScheduleDraftOrNull(): ScheduleDraft? {
        val input = ScheduleFormInput(
            name = scheduleName.value,
            task = scheduleTask.value,
            packageName = schedulePackage.value,
            playbook = schedulePlaybook.value,
            enabled = scheduleEnabled.value,
            recordEnabled = scheduleRecordEnabled.value,
            taskMapMode = normalizeTaskMapMode(scheduleTaskMapMode.value),
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
        scheduleEnabled.value = schedule.enabled
        scheduleRecordEnabled.value = schedule.recordEnabled
        scheduleTaskMapMode.value = normalizeTaskMapMode(schedule.taskMapMode)
    }

    fun resetScheduleForm() {
        scheduleName.value = ""
        scheduleTask.value = ""
        scheduleStartAtMs.value = (System.currentTimeMillis() + 5 * 60_000L).toString()
        scheduleRepeatMode.value = REPEAT_ONCE
        scheduleRepeatWeekdays.value = 0b0011111
        schedulePackage.value = ""
        schedulePlaybook.value = ""
        scheduleEnabled.value = true
        scheduleRecordEnabled.value = false
        scheduleTaskMapMode.value = TASK_MAP_MODE_OFF
    }

    fun toggleScheduleEnabledOnDevice(schedule: ScheduleSummary, enabled: Boolean) {
        val sid = schedule.scheduleId.trim()
        if (sid.isEmpty()) {
            appendSystemMessage("schedule_id is empty.")
            return
        }
        if (enabled && schedule.repeatMode == REPEAT_ONCE && schedule.runAtMs <= System.currentTimeMillis()) {
            appendSystemMessage("One-shot schedule in the past cannot be enabled again.")
            return
        }
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot update schedule.")
            return
        }
        val draft = ScheduleDraft(
            name = schedule.name,
            task = schedule.userTask,
            packageName = schedule.packageName,
            playbook = schedule.userPlaybook,
            enabled = enabled,
            recordEnabled = schedule.recordEnabled,
            taskMapMode = normalizeTaskMapMode(schedule.taskMapMode),
            runAt = schedule.runAtMs,
            repeatMode = schedule.repeatMode.ifBlank { REPEAT_ONCE },
            repeatWeekdays = schedule.repeatWeekdays
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_SCHEDULE_UPDATE,
                        buildScheduleUpsertPayload(draft = draft, scheduleId = sid),
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseScheduleUpdate(resp, sid)
                }
            }.getOrElse { e -> "Update schedule failed: ${e.message}" }

            withContext(Dispatchers.Main) {
                appendLog("[SCHEDULE] $result")
                appendSystemMessage(result)
                if (result.startsWith("Schedule updated")) {
                    _scheduleList.value = _scheduleList.value.map {
                        if (it.scheduleId == sid) it.copy(enabled = enabled) else it
                    }
                    refreshScheduleListOnDevice()
                }
            }
        }
    }

    private fun buildNotifyRulePayload(rule: NotificationTriggerRuleSummary, enabled: Boolean): org.json.JSONObject {
        val action = org.json.JSONObject()
            .put("type", if (rule.actionType.isNotBlank()) rule.actionType else "run_task")
            .put("user_task", rule.actionUserTask)
            .put("package", rule.actionPackage)
            .put("user_playbook", rule.actionUserPlaybook)
            .put("record_enabled", rule.actionRecordEnabled)
        when (rule.actionUseMap) {
            true -> action.put("use_map", true)
            false -> action.put("use_map", false)
            null -> {}
        }
        return org.json.JSONObject()
            .put("id", rule.id)
            .put("name", rule.name)
            .put("enabled", enabled)
            .put("priority", rule.priority)
            .put("package_mode", normalizeNotifyPackageMode(rule.packageMode))
            .put("package_list", org.json.JSONArray(rule.packageList))
            .put("text_mode", normalizeNotifyTextMode(rule.textMode))
            .put("title_pattern", rule.titlePattern)
            .put("body_pattern", rule.bodyPattern)
            .put("llm_condition_enabled", rule.llmConditionEnabled)
            .put("llm_condition", rule.llmCondition)
            .put("llm_yes_token", if (rule.llmYesToken.isNotBlank()) rule.llmYesToken else "yes")
            .put("llm_no_token", if (rule.llmNoToken.isNotBlank()) rule.llmNoToken else "no")
            .put("llm_timeout_ms", rule.llmTimeoutMs)
            .put("task_rewrite_enabled", rule.taskRewriteEnabled)
            .put("task_rewrite_instruction", rule.taskRewriteInstruction)
            .put("task_rewrite_timeout_ms", rule.taskRewriteTimeoutMs)
            .put("task_rewrite_fail_policy", normalizeNotifyRewriteFailPolicy(rule.taskRewriteFailPolicy))
            .put("cooldown_ms", rule.cooldownMs)
            .put("active_time_start", rule.activeTimeStart)
            .put("active_time_end", rule.activeTimeEnd)
            .put("stop_after_matched", rule.stopAfterMatched)
            .put("action", action)
    }

    fun toggleNotifyRuleEnabledOnDevice(rule: NotificationTriggerRuleSummary, enabled: Boolean) {
        val rid = rule.id.trim()
        if (rid.isEmpty()) {
            appendSystemMessage("rule_id is empty.")
            return
        }
        val port = currentLxbPortOrNull() ?: run {
            appendSystemMessage("Invalid lxb-core port, cannot upsert notify rule.")
            return
        }
        val payloadRule = buildNotifyRulePayload(rule, enabled)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                coreClientGateway.withClient(port = port) { client ->
                    val payload = org.json.JSONObject()
                        .put("action", "upsert_rule")
                        .put("rule", payloadRule)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    val resp = client.sendCommand(
                        CommandIds.CMD_CORTEX_NOTIFY,
                        payload,
                        timeoutMs = 6_000
                    )
                    CoreApiParser.parseNotifyRuleUpsert(resp)
                }
            }.getOrElse { e -> Pair("Upsert notify rule failed: ${e.message}", "") }

            withContext(Dispatchers.Main) {
                appendLog("[NOTIFY] ${result.first}")
                appendSystemMessage(result.first)
                if (result.first.startsWith("Notify rule updated")) {
                    _notifyRuleList.value = _notifyRuleList.value.map {
                        if (it.id == rid) it.copy(enabled = enabled) else it
                    }
                    refreshNotifyRuleListOnDevice()
                }
            }
        }
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
            mapSource = mapSource.value,
            taskDndMode = taskDndMode.value
        )
    }

    private fun buildVisionProbeChallenge(): VisionProbeChallenge {
        val answer = buildString {
            repeat(5) {
                append(Random.nextInt(0, 10))
            }
        }
        val bmp = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(248, 249, 252))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            isFakeBoldText = true
        }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        fill.color = Color.WHITE
        canvas.drawRoundRect(18f, 18f, 302f, 142f, 18f, 18f, fill)
        accent.color = Color.rgb(210, 214, 224)
        canvas.drawRoundRect(18f, 18f, 302f, 142f, 18f, 18f, accent)

        val noise = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        repeat(6) { idx ->
            noise.color = if (idx % 2 == 0) Color.rgb(210, 80, 80) else Color.rgb(80, 120, 210)
            val y = 35f + idx * 18f + Random.nextInt(-4, 5)
            canvas.drawLine(28f, y, 292f, y + Random.nextInt(-8, 9), noise)
        }

        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 118, 135)
            textSize = 20f
        }
        canvas.drawText("Read digits only", 86f, 42f, guide)
        canvas.drawText(answer, 62f, 110f, text)

        val imagePng = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
        return VisionProbeChallenge(answer = answer, imagePng = imagePng)
    }

    private fun loadLlmProfilesFromPrefs() {
        val raw = prefs.getString(KEY_LLM_PROFILES_JSON, "") ?: ""
        val list = runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val name = obj.optString("name", "").trim()
                    if (id.isEmpty() || name.isEmpty()) continue
                    add(
                        LlmProfile(
                            id = id,
                            name = name,
                            apiBaseUrl = obj.optString("api_base_url", ""),
                            apiKey = obj.optString("api_key", ""),
                            model = obj.optString("model", ""),
                            updatedAt = obj.optLong("updated_at", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        _llmProfiles.value = list.sortedByDescending { it.updatedAt }
        if (_activeLlmProfileId.value.isNotBlank() && list.none { it.id == _activeLlmProfileId.value }) {
            _activeLlmProfileId.value = ""
            prefs.edit().remove(KEY_ACTIVE_LLM_PROFILE_ID).apply()
        }
        val active = list.firstOrNull { it.id == _activeLlmProfileId.value }
        if (active != null && llmProfileDraftName.value.isBlank()) {
            llmProfileDraftName.value = active.name
        }
    }

    private fun persistLlmProfiles(list: List<LlmProfile>) {
        val arr = org.json.JSONArray()
        list.forEach { profile ->
            arr.put(
                org.json.JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("api_base_url", profile.apiBaseUrl)
                    .put("api_key", profile.apiKey)
                    .put("model", profile.model)
                    .put("updated_at", profile.updatedAt)
            )
        }
        prefs.edit().putString(KEY_LLM_PROFILES_JSON, arr.toString()).apply()
        _llmProfiles.value = list.sortedByDescending { it.updatedAt }
    }

    private fun persistActiveLlmProfileId(profileId: String) {
        _activeLlmProfileId.value = profileId
        prefs.edit().putString(KEY_ACTIVE_LLM_PROFILE_ID, profileId).apply()
    }

    fun saveCurrentLlmAsNewProfile() {
        val name = llmProfileDraftName.value.trim()
        if (name.isEmpty()) {
            llmProfileResult.value = localizeLlmProfileMessage("Please enter a config name first.")
            return
        }
        val now = System.currentTimeMillis()
        val next = _llmProfiles.value.toMutableList().apply {
            add(
                0,
                LlmProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    apiBaseUrl = llmBaseUrl.value.trim(),
                    apiKey = llmApiKey.value,
                    model = llmModel.value.trim(),
                    updatedAt = now
                )
            )
        }
        persistLlmProfiles(next)
        val created = next.first()
        persistActiveLlmProfileId(created.id)
        saveConfig()
        llmProfileResult.value = localizeLlmProfileMessage("Saved new LLM config: ${created.name}")
    }

    fun updateSelectedLlmProfile() {
        val activeId = _activeLlmProfileId.value.trim()
        if (activeId.isEmpty()) {
            llmProfileResult.value = localizeLlmProfileMessage("Please select a saved config first.")
            return
        }
        val current = _llmProfiles.value
        val idx = current.indexOfFirst { it.id == activeId }
        if (idx < 0) {
            llmProfileResult.value = localizeLlmProfileMessage("Selected config was not found.")
            persistActiveLlmProfileId("")
            return
        }
        val existing = current[idx]
        val updated = existing.copy(
            name = llmProfileDraftName.value.trim().ifBlank { existing.name },
            apiBaseUrl = llmBaseUrl.value.trim(),
            apiKey = llmApiKey.value,
            model = llmModel.value.trim(),
            updatedAt = System.currentTimeMillis()
        )
        val next = current.toMutableList()
        next[idx] = updated
        persistLlmProfiles(next)
        persistActiveLlmProfileId(updated.id)
        llmProfileDraftName.value = updated.name
        saveConfig()
        llmProfileResult.value = localizeLlmProfileMessage("Updated LLM config: ${updated.name}")
    }

    fun applyLlmProfile(profileId: String) {
        val profile = _llmProfiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            llmProfileResult.value = localizeLlmProfileMessage("Selected config was not found.")
            return
        }
        llmBaseUrl.value = profile.apiBaseUrl
        llmApiKey.value = profile.apiKey
        llmModel.value = profile.model
        llmProfileDraftName.value = profile.name
        persistActiveLlmProfileId(profile.id)
        saveConfig()
        llmProfileResult.value = localizeLlmProfileMessage("Loaded LLM config: ${profile.name}")
    }

    fun deleteLlmProfile(profileId: String) {
        val profile = _llmProfiles.value.firstOrNull { it.id == profileId } ?: return
        val next = _llmProfiles.value.filterNot { it.id == profileId }
        persistLlmProfiles(next)
        if (_activeLlmProfileId.value == profileId) {
            persistActiveLlmProfileId("")
        }
        if (llmProfileDraftName.value.trim() == profile.name) {
            llmProfileDraftName.value = ""
        }
        llmProfileResult.value = localizeLlmProfileMessage("Deleted LLM config: ${profile.name}")
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
            llmTestResult.value = "Testing LLM text + image input..."

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

            // 2) Directly call cloud LLM from APK with a small probe image to validate multimodal input.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val challenge = buildVisionProbeChallenge()
                    val config = com.lxb.server.cortex.LlmConfig(
                        baseUrl,
                        llmApiKey.value.trim(),
                        model
                    )
                    val response = LlmClient().chatOnce(
                        config,
                        "You are a strict multimodal connectivity probe.",
                        "This request includes an image with digits. Read the digits in the image and reply with those digits only. Do not output any extra words, punctuation, spaces, or explanation. If you cannot read the image, reply fail.",
                        challenge.imagePng,
                        10_000,
                        60_000
                    ).trim()
                    if (response == challenge.answer) {
                        "LLM OK: text + image input passed; challenge=${challenge.answer}; device config synced: $llmConfigPath"
                    } else {
                        "LLM test failed: expected `${challenge.answer}`, got `${response.take(120)}`"
                    }
                }.getOrElse { e -> "LLM call failed: ${e.message}" }
            }

            llmTestResult.value = result
            appendLog("[LLM] $result")
        }
    }

    fun checkAppUpdateFromGithub() {
        runUpdateCheck(openTarget = true, showProgress = true, notifyWhenUpToDate = true)
    }

    fun checkAppUpdateOnLaunch() {
        if (updateCheckedOnLaunch) return
        updateCheckedOnLaunch = true
        runUpdateCheck(openTarget = false, showProgress = false, notifyWhenUpToDate = false)
    }

    private fun runUpdateCheck(
        openTarget: Boolean,
        showProgress: Boolean,
        notifyWhenUpToDate: Boolean
    ) {
        viewModelScope.launch {
            if (showProgress) {
                appUpdateResult.value = localizeUpdateMessage("Checking latest release...")
            }
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
            var rawMsg = result.first
            val url = result.second
            if (openTarget && url.isNotBlank()) {
                openUrl(url)
            } else if (!openTarget && url.isNotBlank()) {
                rawMsg = "New version found. Tap \"Check update\" to open download/release page."
            }

            val isUpToDate = rawMsg.startsWith("Already up to date")
            val shouldNotify = url.isNotBlank() || !isUpToDate || notifyWhenUpToDate
            if (shouldNotify) {
                appUpdateResult.value = localizeUpdateMessage(rawMsg)
            }
            appendLog("[UPDATE] $rawMsg")
        }
    }

    private fun localizeUpdateMessage(raw: String): String {
        if (uiLang.value != "zh") {
            return raw
        }
        return when {
            raw == "Checking latest release..." ->
                "正在检查最新版本..."

            raw == "New version found. Tap \"Check update\" to open download/release page." ->
                "发现新版本，点击“检查更新”可打开下载/发布页面。"

            raw.startsWith("Already up to date (current=") -> {
                val m = Regex("""Already up to date \(current=([^,]+), latest=([^)]+)\)\.""")
                    .find(raw)
                if (m != null) {
                    "当前已是最新版本（当前=${m.groupValues[1]}，最新=${m.groupValues[2]}）。"
                } else {
                    "当前已是最新版本。"
                }
            }

            raw.startsWith("New version found: v") -> {
                val m = Regex("""New version found: v([^ ]+) \(current=([^)]+)\)\. (Opening APK download|Opening release page)\.\.\.""")
                    .find(raw)
                if (m != null) {
                    val latest = m.groupValues[1]
                    val current = m.groupValues[2]
                    val action = m.groupValues[3]
                    if (action == "Opening APK download") {
                        "发现新版本：v$latest（当前=$current）。将打开 APK 下载地址..."
                    } else {
                        "发现新版本：v$latest（当前=$current）。将打开 Release 页面..."
                    }
                } else {
                    "发现新版本。"
                }
            }

            raw.startsWith("Update check failed: ") ->
                "检查更新失败：${raw.removePrefix("Update check failed: ")}"

            raw == "Invalid update URL." ->
                "更新地址无效。"

            raw.startsWith("Failed to open browser: ") ->
                "打开浏览器失败：${raw.removePrefix("Failed to open browser: ")}"

            else -> raw
        }
    }

    private fun localizeLlmProfileMessage(raw: String): String {
        if (uiLang.value != "zh") {
            return raw
        }
        return when {
            raw == "Please enter a config name first." ->
                "请先输入配置名称。"
            raw == "Please select a saved config first." ->
                "请先选择一套已保存配置。"
            raw == "Selected config was not found." ->
                "当前选中的配置不存在。"
            raw.startsWith("Saved new LLM config: ") ->
                "已保存新的 LLM 配置：" + raw.removePrefix("Saved new LLM config: ")
            raw.startsWith("Updated LLM config: ") ->
                "已更新 LLM 配置：" + raw.removePrefix("Updated LLM config: ")
            raw.startsWith("Loaded LLM config: ") ->
                "已加载 LLM 配置：" + raw.removePrefix("Loaded LLM config: ")
            raw.startsWith("Deleted LLM config: ") ->
                "已删除 LLM 配置：" + raw.removePrefix("Deleted LLM config: ")
            else -> raw
        }
    }

    fun openLatestReleasePage() {
        openUrl(RELEASE_WEB_LATEST) { msg ->
            appUpdateResult.value = localizeUpdateMessage(msg)
        }
    }

    fun openAdbKeyboardReleasePage() {
        openUrl(ADB_KEYBOARD_RELEASE_LATEST) { msg ->
            coreConfigResult.value = UiMessageLocalizer.localize(uiLang.value, msg)
        }
    }

    fun setTouchMode(mode: String) {
        touchMode.value = normalizeTouchMode(mode)
    }

    fun setTaskDndMode(mode: String) {
        taskDndMode.value = normalizeTaskDndMode(mode)
    }

    fun applyTouchModeToCore() {
        val port = currentLxbPortOrNull() ?: run {
            val msg = "Invalid lxb-core port, cannot apply touch mode."
            coreConfigResult.value = msg
            appendLog("[CORE] $msg")
            return
        }
        saveConfig()
        val selected = normalizeTouchMode(touchMode.value)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    coreClientGateway.withClient(port = port) { client ->
                        val modeByte: Byte = if (selected == TOUCH_MODE_SHELL) 1 else 0
                        val resp = client.sendCommand(
                            CommandIds.CMD_SET_TOUCH_MODE,
                            byteArrayOf(modeByte),
                            timeoutMs = 3_000
                        )
                        val ok = resp.isNotEmpty() && resp[0].toInt() != 0
                        if (!ok) {
                            throw RuntimeException("core rejected touch mode")
                        }
                        val touchMsg = if (selected == TOUCH_MODE_SHELL) {
                            "shell_first"
                        } else {
                            "uiautomation_first"
                        }
                        val sync = syncDeviceLlmConfigFile()
                        if (sync.isFailure) {
                            throw RuntimeException("touch=$touchMsg; config_sync_failed=${sync.exceptionOrNull()?.message}")
                        }
                        val syncMsg = sync.getOrNull().orEmpty()
                        "Control mode applied: touch=$touchMsg; config=$syncMsg"
                    }
                }.getOrElse { e -> "Touch mode apply failed: ${e.message}" }
            }
            coreConfigResult.value = result
            appendLog("[CORE] $result")
        }
    }

    private fun openUrl(url: String, onFailure: ((String) -> Unit)? = null) {
        val u = url.trim()
        if (u.isBlank()) {
            val msg = "Invalid update URL."
            if (onFailure != null) {
                onFailure(msg)
            } else {
                appUpdateResult.value = localizeUpdateMessage(msg)
            }
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }.onFailure { e ->
            val msg = "Failed to open browser: ${e.message}"
            if (onFailure != null) {
                onFailure(msg)
            } else {
                appUpdateResult.value = localizeUpdateMessage(msg)
            }
            appendLog("[UPDATE] $msg")
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
            .putString(KEY_ACTIVE_LLM_PROFILE_ID, _activeLlmProfileId.value)
            .putBoolean(KEY_AUTO_UNLOCK_BEFORE_ROUTE, autoUnlockBeforeRoute.value)
            .putBoolean(KEY_AUTO_LOCK_AFTER_TASK, autoLockAfterTask.value)
            .putString(KEY_UNLOCK_PIN, unlockPin.value)
            .putBoolean(KEY_USE_MAP, useMap.value)
            .putString(KEY_MAP_REPO_RAW_BASE_URL, mapRepoRawBaseUrl.value)
            .putString(KEY_MAP_SOURCE, normalizeMapSource(mapSource.value))
            .putString(KEY_UI_LANG, normalizedLang)
            .putString(KEY_TOUCH_MODE, normalizeTouchMode(touchMode.value))
            .putString(KEY_TASK_DND_MODE, normalizeTaskDndMode(taskDndMode.value))
            .apply()
    }

    fun setUiLang(lang: String) {
        val normalizedLang = normalizeUiLang(lang)
        uiLang.value = normalizedLang
        prefs.edit()
            .putString(KEY_UI_LANG, normalizedLang)
            .putBoolean(KEY_UI_LANG_MANUAL, true)
            .apply()
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

    private fun isPackageInstalled(app: Application, packageName: String): Boolean {
        return runCatching {
            val pm = app.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
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
            ContextCompat.registerReceiver(
                app,
                wirelessBootstrapReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { e ->
            appendLog("[WIRELESS_BOOTSTRAP] receiver register failed: ${e.message}")
        }
    }

    private fun unregisterWirelessBootstrapReceiver() {
        val app = getApplication<Application>()
        runCatching { app.unregisterReceiver(wirelessBootstrapReceiver) }
    }

    private fun pullTracePage(
        port: Int,
        mode: String,
        limit: Int,
        beforeSeq: Long? = null,
        afterSeq: Long? = null,
        throwOnFailure: Boolean = false
    ): Pair<String, TracePage> {
        val request = {
            coreClientGateway.withClient(port = port) { client ->
                val payloadObj = org.json.JSONObject()
                    .put("mode", mode)
                    .put("limit", limit.coerceIn(1, 200))
                if (beforeSeq != null) {
                    payloadObj.put("before_seq", beforeSeq)
                }
                if (afterSeq != null) {
                    payloadObj.put("after_seq", afterSeq)
                }
                val resp = client.sendCommand(
                    CommandIds.CMD_CORTEX_TRACE_PULL,
                    payloadObj.toString().toByteArray(Charsets.UTF_8),
                    timeoutMs = 5_000
                )
                CoreApiParser.parseTraceLines(resp)
            }
        }
        if (throwOnFailure) {
            return request()
        }
        return runCatching {
            request()
        }.getOrElse { e ->
            val seq = beforeSeq ?: afterSeq ?: 0L
            Pair(
                "Trace pull failed: ${e.message}",
                TracePage(
                    entries = emptyList(),
                    hasMoreBefore = false,
                    hasMoreAfter = false,
                    oldestSeq = seq,
                    newestSeq = seq
                )
            )
        }
    }

    private fun pullAllTraceEntries(port: Int, limit: Int): List<TraceEntry> {
        val merged = LinkedHashMap<Long, TraceEntry>()
        var page = pullTracePage(
            port = port,
            mode = "tail",
            limit = limit,
            throwOnFailure = true
        ).second
        page.entries.forEach { merged[it.seq] = it }
        var oldestSeq = page.entries.firstOrNull()?.seq ?: page.oldestSeq
        var guard = 0
        while (page.hasMoreBefore && oldestSeq > 0L && guard < 2048) {
            val nextPage = pullTracePage(
                port = port,
                mode = "before",
                limit = limit,
                beforeSeq = oldestSeq,
                throwOnFailure = true
            ).second
            val nextOldestSeq = nextPage.entries.firstOrNull()?.seq ?: nextPage.oldestSeq
            if (nextPage.entries.isEmpty() || nextOldestSeq <= 0L || nextOldestSeq >= oldestSeq) {
                break
            }
            nextPage.entries.forEach { merged[it.seq] = it }
            page = nextPage
            oldestSeq = nextOldestSeq
            guard += 1
        }
        return merged.values.sortedBy { it.seq }
    }

    private fun writeTraceExportFile(entries: List<TraceEntry>): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "lxb-trace-$stamp.jsonl"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/LXB"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                val stream = resolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Cannot open export file stream.")
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    entries.forEach { entry ->
                        writer.append(entry.rawLine)
                        writer.append('\n')
                    }
                }
                val publish = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                return "Downloads/LXB/$fileName"
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

        val fallbackDir = File(app.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val fallbackFile = File(fallbackDir, fileName)
        fallbackFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            entries.forEach { entry ->
                writer.append(entry.rawLine)
                writer.append('\n')
            }
        }
        return fallbackFile.absolutePath
    }

    private fun writePortableTaskRouteExportFile(bundleJson: String): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "lxb-route-portable-$stamp.json"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/LXB/Routes"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                val stream = resolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Cannot open portable export file stream.")
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(bundleJson)
                }
                val publish = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
                return "Downloads/LXB/Routes/$fileName"
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

        val fallbackDir = File(app.getExternalFilesDir(null), "routes").apply { mkdirs() }
        val fallbackFile = File(fallbackDir, fileName)
        fallbackFile.writeText(bundleJson, Charsets.UTF_8)
        return fallbackFile.absolutePath
    }

    private fun readPortableRouteBundle(uri: Uri): String {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open portable route file.")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
