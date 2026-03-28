package com.example.lxb_ignition.core

import com.example.lxb_ignition.map.MapSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapOperationsController(
    private val viewModelScope: CoroutineScope,
    private val mapSyncManager: MapSyncManager,
    private val mapRepoRawBaseUrl: MutableStateFlow<String>,
    private val mapTargetPackage: MutableStateFlow<String>,
    private val mapTargetId: MutableStateFlow<String>,
    private val mapSource: MutableStateFlow<String>,
    private val useMap: MutableStateFlow<Boolean>,
    private val mapSyncResult: MutableStateFlow<String>,
    private val saveConfig: () -> Unit,
    private val syncDeviceConfig: suspend () -> Result<String>,
    private val appendLog: (String) -> Unit,
    private val appendSystemMessage: (String) -> Unit
) {
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
            val cfgSync = syncDeviceConfig().fold(
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
            val sync = syncDeviceConfig()
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
}
