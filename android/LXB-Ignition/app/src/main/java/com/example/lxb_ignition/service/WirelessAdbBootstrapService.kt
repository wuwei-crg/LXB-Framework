package com.example.lxb_ignition.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.lxb_ignition.MainActivity
import com.example.lxb_ignition.R
import com.example.lxb_ignition.storage.AppStatePaths
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Wireless ADB bootstrap skeleton:
 * - Foreground notification + inline input (pairing code only)
 * - Pairing/connect service discovery via NSD (_adb-tls-pairing/_adb-tls-connect)
 * - State machine and status broadcasts for UI
 */
class WirelessAdbBootstrapService : Service() {

    companion object {
        const val ACTION_START = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_START"
        const val ACTION_START_GUIDE = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_START_GUIDE"
        const val ACTION_STOP = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_STOP"
        const val ACTION_START_CORE_NATIVE = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_START_CORE_NATIVE"
        const val ACTION_START_CORE_ROOT_DIRECT = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_START_CORE_ROOT_DIRECT"
        const val ACTION_STOP_CORE_NATIVE = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_STOP_CORE_NATIVE"
        const val ACTION_STOP_CORE_UNIFIED = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_STOP_CORE_UNIFIED"
        const val ACTION_OPEN_DEV_OPTIONS = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_OPEN_DEV_OPTIONS"
        const val ACTION_OPEN_WIRELESS_DEBUGGING = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_OPEN_WIRELESS_DEBUGGING"
        const val ACTION_SUBMIT_PAIRING = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_SUBMIT_PAIRING"

        const val ACTION_STATUS = "com.example.lxb_ignition.action.WIRELESS_BOOTSTRAP_STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RUNNING = "running"

        const val EXTRA_PAIR_CODE = "pair_code"

        private const val REMOTE_INPUT_PAIR_CODE = "wireless_pair_code"

        private const val CHANNEL_ID = "lxb_wireless_bootstrap"
        private const val CHANNEL_NAME = "LXB Wireless Bootstrap"
        private const val NOTIFICATION_ID = 1003
        private const val SERVICE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_UI_LANG = "ui_lang"
        private const val KEY_WIRELESS_ADB_HOST = "wireless_adb_host"
        private const val KEY_WIRELESS_ADB_PORT = "wireless_adb_port"
        private const val DEFAULT_LXB_PORT = 12345
        private const val WATCHDOG_INTERVAL_MS = 12_000L
        private const val START_RETRY_MAX_ATTEMPTS = 12
        private const val START_RETRY_INTERVAL_MS = 5_000L
        private const val CONNECT_ENDPOINT_WAIT_MS = 2_500L

        private const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        private const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
        private const val ACTION_WIRELESS_DEBUGGING_SETTINGS = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        private const val CORE_CLASS = "com.lxb.server.Main"
        private const val CORE_NICE_NAME = "lxb_core"
        private val STARTER_ASSET_BY_ABI = linkedMapOf(
            "arm64-v8a" to "lxb-starter-arm64",
            "armeabi-v7a" to "lxb-starter-armv7",
            "x86_64" to "lxb-starter-x86_64",
            "x86" to "lxb-starter-x86"
        )
        private const val REMOTE_JAR_PATH = "/data/local/tmp/lxb-core.jar"
        private const val REMOTE_STARTER_PATH = "/data/local/tmp/lxb-starter"
        private const val REMOTE_LOG_PATH = "/data/local/tmp/lxb-core.log"
        private const val REMOTE_APP_LABELS_PATH = "/data/local/tmp/lxb-app-labels.tsv"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Endpoint(val host: String, val port: Int) {
        fun asText(): String = "$host:$port"
    }

    private data class RelaunchResult(
        val ok: Boolean,
        val detail: String
    )

    private data class StarterResult(
        val ok: Boolean,
        val detail: String
    )

    private data class StarterAsset(
        val abi: String,
        val assetName: String
    )

    private var nsdManager: NsdManager? = null
    private var pairingDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connectDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var pairingResolveListener: NsdManager.ResolveListener? = null
    private var connectResolveListener: NsdManager.ResolveListener? = null
    private var latestPairingEndpoint: Endpoint? = null
    private var latestConnectEndpoint: Endpoint? = null

    private var running = false
    private var currentState = "IDLE"
    private var currentMessage = "Idle"
    private var watchdogJob: Job? = null
    private var startRetryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBootstrap()
            ACTION_START_GUIDE -> startBootstrap()
            ACTION_START_CORE_NATIVE -> startCoreNative()
            ACTION_START_CORE_ROOT_DIRECT -> startCoreRootDirect()
            ACTION_STOP_CORE_NATIVE -> stopCoreNative()
            ACTION_STOP_CORE_UNIFIED -> stopCoreUnified()
            ACTION_STOP -> stopBootstrap()
            ACTION_OPEN_DEV_OPTIONS -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            ACTION_OPEN_WIRELESS_DEBUGGING -> openWirelessDebuggingSettings()
            ACTION_SUBMIT_PAIRING -> {
                val pairCode = readPairCodeFromIntent(intent)
                submitPairing(pairCode)
            }
            else -> {
                if (!running) {
                    startBootstrap()
                } else {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDiscovery()
        watchdogJob?.cancel()
        startRetryJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startBootstrap() {
        if (!running) {
            running = true
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, SERVICE_TYPE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        setState("GUIDE_SETTINGS", "Open Developer Options and enable Wireless debugging.")
        startDiscovery()
        setState(
            "WAIT_INPUT",
            "Waiting pairing code input from notification. Pair/connect endpoint auto-detected."
        )
        updateNotification()
    }

    private fun ensureForegroundRunning() {
        if (running) {
            updateNotification()
            return
        }
        running = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, SERVICE_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCoreNative() {
        ensureForegroundRunning()
        startRetryJob?.cancel()
        setState("STARTING_CORE", "Starting core via saved wireless endpoint...")
        updateNotification()
        scope.launch {
            val res = relaunchCoreViaSavedEndpoint()
            if (res.ok) {
                val port = getConfiguredPort()
                finishBootstrap("RUNNING", "Native start succeeded. lxb-core is listening on port $port.")
            } else {
                setState(
                    "WAIT_WIRELESS_DEBUGGING",
                    "Native start failed. Auto retrying with saved/discovered endpoint..."
                )
                updateNotification()
                scheduleStartRetry()
            }
        }
    }

    private fun startCoreRootDirect() {
        ensureForegroundRunning()
        startRetryJob?.cancel()
        setState("STARTING_CORE_ROOT", "Starting core via root...")
        updateNotification()
        scope.launch {
            val port = getConfiguredPort()
            val res = launchCoreWithRootVerification(port)
            if (res.ok) {
                finishBootstrap("RUNNING", "Root start succeeded. lxb-core is listening on port $port.")
            } else {
                finishBootstrap("FAILED", "Root start failed: ${res.detail}")
            }
        }
    }

    private fun stopCoreNative() {
        ensureForegroundRunning()
        startRetryJob?.cancel()
        setState("STOPPING", "Stopping core process via saved wireless endpoint...")
        updateNotification()
        scope.launch {
            val localReachableBefore = isCoreReachableLocal()
            val okByEndpoint = stopCoreViaSavedEndpoint()
            val ok = okByEndpoint
            if (ok) {
                stopBootstrap()
            } else {
                running = false
                watchdogJob?.cancel()
                val localReachableAfter = isCoreReachableLocal()
                setState(
                    "IDLE",
                    "Stop failed endpoint=$okByEndpoint reach_before=$localReachableBefore reach_after=$localReachableAfter."
                )
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun stopCoreUnified() {
        ensureForegroundRunning()
        startRetryJob?.cancel()
        setState("STOPPING", "Stopping core process (unified)...")
        updateNotification()
        scope.launch {
            val details = ArrayList<String>(3)
            val port = getConfiguredPort()
            var stopped = false

            val rootStop = stopCoreViaRootDirect()
            details.add("root=${rootStop.second}")
            if (rootStop.first) {
                stopped = waitLocalPortClosed(port, 3500L, 200L) || !isCoreReachableLocal()
            }

            if (!stopped) {
                val endpointStop = stopCoreViaSavedEndpoint()
                details.add("endpoint=$endpointStop")
                if (endpointStop) {
                    stopped = waitLocalPortClosed(port, 3500L, 200L) || !isCoreReachableLocal()
                }
            }

            if (stopped || !isCoreReachableLocal()) {
                stopBootstrap()
            } else {
                running = false
                watchdogJob?.cancel()
                setState("IDLE", "Unified stop failed: ${details.joinToString("; ")}")
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun stopCoreViaRootDirect(): Pair<Boolean, String> {
        val starterStop = runStarterStopViaRoot()
        if (starterStop.ok) {
            return Pair(true, starterStop.detail)
        }
        val fallbackCmd = "for p in ${'$'}(ps -A | grep -E 'lxb_core|com\\.lxb\\.server\\.Main' | grep -v grep | awk '{print ${'$'}2}'); do kill -9 ${'$'}p; done; true"
        val fallback = runRootShellCommand(
            fallbackCmd,
            8000
        )
        return if (fallback.ok) {
            Pair(true, "fallback_kill_ok: ${fallback.shortOutput()}")
        } else {
            Pair(false, "starter=${starterStop.detail}; fallback=${fallback.shortOutput()}")
        }
    }

    private fun stopBootstrap() {
        stopDiscovery()
        watchdogJob?.cancel()
        startRetryJob?.cancel()
        running = false
        setState("IDLE", "Bootstrap stopped.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleStartRetry() {
        startRetryJob?.cancel()
        startRetryJob = scope.launch {
            var promptedSettings = false
            for (attempt in 1..START_RETRY_MAX_ATTEMPTS) {
                if (!running) return@launch
                delay(START_RETRY_INTERVAL_MS)
                if (!promptedSettings && attempt >= 4) {
                    openWirelessDebuggingSettings()
                    promptedSettings = true
                }
                setState(
                    "WAIT_WIRELESS_DEBUGGING",
                    if (promptedSettings) {
                        "Waiting Wireless debugging... retry $attempt/$START_RETRY_MAX_ATTEMPTS"
                    } else {
                        "Auto reconnecting... retry $attempt/$START_RETRY_MAX_ATTEMPTS"
                    }
                )
                updateNotification()
                val res = relaunchCoreViaSavedEndpoint()
                if (res.ok) {
                    val port = getConfiguredPort()
                    finishBootstrap("RUNNING", "Native start succeeded after retry. lxb-core is listening on port $port.")
                    return@launch
                }
            }
            finishBootstrap(
                "FAILED",
                "Native start failed after retries. Please enable Wireless debugging and tap Start again."
            )
        }
    }

    private fun submitPairing(pairCodeRaw: String) {
        val pairCode = normalizePairCode(pairCodeRaw)
        if (pairCode.isEmpty()) {
            setState("WAIT_INPUT", "Invalid input. Please provide pairing code.")
            updateNotification()
            return
        }

        val pairing = latestPairingEndpoint
        if (pairing == null) {
            setState("WAIT_INPUT", "Pairing endpoint not detected yet. Keep Wireless debugging pairing page open and retry.")
            updateNotification()
            return
        }
        val connect = latestConnectEndpoint

        scope.launch {
            setState("PAIRING", "Checking pairing endpoint reachability...")
            updateNotification()

            val reachable = checkTcpReachable(pairing.host, pairing.port, 3000)
            if (!reachable) {
                finishBootstrap("FAILED", "Pairing endpoint not reachable: ${pairing.host}:${pairing.port}")
                return@launch
            }
            val tlsPrep = prepareConscryptProvider()
            setState("PAIRING", "TLS provider prepared: $tlsPrep")
            updateNotification()
            val manager = WirelessAdbConnectionManager(applicationContext)
            manager.setTimeout(12, TimeUnit.SECONDS)
            manager.setThrowOnUnauthorised(true)

            try {
                manager.setHostAddress(pairing.host)
                val pairOk = runCatching { manager.pair(pairing.port, pairCode) }.getOrElse { e ->
                    val reason = e.message.orEmpty()
                    // Rotate key material once for TLS/RSA provider errors.
                    if (isLikelyTlsRsaError(reason)) {
                        runCatching { manager.rotateKeyMaterial() }
                        val retryWithNewKey = runCatching { manager.pair(pairing.port, pairCode) }.getOrElse { e3 ->
                            finishBootstrap("FAILED", "ADB pair failed (TLS/RSA): ${formatExceptionChain(e3)}. Reopen pairing dialog and retry.")
                            return@launch
                        }
                        if (retryWithNewKey) {
                            return@getOrElse true
                        }
                    }
                    // One retry after refreshing discovery can recover stale pairing endpoint.
                    startDiscovery()
                    Thread.sleep(500L)
                    val retryEndpoint = latestPairingEndpoint
                    if (retryEndpoint != null) {
                        manager.setHostAddress(retryEndpoint.host)
                        return@getOrElse runCatching { manager.pair(retryEndpoint.port, pairCode) }.getOrElse { e2 ->
                            finishBootstrap("FAILED", "ADB pair failed (TLS/protocol): ${formatExceptionChain(e2)}. Reopen \"Pair device with pairing code\" and try again.")
                            return@launch
                        }
                    }
                    finishBootstrap("FAILED", "ADB pair failed (TLS/protocol): ${formatExceptionChain(e)}. Reopen \"Pair device with pairing code\" and try again.")
                    return@launch
                }
                if (!pairOk) {
                    finishBootstrap("FAILED", "ADB pair returned false. Reopen pairing dialog and retry.")
                    return@launch
                }

                startDiscovery()
                val connectReady = waitForConnectEndpoint(CONNECT_ENDPOINT_WAIT_MS)
                val persisted = connectReady ?: connect ?: Endpoint(pairing.host, 5555)
                persistWirelessEndpoint(persisted)

                setState(
                    "PAIRED",
                    "Pairing succeeded. Return to the app and tap \"I already paired before, start directly\"."
                )
                updateNotification()
            } catch (e: AdbPairingRequiredException) {
                finishBootstrap("FAILED", "ADB reports pairing required: ${formatExceptionChain(e)}")
            } catch (e: Exception) {
                finishBootstrap("FAILED", "Wireless bootstrap failed: ${formatExceptionChain(e)}")
            } finally {
                runCatching { manager.close() }
            }
        }
    }

    private fun checkTcpReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun isLikelyTlsRsaError(msg: String): Boolean {
        val s = msg.lowercase()
        return s.contains("failure in ssl library")
                || s.contains("protocol error")
                || s.contains("rsa")
                || s.contains("conscrypt")
                || s.contains("openssl_internal")
                || s.contains("exportkeyingmaterial")
                || s.contains("nosuchmethodexception")
    }

    private fun prepareConscryptProvider(): String {
        return runCatching {
            val clazz = Class.forName("org.conscrypt.OpenSSLProvider")
            val provider = clazz.getDeclaredConstructor().newInstance() as java.security.Provider
            if (Security.getProvider(provider.name) == null) {
                Security.insertProviderAt(provider, 1)
            }
            provider.name
        }.getOrElse { e ->
            "unavailable(${e.javaClass.simpleName}:${e.message})"
        }
    }

    private fun formatExceptionChain(e: Throwable): String {
        val parts = ArrayList<String>(4)
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4) {
            val msg = cur.message?.trim().orEmpty()
            if (msg.isNotEmpty()) {
                parts.add("${cur.javaClass.simpleName}: $msg")
            } else {
                parts.add(cur.javaClass.simpleName)
            }
            cur = cur.cause
            depth++
        }
        return if (parts.isEmpty()) "unknown error" else parts.joinToString(" <- ")
    }

    private fun readPairCodeFromIntent(intent: Intent): String {
        val fromRemote = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_PAIR_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (fromRemote.isNotEmpty()) return fromRemote
        return intent.getStringExtra(EXTRA_PAIR_CODE).orEmpty()
    }

    private fun openSettings(action: String): Boolean {
        return runCatching {
            val i = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            true
        }.getOrDefault(false)
    }

    private fun openWirelessDebuggingSettings(): Boolean {
        val direct = openSettings(ACTION_WIRELESS_DEBUGGING_SETTINGS)
        if (direct) return true
        return openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    private fun startDiscovery() {
        stopDiscovery()
        val mgr = nsdManager ?: return

        pairingResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // no-op
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress.orEmpty()
                val port = serviceInfo.port
                if (host.isNotBlank() && port in 1..65535) {
                    latestPairingEndpoint = Endpoint(host, port)
                    if (currentState == "WAIT_INPUT") {
                        setState("WAIT_INPUT", "Detected pairing endpoint: ${latestPairingEndpoint?.asText().orEmpty()}")
                        updateNotification()
                    }
                }
            }
        }
        connectResolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // no-op
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress.orEmpty()
                val port = serviceInfo.port
                if (host.isNotBlank() && port in 1..65535) {
                    latestConnectEndpoint = Endpoint(host, port)
                    if (currentState == "WAIT_INPUT") {
                        setState("WAIT_INPUT", "Detected connect endpoint: ${latestConnectEndpoint?.asText().orEmpty()}")
                        updateNotification()
                    }
                }
            }
        }

        pairingDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                setState("WAIT_INPUT", "NSD discovery start failed: $errorCode")
                updateNotification()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // no-op
            }

            override fun onDiscoveryStarted(serviceType: String) {
                // no-op
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // no-op
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val rs = pairingResolveListener ?: return
                runCatching { mgr.resolveService(serviceInfo, rs) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // no-op
            }
        }

        runCatching {
            mgr.discoverServices(
                PAIRING_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                pairingDiscoveryListener
            )
        }.onFailure {
            setState("WAIT_INPUT", "NSD discovery unavailable: ${it.message}")
            updateNotification()
        }

        connectDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // no-op
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // no-op
            }

            override fun onDiscoveryStarted(serviceType: String) {
                // no-op
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // no-op
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val rs = connectResolveListener ?: return
                runCatching { mgr.resolveService(serviceInfo, rs) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // no-op
            }
        }

        runCatching {
            mgr.discoverServices(
                CONNECT_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                connectDiscoveryListener
            )
        }
    }

    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        pairingDiscoveryListener?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        connectDiscoveryListener?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        pairingDiscoveryListener = null
        connectDiscoveryListener = null
        pairingResolveListener = null
        connectResolveListener = null
    }

    private fun setState(state: String, message: String) {
        currentState = state
        currentMessage = message
        val broadcast = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, currentState)
            putExtra(EXTRA_MESSAGE, currentMessage)
            putExtra(EXTRA_RUNNING, running)
        }
        sendBroadcast(broadcast)
    }

    private fun finishBootstrap(state: String, message: String) {
        setState(state, message)
        if (state == "RUNNING") {
            running = true
            startWatchdog()
            updateNotification()
            return
        }
        running = false
        watchdogJob?.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive && running) {
                delay(WATCHDOG_INTERVAL_MS)
                if (currentState != "RUNNING" && currentState != "RECONNECTING") {
                    continue
                }
                val alive = isCoreReachableLocal()
                if (alive) continue

                setState("RECONNECTING", "Core not reachable, trying wireless ADB relaunch...")
                updateNotification()
                val relaunched = relaunchCoreViaSavedEndpoint()
                if (relaunched.ok) {
                    setState("RUNNING", "Wireless ADB keepalive recovered core process.")
                } else {
                    setState("RECONNECTING", "Reconnect failed: ${relaunched.detail}")
                }
                updateNotification()
            }
        }
    }

    private fun getConfiguredPort(): Int {
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LXB_PORT, DEFAULT_LXB_PORT.toString())
            ?.trim()
            ?.toIntOrNull()
            ?: DEFAULT_LXB_PORT
        return if (p in 1..65535) p else DEFAULT_LXB_PORT
    }

    private fun persistWirelessEndpoint(endpoint: Endpoint) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WIRELESS_ADB_HOST, endpoint.host)
            .putInt(KEY_WIRELESS_ADB_PORT, endpoint.port)
            .apply()
    }

    private fun loadWirelessEndpoint(): Endpoint? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_WIRELESS_ADB_HOST, "")?.trim().orEmpty()
        val port = prefs.getInt(KEY_WIRELESS_ADB_PORT, 0)
        if (host.isBlank() || port !in 1..65535) return null
        return Endpoint(host, port)
    }

    private fun isCoreReachableLocal(): Boolean {
        val port = getConfiguredPort()
        return checkTcpReachable("127.0.0.1", port, 1200)
    }

    private fun waitLocalPortReady(port: Int, timeoutMs: Long, intervalMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (checkTcpReachable("127.0.0.1", port, 1000)) {
                return true
            }
            Thread.sleep(intervalMs)
        }
        return checkTcpReachable("127.0.0.1", port, 1200)
    }

    private fun waitLocalPortClosed(port: Int, timeoutMs: Long, intervalMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!checkTcpReachable("127.0.0.1", port, 900)) {
                return true
            }
            Thread.sleep(intervalMs)
        }
        return !checkTcpReachable("127.0.0.1", port, 1000)
    }

    private fun relaunchCoreViaSavedEndpoint(): RelaunchResult {
        val ep = loadWirelessEndpoint()
        startDiscovery()
        val connectDiscovered = waitForConnectEndpoint(CONNECT_ENDPOINT_WAIT_MS)
        val pairingDiscovered = latestPairingEndpoint

        val endpointCandidates = linkedSetOf<Endpoint>()
        if (ep != null) endpointCandidates.add(ep)
        if (connectDiscovered != null) endpointCandidates.add(connectDiscovered)
        if (pairingDiscovered != null) endpointCandidates.add(Endpoint(pairingDiscovered.host, 5555))
        if (endpointCandidates.isEmpty()) {
            return RelaunchResult(
                false,
                "no endpoint available. keep wireless debugging on, ensure same Wi-Fi, then retry."
            )
        }

        val manager = WirelessAdbConnectionManager(applicationContext)
        manager.setTimeout(10, TimeUnit.SECONDS)
        manager.setThrowOnUnauthorised(true)
        return try {
            val connectErrors = ArrayList<String>(4)
            for (candidate in endpointCandidates) {
                manager.setHostAddress(candidate.host)
                val direct = runCatching { manager.connect(candidate.host, candidate.port) }.getOrDefault(false)
                val auto = if (!direct) {
                    runCatching { manager.autoConnect(applicationContext, 5000) }.getOrDefault(false)
                } else {
                    false
                }
                val connected = direct || auto || manager.isConnected
                if (!connected) {
                    connectErrors.add("${candidate.asText()}(connect=false)")
                    continue
                }

                persistWirelessEndpoint(candidate)
                val launch = launchCoreWithVerification(manager, getConfiguredPort())
                if (launch.ok) {
                    return RelaunchResult(true, "connected=${candidate.asText()}")
                }
                connectErrors.add("${candidate.asText()}(launch=${launch.detail})")
            }
            RelaunchResult(
                false,
                "cannot connect/launch via endpoints=${connectErrors.joinToString("; ")}"
            )
        } catch (e: Exception) {
            RelaunchResult(false, "exception=${formatExceptionChain(e)}")
        } finally {
            runCatching { manager.close() }
        }
    }

    private fun waitForConnectEndpoint(timeoutMs: Long): Endpoint? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val ep = latestConnectEndpoint
            if (ep != null) return ep
            Thread.sleep(100L)
        }
        return latestConnectEndpoint
    }

    private fun launchCoreWithVerification(
        manager: WirelessAdbConnectionManager,
        port: Int
    ): RelaunchResult {
        val starterAsset = resolveStarterAssetForDevice()
            ?: return RelaunchResult(false, unsupportedAbiDetail())
        val deploy = deployCoreJarViaShell(manager)
        if (!deploy.ok) {
            return RelaunchResult(false, "deploy_failed=${deploy.detail}")
        }
        val starterDeploy = deployStarterBinaryViaShell(manager, starterAsset)
        if (!starterDeploy.ok) {
            return RelaunchResult(
                false,
                "starter_deploy_failed abi=${starterAsset.abi} asset=${starterAsset.assetName} detail=${starterDeploy.detail}"
            )
        }
        val appLabelsDeploy = deployAppLabelsSnapshotViaShell(manager)
        val appLabelsNote = if (appLabelsDeploy.ok) {
            "labels_ok"
        } else {
            "labels_warn=${appLabelsDeploy.detail}"
        }
        val errors = ArrayList<String>(3)
        for (attempt in 1..2) {
            runStarterStop(manager)
            waitLocalPortClosed(port, 2500L, 200L)
            Thread.sleep(350L)
            val launch = runStarterStart(manager, port)
            if (waitLocalPortReady(port, 3500L, 200L)) {
                return RelaunchResult(true, "ok;$appLabelsNote")
            }
            if (!launch.ok) {
                errors.add("a$attempt:starter_start_failed ${launch.detail}")
                continue
            }
            if (waitLocalPortReady(port, 7000L, 250L)) {
                return RelaunchResult(true, "ok;$appLabelsNote")
            }
            val tail = runShellCommand(manager, "tail -n 40 $REMOTE_LOG_PATH", 8000)
            val ps = runShellCommand(
                manager,
                "ps -A | grep -E 'lxb_core|com\\.lxb\\.server\\.Main|app_process' | grep -v grep | head -n 12 || true",
                8000
            )
            errors.add(
                "a$attempt:port_not_ready starter=${launch.detail} ps=${ps.shortOutput()} log=${tail.shortOutput()}"
            )
        }
        return RelaunchResult(false, errors.joinToString(" | "))
    }

    private fun stopCoreViaSavedEndpoint(): Boolean {
        val ep = loadWirelessEndpoint() ?: return false
        val manager = WirelessAdbConnectionManager(applicationContext)
        manager.setTimeout(8, TimeUnit.SECONDS)
        manager.setThrowOnUnauthorised(true)
        return try {
            manager.setHostAddress(ep.host)
            val connected = runCatching { manager.connect(ep.host, ep.port) }.getOrDefault(false)
                || runCatching { manager.autoConnect(applicationContext, 4000) }.getOrDefault(false)
            if (!connected || !manager.isConnected) {
                return false
            }
            val kill = runShellCommand(
                manager,
                buildStarterCommand("stop", getConfiguredPort()),
                12000
            )
            val stopResult = parseStarterResult(kill)
            val closed = waitLocalPortClosed(getConfiguredPort(), 5000L, 250L)
            if (closed) {
                return true
            }
            if (!stopResult.ok) {
                return false
            }
            waitLocalPortClosed(getConfiguredPort(), 2500L, 250L)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { manager.close() }
        }
    }

    private fun normalizePairCode(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val digits = buildString {
            for (c in t) {
                if (c.isDigit()) append(c)
            }
        }
        // Most devices use 6-digit code; fallback to trimmed raw if model uses non-digit format.
        return if (digits.length >= 6) digits else t
    }

    private data class ShellExecResult(
        val ok: Boolean,
        val exitCode: Int,
        val output: String
    ) {
        fun shortOutput(): String {
            val s = output.replace('\n', ' ').replace('\r', ' ').trim()
            return if (s.length <= 180) s else s.substring(0, 180) + "..."
        }
    }

    private fun runShellCommand(
        manager: WirelessAdbConnectionManager,
        command: String,
        timeoutMs: Long
    ): ShellExecResult {
        val marker = "__LXB_RC_${System.currentTimeMillis()}__"
        val full = buildString {
            append(command.trim())
            append('\n')
            append("rc=\$?\n")
            append("echo ${marker}\$rc")
        }
        return runCatching {
            val stream = manager.openStream("shell:$full")
            val input = stream.openInputStream()
            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                val avail = runCatching { input.available() }.getOrDefault(0)
                if (avail > 0) {
                    val n = input.read(buf, 0, min(avail, buf.size))
                    if (n > 0) {
                        sb.append(String(buf, 0, n))
                        if (sb.indexOf(marker) >= 0) {
                            break
                        }
                    }
                } else {
                    if (stream.isClosed) break
                    Thread.sleep(30L)
                }
            }
            runCatching { input.close() }
            runCatching { stream.close() }

            val text = sb.toString()
            val idx = text.lastIndexOf(marker)
            if (idx < 0) {
                return@runCatching ShellExecResult(false, -1, "timeout/no-exit-marker: $text")
            }
            val out = text.substring(0, idx).trim()
            var code = -1
            val tail = text.substring(idx + marker.length).trim()
            val digits = tail.takeWhile { it.isDigit() }
            if (digits.isNotEmpty()) {
                code = digits.toIntOrNull() ?: -1
            }
            ShellExecResult(code == 0, code, out)
        }.getOrElse { e ->
            ShellExecResult(false, -1, e.message ?: "shell exec error")
        }
    }

    private fun runStarterStart(
        manager: WirelessAdbConnectionManager,
        port: Int
    ): StarterResult {
        val cmd = buildStarterCommand("start", port)
        val res = runShellCommand(manager, cmd, 15000)
        return parseStarterResult(res)
    }

    private fun runStarterStop(
        manager: WirelessAdbConnectionManager
    ): StarterResult {
        val cmd = buildStarterCommand("stop", getConfiguredPort())
        val res = runShellCommand(manager, cmd, 12000)
        return parseStarterResult(res)
    }

    private fun parseStarterResult(res: ShellExecResult): StarterResult {
        val out = res.output.trim()
        if (out.contains("RESULT:OK")) {
            return StarterResult(true, out)
        }
        if (out.contains("RESULT:ERR")) {
            return StarterResult(false, out)
        }
        if (!res.ok) {
            return StarterResult(false, "shell_fail(${res.exitCode}): ${res.shortOutput()}")
        }
        return StarterResult(false, "unexpected_output: ${res.shortOutput()}")
    }

    private fun buildStarterCommand(action: String, port: Int): String {
        if ("stop".equals(action, ignoreCase = true)) {
            return buildString {
                append(shellQuote(REMOTE_STARTER_PATH))
                append(" --action stop --process ")
                append(shellQuote(CORE_NICE_NAME))
            }
        }
        val mapDir = AppStatePaths.getMapDir(applicationContext).absolutePath
        val llmConfigPath = AppStatePaths.getLlmConfigPath(applicationContext)
        val taskMemoryPath = AppStatePaths.getTaskMemoryPath(applicationContext)
        return buildString {
            append(shellQuote(REMOTE_STARTER_PATH))
            append(" --action start")
            append(" --jar ")
            append(shellQuote(REMOTE_JAR_PATH))
            append(" --main ")
            append(shellQuote(CORE_CLASS))
            append(" --process ")
            append(shellQuote(CORE_NICE_NAME))
            append(" --port ")
            append(port)
            append(" --log ")
            append(shellQuote(REMOTE_LOG_PATH))
            append(" --map-dir ")
            append(shellQuote(mapDir))
            append(" --llm-config ")
            append(shellQuote(llmConfigPath))
            append(" --task-memory ")
            append(shellQuote(taskMemoryPath))
            append(" --app-labels ")
            append(shellQuote(REMOTE_APP_LABELS_PATH))
        }
    }

    private data class DeployResult(
        val ok: Boolean,
        val detail: String
    )

    private fun deployCoreJarViaShell(manager: WirelessAdbConnectionManager): DeployResult {
        return deployAssetToRemote(
            manager = manager,
            assetName = "lxb-core-dex.jar",
            remotePath = REMOTE_JAR_PATH,
            executable = false
        )
    }

    private fun deployStarterBinaryViaShell(
        manager: WirelessAdbConnectionManager,
        starterAsset: StarterAsset
    ): DeployResult {
        return deployAssetToRemote(
            manager = manager,
            assetName = starterAsset.assetName,
            remotePath = REMOTE_STARTER_PATH,
            executable = true
        )
    }

    private fun deployAppLabelsSnapshotViaShell(manager: WirelessAdbConnectionManager): DeployResult {
        val labelsBytes = runCatching { buildAppLabelsTsv() }.getOrElse { e ->
            return DeployResult(false, "build_app_labels_failed: ${e.message}")
        }
        if (labelsBytes.isEmpty()) {
            return DeployResult(false, "build_app_labels_empty")
        }
        return deployBytesToRemote(
            manager = manager,
            bytes = labelsBytes,
            remotePath = REMOTE_APP_LABELS_PATH,
            executable = false
        )
    }

    private fun deployAssetToRemote(
        manager: WirelessAdbConnectionManager,
        assetName: String,
        remotePath: String,
        executable: Boolean
    ): DeployResult {
        val bytes = runCatching { assets.open(assetName).use { it.readBytes() } }.getOrElse { e ->
            return DeployResult(false, "asset_open_failed($assetName): ${e.message}")
        }
        if (bytes.isEmpty()) {
            return DeployResult(false, "asset_empty($assetName)")
        }
        return deployBytesToRemote(
            manager = manager,
            bytes = bytes,
            remotePath = remotePath,
            executable = executable
        )
    }

    private fun deployBytesToRemote(
        manager: WirelessAdbConnectionManager,
        bytes: ByteArray,
        remotePath: String,
        executable: Boolean
    ): DeployResult {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val init = runShellCommand(manager, "rm -f ${remotePath}.b64 $remotePath", 7000)
        if (!init.ok) return DeployResult(false, "init failed: ${init.shortOutput()}")

        val chunk = 3500
        var i = 0
        var chunkIndex = 0
        while (i < b64.length) {
            val end = min(i + chunk, b64.length)
            val part = b64.substring(i, end)
            val append = runShellCommand(manager, "echo '$part' >> ${remotePath}.b64", 7000)
            if (!append.ok) return DeployResult(false, "append failed at chunk=$chunkIndex: ${append.shortOutput()}")
            i = end
            chunkIndex++
        }

        val decodeCmd = buildString {
            append("(base64 -d ${remotePath}.b64 > $remotePath || toybox base64 -d ${remotePath}.b64 > $remotePath)")
            if (executable) {
                append(" && chmod 700 $remotePath")
            }
            append(" && rm -f ${remotePath}.b64 && ls -l $remotePath")
        }
        val decode = runShellCommand(manager, decodeCmd, 15000)
        if (!decode.ok) return DeployResult(false, "decode failed: ${decode.shortOutput()}")
        return DeployResult(true, decode.shortOutput())
    }

    private fun launchCoreWithRootVerification(port: Int): RelaunchResult {
        val starterAsset = resolveStarterAssetForDevice()
            ?: return RelaunchResult(false, unsupportedAbiDetail())
        val deploy = deployCoreJarViaRoot()
        if (!deploy.ok) {
            return RelaunchResult(false, "deploy_failed=${deploy.detail}")
        }
        val starterDeploy = deployStarterBinaryViaRoot(starterAsset)
        if (!starterDeploy.ok) {
            return RelaunchResult(
                false,
                "starter_deploy_failed abi=${starterAsset.abi} asset=${starterAsset.assetName} detail=${starterDeploy.detail}"
            )
        }
        val appLabelsDeploy = deployAppLabelsSnapshotViaRoot()
        val appLabelsNote = if (appLabelsDeploy.ok) {
            "labels_ok"
        } else {
            "labels_warn=${appLabelsDeploy.detail}"
        }
        val errors = ArrayList<String>(3)
        for (attempt in 1..2) {
            runStarterStopViaRoot()
            waitLocalPortClosed(port, 2500L, 200L)
            Thread.sleep(350L)
            val launch = runStarterStartViaRoot(port)
            if (waitLocalPortReady(port, 3500L, 200L)) {
                return RelaunchResult(true, "ok;$appLabelsNote")
            }
            if (!launch.ok) {
                errors.add("a$attempt:starter_start_failed ${launch.detail}")
                continue
            }
            if (waitLocalPortReady(port, 7000L, 250L)) {
                return RelaunchResult(true, "ok;$appLabelsNote")
            }
            val tail = runRootShellCommand("tail -n 40 $REMOTE_LOG_PATH", 8000)
            val ps = runRootShellCommand(
                "ps -A | grep -E 'lxb_core|com\\.lxb\\.server\\.Main|app_process' | grep -v grep | head -n 12 || true",
                8000
            )
            errors.add(
                "a$attempt:port_not_ready starter=${launch.detail} ps=${ps.shortOutput()} log=${tail.shortOutput()}"
            )
        }
        return RelaunchResult(false, errors.joinToString(" | "))
    }

    private fun runStarterStartViaRoot(port: Int): StarterResult {
        val cmd = buildStarterCommand("start", port)
        val res = runRootShellCommand(cmd, 15000)
        return parseStarterResult(res)
    }

    private fun runStarterStopViaRoot(): StarterResult {
        val cmd = buildStarterCommand("stop", getConfiguredPort())
        val res = runRootShellCommand(cmd, 12000)
        return parseStarterResult(res)
    }

    private fun deployCoreJarViaRoot(): DeployResult {
        return deployAssetToRemoteViaRoot(
            assetName = "lxb-core-dex.jar",
            remotePath = REMOTE_JAR_PATH,
            executable = false
        )
    }

    private fun deployStarterBinaryViaRoot(starterAsset: StarterAsset): DeployResult {
        return deployAssetToRemoteViaRoot(
            assetName = starterAsset.assetName,
            remotePath = REMOTE_STARTER_PATH,
            executable = true
        )
    }

    private fun deployAppLabelsSnapshotViaRoot(): DeployResult {
        val labelsBytes = runCatching { buildAppLabelsTsv() }.getOrElse { e ->
            return DeployResult(false, "build_app_labels_failed: ${e.message}")
        }
        if (labelsBytes.isEmpty()) {
            return DeployResult(false, "build_app_labels_empty")
        }
        return deployBytesToRemoteViaRoot(
            bytes = labelsBytes,
            remotePath = REMOTE_APP_LABELS_PATH,
            executable = false
        )
    }

    private fun deployAssetToRemoteViaRoot(
        assetName: String,
        remotePath: String,
        executable: Boolean
    ): DeployResult {
        val bytes = runCatching { assets.open(assetName).use { it.readBytes() } }.getOrElse { e ->
            return DeployResult(false, "asset_open_failed($assetName): ${e.message}")
        }
        if (bytes.isEmpty()) {
            return DeployResult(false, "asset_empty($assetName)")
        }
        return deployBytesToRemoteViaRoot(
            bytes = bytes,
            remotePath = remotePath,
            executable = executable
        )
    }

    private fun deployBytesToRemoteViaRoot(
        bytes: ByteArray,
        remotePath: String,
        executable: Boolean
    ): DeployResult {
        val init = runRootShellCommand("rm -f ${remotePath}.b64 $remotePath", 7000)
        if (!init.ok) return DeployResult(false, "init failed: ${init.shortOutput()}")

        val write = writeBytesViaRoot(bytes, remotePath, 20_000)
        if (!write.ok) return write

        val chmodMode = if (executable) "700" else "600"
        val finalCmd = "chmod $chmodMode $remotePath && ls -l $remotePath"
        val chmod = runRootShellCommand(finalCmd, 8000)
        if (!chmod.ok) return DeployResult(false, "chmod failed: ${chmod.shortOutput()}")
        return DeployResult(true, chmod.shortOutput())
    }

    private fun writeBytesViaRoot(
        bytes: ByteArray,
        remotePath: String,
        timeoutMs: Long
    ): DeployResult {
        return runCatching {
            val process = ProcessBuilder(
                "su",
                "-c",
                "cat > ${shellQuote(remotePath)}"
            )
                .redirectErrorStream(true)
                .start()

            process.outputStream.use { os ->
                os.write(bytes)
                os.flush()
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching DeployResult(false, "root_write_timeout")
            }
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            val code = process.exitValue()
            if (code != 0) {
                return@runCatching DeployResult(
                    false,
                    "root_write_fail($code): ${
                        output.replace('\n', ' ').replace('\r', ' ').trim().take(180)
                    }"
                )
            }
            DeployResult(true, "root_write_ok")
        }.getOrElse { e ->
            DeployResult(false, "root_write_exception: ${e.message}")
        }
    }

    private fun runRootShellCommand(command: String, timeoutMs: Long): ShellExecResult {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching ShellExecResult(false, -1, "timeout")
            }
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            val code = process.exitValue()
            ShellExecResult(code == 0, code, output.trim())
        }.getOrElse { e ->
            ShellExecResult(false, -1, e.message ?: "root shell exec error")
        }
    }

    private fun buildAppLabelsTsv(): ByteArray {
        val pm = packageManager
        val labelsByPkg = linkedMapOf<String, String>()
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        for (app in apps) {
            val pkg = app.packageName?.trim().orEmpty()
            if (pkg.isEmpty()) continue
            val label = sanitizeLabel(
                runCatching { pm.getApplicationLabel(app)?.toString() ?: "" }.getOrDefault("")
            )
            if (label.isNotEmpty()) {
                labelsByPkg[pkg] = label
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherActivities = runCatching { pm.queryIntentActivities(launcherIntent, 0) }
            .getOrDefault(emptyList())
        for (ri in launcherActivities) {
            val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isEmpty()) continue
            if (labelsByPkg.containsKey(pkg)) continue
            val label = sanitizeLabel(runCatching { ri.loadLabel(pm)?.toString() ?: "" }.getOrDefault(""))
            if (label.isNotEmpty()) {
                labelsByPkg[pkg] = label
            }
        }

        val sb = StringBuilder()
        for ((pkg, label) in labelsByPkg) {
            sb.append(pkg).append('\t').append(label).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun sanitizeLabel(raw: String): String {
        return raw
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
    }

    private fun shellQuote(v: String): String {
        return "'" + v.replace("'", "'\\''") + "'"
    }

    private fun resolveStarterAssetForDevice(): StarterAsset? {
        for (abi in Build.SUPPORTED_ABIS) {
            val assetName = STARTER_ASSET_BY_ABI[abi] ?: continue
            return StarterAsset(abi = abi, assetName = assetName)
        }
        return null
    }

    private fun unsupportedAbiDetail(): String {
        val deviceAbis = Build.SUPPORTED_ABIS.joinToString(",")
        val supportedAbis = STARTER_ASSET_BY_ABI.keys.joinToString(",")
        return "unsupported_abi device=[$deviceAbis] supported=[$supportedAbis]"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val uiLang = currentUiLang()
        val channel = NotificationChannel(
            CHANNEL_ID,
            nt("LXB Wireless Bootstrap", "LXB 无线启动", uiLang),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = nt("Wireless ADB startup status", "无线 ADB 启动状态", uiLang)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val n = buildNotification()
        if (running) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        createChannel()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val submitPending = PendingIntent.getService(
            this,
            1,
            Intent(this, WirelessAdbBootstrapService::class.java).setAction(ACTION_SUBMIT_PAIRING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val uiLang = currentUiLang()
        val codeInput = RemoteInput.Builder(REMOTE_INPUT_PAIR_CODE)
            .setLabel(nt("6-digit pairing code", "6 位配对码", uiLang))
            .build()

        val submitAction = NotificationCompat.Action.Builder(
            0,
            nt("Submit code", "提交配对码", uiLang),
            submitPending
        )
            .addRemoteInput(codeInput)
            .setAllowGeneratedReplies(false)
            .build()

        val content = buildNotificationContent(uiLang)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(buildNotificationTitle(uiLang))
            .setContentText(content.lines().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPending)
            .addAction(submitAction)
            .build()
    }

    private fun buildNotificationTitle(uiLang: String): String {
        return when (currentState) {
            "RUNNING" -> nt("Core service is running", "核心服务已运行", uiLang)
            "PAIRED" -> nt("Phone paired successfully", "手机已配对成功", uiLang)
            "FAILED" -> nt("Wireless startup failed", "无线启动失败", uiLang)
            "PAIRING" -> nt("Pairing phone", "正在配对手机", uiLang)
            "CONNECTING" -> nt("Connecting phone", "正在连接手机", uiLang)
            "STARTING_CORE" -> nt("Starting core service", "正在启动核心服务", uiLang)
            "RECONNECTING" -> nt("Recovering connection", "正在恢复连接", uiLang)
            "STOPPING" -> nt("Stopping core service", "正在停止核心服务", uiLang)
            else -> nt("Wireless startup guide", "无线启动引导", uiLang)
        }
    }

    private fun buildNotificationContent(uiLang: String): String {
        val lines = ArrayList<String>(4)
        val main = when (currentState) {
            "GUIDE_SETTINGS" -> nt(
                "Open Developer Options, then turn on Wireless debugging.",
                "请先打开开发者选项，再开启无线调试。",
                uiLang
            )
            "WAIT_INPUT" -> nt(
                "Open \"Pair device with pairing code\", then enter the 6-digit code here.",
                "请打开“使用配对码配对设备”，然后在这里输入 6 位配对码。",
                uiLang
            )
            "PAIRING" -> nt(
                "Pairing in progress. Keep the pairing page open.",
                "正在配对，请保持配对码页面开启。",
                uiLang
            )
            "PAIRED" -> nt(
                "Pairing succeeded. Return to the app and tap \"I already paired before, start directly\".",
                "配对成功。请回到应用，点击“我之前已经配对过，直接启动”。",
                uiLang
            )
            "CONNECTING" -> nt(
                "Pairing succeeded. Connecting to the phone now.",
                "配对成功，正在连接手机。",
                uiLang
            )
            "STARTING_CORE" -> nt(
                "Connected. Starting the core service now.",
                "已连接，正在启动核心服务。",
                uiLang
            )
            "RUNNING" -> nt(
                "Startup finished. You can return to the app.",
                "启动完成，现在可以回到应用使用。",
                uiLang
            )
            "FAILED" -> nt(
                "Startup failed. Open the app to retry or check the guide.",
                "启动失败，请回到应用重试或查看引导。",
                uiLang
            )
            "RECONNECTING" -> nt(
                "Connection dropped. Trying to recover in the background.",
                "连接已断开，正在后台尝试恢复。",
                uiLang
            )
            "STOPPING" -> nt(
                "Stopping the core process.",
                "正在停止核心进程。",
                uiLang
            )
            else -> nt(
                "Start the guide, then follow the steps in the app.",
                "请先开始引导，然后按应用内步骤操作。",
                uiLang
            )
        }
        lines.add(main)
        latestPairingEndpoint?.let {
            lines.add(
                nt(
                    "Detected pairing service: ${it.asText()}",
                    "已检测到配对服务：${it.asText()}",
                    uiLang
                )
            )
        }
        latestConnectEndpoint?.let {
            lines.add(
                nt(
                    "Detected connect service: ${it.asText()}",
                    "已检测到连接服务：${it.asText()}",
                    uiLang
                )
            )
        }
        if (currentState == "FAILED" || currentState == "RECONNECTING") {
            lines.add(
                nt(
                    "Details: $currentMessage",
                    "详情：$currentMessage",
                    uiLang
                )
            )
        }
        return lines.joinToString("\n")
    }

    private fun currentUiLang(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_UI_LANG, "en").orEmpty().trim().lowercase()
        return if (raw == "zh") "zh" else "en"
    }

    private fun nt(en: String, zh: String, uiLang: String): String {
        return if (uiLang == "zh") zh else en
    }
}
