package com.example.lxb_ignition.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.example.lxb_ignition.BuildConfig
import com.example.lxb_ignition.IShizukuService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku manager wrapping the UserService (ShizukuServiceImpl) running in shell.
 *
 * Responsibilities:
 * - Bind/unbind the Shizuku user service.
 * - Start / stop lxb-core server via AIDL.
 * - Poll lxb-core log file and forward lines to listener.
 * - Keep a lightweight state machine for UI / notification (UNAVAILABLE / READY / RUNNING / ...).
 *
 * All user-visible text is ASCII-only to avoid encoding issues.
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val ASSET_JAR = "lxb-core-dex.jar"
        private const val TMP_JAR = "/data/local/tmp/lxb-core.jar"
        private const val SERVER_CLASS = "com.lxb.server.Main"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val LOG_POLL_INTERVAL_MS = 2000L
        private const val LXB_STATE_DIR_NAME = "lxb_state"
    }

    enum class State {
        UNAVAILABLE,        // Shizuku binder not ready
        PERMISSION_DENIED,  // Shizuku permission denied
        READY,              // UserService bound, can start server
        STARTING,           // Deploying jar / starting server
        RUNNING,            // lxb-core server process is running
        ERROR               // Error state
    }

    interface Listener {
        fun onStateChanged(state: State, message: String)
        fun onLogLine(line: String)
    }

    @Volatile
    var currentState: State = State.UNAVAILABLE
        private set

    private var listener: Listener? = null
    private var service: IShizukuService? = null
    private var isBound: Boolean = false
    private var logBytesRead: Long = 0L
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logPollJob: Job? = null

    fun setListener(l: Listener) {
        listener = l
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        listener?.onLogLine(msg)
    }

    private fun setState(state: State, msg: String) {
        currentState = state
        listener?.onStateChanged(state, msg)
    }

    // ----- Shizuku binder / user service lifecycle -----

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuServiceImpl::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShizukuService.Stub.asInterface(binder)
            isBound = true
            log("Shizuku UserService connected")

            // When (re)connected, detect whether lxb-core is already running in background.
            // This handles the case where the APK was killed but the server process kept running.
            scope.launch {
                val svc = service
                if (svc != null) {
                    val running = runCatching { svc.isRunning(SERVER_CLASS) }.getOrDefault(false)
                    if (running) {
                        log("Detected lxb-core already running in background")
                        logBytesRead = 0L
                        setState(State.RUNNING, "Server is already running in background")
                        startLogPolling(svc)
                        return@launch
                    }
                }
                if (currentState != State.RUNNING && currentState != State.STARTING) {
                    setState(State.READY, "Shizuku ready, you can start the server")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            log("Shizuku UserService disconnected")
            if (currentState == State.RUNNING) {
                setState(State.ERROR, "UserService disconnected unexpectedly, please retry")
            }
        }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        log("Shizuku binder received")
        refreshState()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        log("Shizuku binder dead")
        service = null
        isBound = false
        logPollJob?.cancel()
        setState(State.UNAVAILABLE, "Shizuku binder is not ready")
    }

    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted")
            bindServiceIfNeeded()
        } else {
            setState(State.PERMISSION_DENIED, "Shizuku permission denied")
        }
    }

    /** Should be called from ViewModel.init or Service.onCreate. */
    fun attach() {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refreshState()
    }

    /** Should be called from ViewModel.onCleared or Service.onDestroy. */
    fun detach() {
        logPollJob?.cancel()
        scope.cancel()
        if (isBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, false) }
            isBound = false
        }
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermissionResult)
    }

    fun refreshState() {
        when {
            !Shizuku.pingBinder() -> {
                setState(
                    State.UNAVAILABLE,
                    "Shizuku is not running, please start the Shizuku service first"
                )
            }

            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                setState(
                    State.PERMISSION_DENIED,
                    "Please grant Shizuku permission to this app"
                )
            }

            else -> {
                bindServiceIfNeeded()
            }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Write a config file to a shell-accessible path via the user service (reuses deployJar).
     */
    suspend fun writeConfigFile(destPath: String, content: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext Result.failure(
                Exception("Shizuku UserService is not connected, cannot write config file")
            )
            return@withContext try {
                val ok = svc.deployJar(content, destPath)
                if (ok) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("deployJar returned false"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun bindServiceIfNeeded() {
        if (!isBound) {
            log("Binding Shizuku UserService...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } else if (currentState != State.RUNNING && currentState != State.STARTING) {
            setState(State.READY, "Shizuku ready")
        }
    }

    fun getLxbStateBaseDir(): String {
        val externalBase = context.getExternalFilesDir(null)?.absolutePath
        val base = if (!externalBase.isNullOrBlank()) {
            File(externalBase, LXB_STATE_DIR_NAME)
        } else {
            File("/data/local/tmp/lxb/$LXB_STATE_DIR_NAME")
        }
        if (!base.exists()) {
            runCatching { base.mkdirs() }
        }
        return base.absolutePath
    }

    fun getMapDirPath(): String {
        val dir = File(getLxbStateBaseDir(), "maps")
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        return dir.absolutePath
    }

    fun getLlmConfigPath(): String {
        val base = File(getLxbStateBaseDir())
        if (!base.exists()) {
            runCatching { base.mkdirs() }
        }
        return File(base, "lxb-llm-config.json").absolutePath
    }

    fun getTaskMemoryPath(): String {
        val base = File(getLxbStateBaseDir())
        if (!base.exists()) {
            runCatching { base.mkdirs() }
        }
        return File(base, "task_memory.json").absolutePath
    }

    private fun buildServerJvmOpts(): String {
        val mapDir = getMapDirPath()
        val llmCfg = getLlmConfigPath()
        val taskMem = getTaskMemoryPath()
        return listOf(
            "-Dlxb.map.dir=$mapDir",
            "-Dlxb.llm.config.path=$llmCfg",
            "-Dlxb.task.memory.path=$taskMem"
        ).joinToString(" ")
    }

    // ----- lxb-core server management -----

    suspend fun startServer(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext Result.failure(
            Exception("Shizuku UserService is not connected, please retry")
        )
        try {
            setState(State.STARTING, "Reading JAR from assets...")
            val jarBytes = readAssetBytes(ASSET_JAR)
            log("Read JAR from assets (${jarBytes.size} bytes)")

            setState(State.STARTING, "Writing JAR to $TMP_JAR...")
            val deployed = svc.deployJar(jarBytes, TMP_JAR)
            if (!deployed) {
                val msg = "Failed to write JAR to $TMP_JAR"
                setState(State.ERROR, msg)
                return@withContext Result.failure(Exception(msg))
            }
            log("JAR written to $TMP_JAR")

            setState(State.STARTING, "Starting lxb-core (UDP :$port)...")
            val jvmOpts = buildServerJvmOpts()
            log("Server storage base: ${getLxbStateBaseDir()}")
            val result = svc.startServerWithJvmOpts(TMP_JAR, SERVER_CLASS, port, jvmOpts)

            if (result.startsWith("OK")) {
                logBytesRead = 0L
                setState(State.RUNNING, "Server running (UDP :$port)")
                result.removePrefix("OK\n")
                    .lines()
                    .filter { it.isNotEmpty() }
                    .forEach { log(it) }
                startLogPolling(svc)
                Result.success(Unit)
            } else {
                val body = result.removePrefix("ERROR\n")
                val summary = body.lines().firstOrNull { it.isNotEmpty() } ?: "Failed to start"
                setState(State.ERROR, summary)
                body.lines()
                    .filter { it.isNotEmpty() }
                    .forEach { log(it) }
                Result.failure(Exception(summary))
            }
        } catch (e: Exception) {
            val msg = "Failed to start: ${e.message}"
            log(msg)
            setState(State.ERROR, msg)
            Result.failure(e)
        }
    }

    suspend fun stopServer(): Result<Unit> = withContext(Dispatchers.IO) {
        logPollJob?.cancel()
        try {
            service?.stopServer(SERVER_CLASS)
            setState(State.READY, "Server stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = "Failed to stop: ${e.message}"
            log(msg)
            Result.failure(e)
        }
    }

    suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching { service?.isRunning(SERVER_CLASS) ?: false }.getOrDefault(false)
    }

    // ----- log polling -----

    private fun startLogPolling(svc: IShizukuService) {
        logPollJob?.cancel()
        logPollJob = scope.launch {
            while (isActive) {
                delay(LOG_POLL_INTERVAL_MS)
                try {
                    val chunk = svc.readLogPart(logBytesRead, 4096)
                    if (chunk.isNotEmpty()) {
                        // Note: we approximate consumed bytes using UTF-8; server uses same.
                        val bytes = chunk.toByteArray(Charsets.UTF_8)
                        logBytesRead += bytes.size
                        chunk.lines()
                            .filter { it.isNotEmpty() }
                            .forEach { line -> listener?.onLogLine(line) }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    // ----- helpers -----

    private fun readAssetBytes(name: String): ByteArray {
        return try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: Exception) {
            throw Exception(
                "Cannot read $name from assets; please run ./gradlew :lxb-core:buildDex first: ${e.message}"
            )
        }
    }
}
