package com.example.lxb_ignition.map

import android.app.Application
import com.example.lxb_ignition.storage.AppStatePaths
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MapSyncManager(
    private val app: Application,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val REGISTRY_FILE = "map_registry.json"
        private const val ACTIVE_FILE = "map_active.json"
        private const val CACHE_DIR = "map_cloud_cache"
        private const val LANE_ROOT_DIR = "_lane"
        private const val REGISTRY_SCHEMA = "lxb.map.registry.v1"
        private const val ACTIVE_SCHEMA = "lxb.map.active.v3"

        private const val SOURCE_STABLE = "stable"
        private const val SOURCE_CANDIDATE = "candidate"
        private const val SOURCE_BURN = "burn"

        private const val LANE_STABLE = "stable"
        private const val LANE_CANDIDATES = "candidates"
    }

    data class MapEntry(
        val lane: String,
        val packageName: String,
        val mapId: String,
        val mapPath: String,
        val metaPath: String,
        val sha256: String,
        val submittedAt: String,
        val generatedAt: String,
        val stableAt: String,
    )

    data class SyncIndexResult(
        val lane: String,
        val count: Int,
        val indexUrl: String
    )

    data class ApplyAllResult(
        val lane: String,
        val indexedCount: Int,
        val totalPackages: Int,
        val appliedPackages: Int,
        val failedPackages: Int
    )

    data class ApplyResult(
        val readPackages: Int,
        val appliedPackages: Int,
        val failedPackages: Int
    )

    fun normalizeSelectedSource(selectedSourceRaw: String): String {
        return normalizeSource(selectedSourceRaw)
    }

    fun syncLaneIndex(rawBaseUrl: String, laneRaw: String): Result<SyncIndexResult> = runCatching {
        val lane = normalizeRepoLane(laneRaw)
        val base = normalizeRawBaseUrl(rawBaseUrl)
        val indexUrl = "$base/manifests/$lane/latest.json"
        val body = httpGetBytes(indexUrl)
        val text = body.toString(StandardCharsets.UTF_8)
        val obj = JSONObject(text)
        val arr = when {
            obj.has("items") && obj.opt("items") is JSONArray -> obj.optJSONArray("items")
            obj.has("maps") && obj.opt("maps") is JSONArray -> obj.optJSONArray("maps")
            obj.has("candidates") && obj.opt("candidates") is JSONArray -> obj.optJSONArray("candidates")
            else -> JSONArray()
        } ?: JSONArray()

        val normalized = JSONArray()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val pkg = row.optString("package", "").trim()
            val mapPath = row.optString("map_path", "").trim()
            if (pkg.isEmpty() || mapPath.isEmpty()) continue
            val mapId = row.optString("map_id", "").trim().ifEmpty { guessMapIdFromMapPath(mapPath) }
            normalized.put(
                JSONObject()
                    .put("lane", lane)
                    .put("package", pkg)
                    .put("map_id", mapId)
                    .put("map_path", mapPath)
                    .put("meta_path", row.optString("meta_path", "").trim())
                    .put("sha256", row.optString("sha256", "").trim().lowercase(Locale.US))
                    .put("submitted_at", row.optString("submitted_at", "").trim())
                    .put("generated_at", row.optString("generated_at", "").trim())
                    .put("stable_at", row.optString("stable_at", "").trim())
            )
        }

        val registry = loadRegistry()
        val lanes = registry.optJSONObject("lanes") ?: JSONObject()
        lanes.put(
            lane,
            JSONObject()
                .put("fetched_at", Instant.now().toString())
                .put("index_url", indexUrl)
                .put("maps", normalized)
        )
        registry.put("schema_version", REGISTRY_SCHEMA)
        registry.put("updated_at", Instant.now().toString())
        registry.put("lanes", lanes)
        saveJson(registryFile(), registry)
        SyncIndexResult(lane = lane, count = normalized.length(), indexUrl = indexUrl)
    }

    fun syncStableAndApplyAll(rawBaseUrl: String, selectedSourceRaw: String): Result<ApplyAllResult> = runCatching {
        val lane = LANE_STABLE
        val selectedSource = normalizeSource(selectedSourceRaw)
        val idx = syncLaneIndex(rawBaseUrl, lane).getOrThrow()
        val entries = allEntriesForLane(lane)
        if (entries.isEmpty()) {
            throw IllegalStateException("no stable maps found")
        }
        val latestByPkg = linkedMapOf<String, MapEntry>()
        for (e in entries.sortedWith(
            compareByDescending<MapEntry> { stableRankTs(it) }
                .thenByDescending { fallbackRankTs(it) }
                .thenByDescending { it.mapId }
        )) {
            if (!latestByPkg.containsKey(e.packageName)) {
                latestByPkg[e.packageName] = e
            }
        }

        var applied = 0
        var failed = 0
        for ((pkg, entry) in latestByPkg) {
            val ok = runCatching {
                val jsonText = downloadAndValidateJson(rawBaseUrl, entry)
                writeCacheEntry(entry.lane, pkg, entry.mapId, jsonText)
                writeLaneMap(SOURCE_STABLE, pkg, jsonText)
                upsertActiveSlot(pkg, SOURCE_STABLE, entry)
                reconcilePackageRuntime(pkg, selectedSource)
            }.isSuccess
            if (ok) applied++ else failed++
        }
        ApplyAllResult(
            lane = lane,
            indexedCount = idx.count,
            totalPackages = latestByPkg.size,
            appliedPackages = applied,
            failedPackages = failed
        )
    }

    fun pullStableByIdentifier(
        rawBaseUrl: String,
        packageNameRaw: String,
        mapIdRaw: String,
        selectedSourceRaw: String
    ): Result<String> = runCatching {
        val selectedSource = normalizeSource(selectedSourceRaw)
        val pkg = packageNameRaw.trim()
        val mapId = mapIdRaw.trim()
        if (pkg.isEmpty() || mapId.isEmpty()) {
            throw IllegalArgumentException("package and map_id are required")
        }
        var entry = findEntryById(LANE_STABLE, pkg, mapId)
        if (entry == null) {
            syncLaneIndex(rawBaseUrl, LANE_STABLE).getOrThrow()
            entry = findEntryById(LANE_STABLE, pkg, mapId)
        }
        val selected = entry ?: throw IllegalStateException("stable map not found: package=$pkg map_id=$mapId")
        val jsonText = downloadAndValidateJson(rawBaseUrl, selected)
        writeCacheEntry(selected.lane, pkg, selected.mapId, jsonText)
        writeLaneMap(SOURCE_STABLE, pkg, jsonText)
        upsertActiveSlot(pkg, SOURCE_STABLE, selected)
        reconcilePackageRuntime(pkg, selectedSource)
        "Stable map pulled: package=$pkg map_id=$mapId"
    }

    fun pullCandidateByIdentifier(
        rawBaseUrl: String,
        packageNameRaw: String,
        mapIdRaw: String,
        selectedSourceRaw: String
    ): Result<String> = runCatching {
        val selectedSource = normalizeSource(selectedSourceRaw)
        val pkg = packageNameRaw.trim()
        val mapId = mapIdRaw.trim()
        if (pkg.isEmpty() || mapId.isEmpty()) {
            throw IllegalArgumentException("package and map_id are required")
        }
        var entry = findEntryById(LANE_CANDIDATES, pkg, mapId)
        if (entry == null) {
            syncLaneIndex(rawBaseUrl, LANE_CANDIDATES).getOrThrow()
            entry = findEntryById(LANE_CANDIDATES, pkg, mapId)
        }
        val selected = entry ?: throw IllegalStateException("candidate map not found: package=$pkg map_id=$mapId")
        val jsonText = downloadAndValidateJson(rawBaseUrl, selected)
        writeCacheEntry(selected.lane, pkg, selected.mapId, jsonText)
        writeLaneMap(SOURCE_CANDIDATE, pkg, jsonText)
        upsertActiveSlot(pkg, SOURCE_CANDIDATE, selected)
        reconcilePackageRuntime(pkg, selectedSource)
        "Candidate map pulled: package=$pkg map_id=$mapId"
    }

    fun applySelectedSourceToAllPackages(selectedSourceRaw: String): Result<ApplyResult> = runCatching {
        val selectedSource = normalizeSource(selectedSourceRaw)
        val names = listPackagesWithValidMapInLane(selectedSource)
        var applied = 0
        var failed = 0
        for (pkg in names) {
            val ok = runCatching {
                reconcilePackageRuntime(pkg, selectedSource)
            }.isSuccess
            if (ok) applied++ else failed++
        }
        ApplyResult(
            readPackages = names.size,
            appliedPackages = applied,
            failedPackages = failed
        )
    }

    fun activeStatus(packageNameRaw: String): String {
        val pkg = packageNameRaw.trim()
        if (pkg.isEmpty()) return "package is empty"
        val active = loadActive()
        val packages = active.optJSONObject("packages") ?: return "active status: none"
        val row = packages.optJSONObject(pkg) ?: JSONObject()
        val stable = row.optJSONObject(SOURCE_STABLE)
        val candidate = row.optJSONObject(SOURCE_CANDIDATE)
        val burn = row.optJSONObject(SOURCE_BURN)
        val effective = row.optJSONObject("effective")

        val stableId = stable?.optString("map_id", "")?.takeIf { it.isNotBlank() }
            ?: if (laneMapFile(SOURCE_STABLE, pkg).exists()) "present" else "-"
        val candidateId = candidate?.optString("map_id", "")?.takeIf { it.isNotBlank() }
            ?: if (laneMapFile(SOURCE_CANDIDATE, pkg).exists()) "present" else "-"
        val burnId = burn?.optString("map_id", "")?.takeIf { it.isNotBlank() }
            ?: if (laneMapFile(SOURCE_BURN, pkg).exists()) "present" else "-"
        val source = effective?.optString("source", "")?.takeIf { it.isNotBlank() } ?: "-"
        val effectiveId = effective?.optString("map_id", "")?.takeIf { it.isNotBlank() } ?: "-"
        return "stable=$stableId candidate=$candidateId burn=$burnId effective=$source/$effectiveId"
    }

    fun startupSyncStable(rawBaseUrl: String, selectedSourceRaw: String): Result<String> = runCatching {
        val r = syncStableAndApplyAll(rawBaseUrl, selectedSourceRaw).getOrThrow()
        "startup stable sync done: indexed=${r.indexedCount}, applied=${r.appliedPackages}/${r.totalPackages}, failed=${r.failedPackages}"
    }

    private fun normalizeRepoLane(laneRaw: String): String {
        val lane = laneRaw.trim().lowercase(Locale.US)
        return if (lane == LANE_CANDIDATES || lane == SOURCE_CANDIDATE) LANE_CANDIDATES else LANE_STABLE
    }

    private fun normalizeSource(sourceRaw: String): String {
        val source = sourceRaw.trim().lowercase(Locale.US)
        return when (source) {
            LANE_CANDIDATES -> SOURCE_CANDIDATE
            SOURCE_STABLE -> SOURCE_STABLE
            SOURCE_CANDIDATE -> SOURCE_CANDIDATE
            SOURCE_BURN -> SOURCE_BURN
            else -> SOURCE_STABLE
        }
    }

    private fun normalizeRawBaseUrl(raw: String): String {
        val v = raw.trim().trimEnd('/')
        if (v.isEmpty()) {
            throw IllegalArgumentException("map repo raw base url is empty")
        }
        return v
    }

    private fun findEntryById(lane: String, packageName: String, mapId: String): MapEntry? {
        val list = allEntriesForLane(lane)
        return list.firstOrNull { it.packageName == packageName && it.mapId == mapId }
    }

    private fun allEntriesForLane(lane: String): List<MapEntry> {
        val normalizedLane = normalizeRepoLane(lane)
        val registry = loadRegistry()
        val lanes = registry.optJSONObject("lanes") ?: return emptyList()
        val laneObj = lanes.optJSONObject(normalizedLane) ?: return emptyList()
        val arr = laneObj.optJSONArray("maps") ?: return emptyList()
        val list = mutableListOf<MapEntry>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val pkg = row.optString("package", "").trim()
            if (pkg.isEmpty()) continue
            list.add(
                MapEntry(
                    lane = normalizedLane,
                    packageName = pkg,
                    mapId = row.optString("map_id", "").trim(),
                    mapPath = row.optString("map_path", "").trim(),
                    metaPath = row.optString("meta_path", "").trim(),
                    sha256 = row.optString("sha256", "").trim(),
                    submittedAt = row.optString("submitted_at", "").trim(),
                    generatedAt = row.optString("generated_at", "").trim(),
                    stableAt = row.optString("stable_at", "").trim()
                )
            )
        }
        return list
    }

    private fun stableRankTs(entry: MapEntry): Long {
        val s = parseIsoMillis(entry.stableAt)
        if (s > 0L) return s
        return fallbackRankTs(entry)
    }

    private fun fallbackRankTs(entry: MapEntry): Long {
        val a = parseIsoMillis(entry.submittedAt)
        if (a > 0L) return a
        return parseIsoMillis(entry.generatedAt)
    }

    private fun parseIsoMillis(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrElse { 0L }
    }

    private fun upsertActiveSlot(packageName: String, sourceSlotRaw: String, entry: MapEntry) {
        val sourceSlot = normalizeSource(sourceSlotRaw)
        val active = loadActive()
        val packages = active.optJSONObject("packages") ?: JSONObject()
        val row = packages.optJSONObject(packageName) ?: JSONObject()
        val slot = JSONObject()
            .put("map_id", entry.mapId)
            .put("map_path", entry.mapPath)
            .put("sha256", entry.sha256)
            .put("submitted_at", entry.submittedAt)
            .put("generated_at", entry.generatedAt)
            .put("stable_at", entry.stableAt)
            .put("updated_at", Instant.now().toString())
        row.put(sourceSlot, slot)
        row.put("updated_at", Instant.now().toString())
        packages.put(packageName, row)
        active.put("schema_version", ACTIVE_SCHEMA)
        active.put("updated_at", Instant.now().toString())
        active.put("packages", packages)
        saveJson(activeFile(), active)
    }

    private fun listPackagesInLane(sourceRaw: String): List<String> {
        val source = normalizeSource(sourceRaw)
        val laneDir = File(File(AppStatePaths.getMapDir(app), LANE_ROOT_DIR), source)
        val dirs = laneDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.map { it.name }
    }

    private fun listPackagesWithValidMapInLane(sourceRaw: String): List<String> {
        val source = normalizeSource(sourceRaw)
        val laneDir = File(File(AppStatePaths.getMapDir(app), LANE_ROOT_DIR), source)
        val dirs = laneDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = mutableListOf<String>()
        for (d in dirs) {
            val f = File(d, "nav_map.json")
            if (f.exists() && f.isFile() && f.length() > 0L) {
                out.add(d.name)
            }
        }
        return out
    }

    private fun reconcilePackageRuntime(packageName: String, selectedSourceRaw: String) {
        val selectedSource = normalizeSource(selectedSourceRaw)
        ensureLaneFileReady(packageName, selectedSource)
        updateEffectiveSlot(packageName, selectedSource)
    }

    private fun ensureLaneFileReady(packageName: String, selectedSource: String): File {
        val laneFile = laneMapFile(selectedSource, packageName)
        if (laneFile.exists() && laneFile.isFile() && laneFile.length() > 0L) {
            return laneFile
        }
        throw IllegalStateException("source map missing: source=$selectedSource package=$packageName")
    }

    private fun updateEffectiveSlot(packageName: String, selectedSourceRaw: String) {
        val selectedSource = normalizeSource(selectedSourceRaw)
        val active = loadActive()
        val packages = active.optJSONObject("packages") ?: JSONObject()
        val row = packages.optJSONObject(packageName) ?: JSONObject()
        val slot = row.optJSONObject(selectedSource)
        val mapId = slot?.optString("map_id", "")?.trim().orEmpty().ifBlank {
            if (selectedSource == SOURCE_BURN) "burn_local" else "unknown"
        }
        val effective = JSONObject()
            .put("source", selectedSource)
            .put("lane", sourceToRepoLane(selectedSource))
            .put("map_id", mapId)
            .put("updated_at", Instant.now().toString())
        row.put("effective", effective)
        row.put("updated_at", Instant.now().toString())
        packages.put(packageName, row)
        active.put("schema_version", ACTIVE_SCHEMA)
        active.put("updated_at", Instant.now().toString())
        active.put("packages", packages)
        saveJson(activeFile(), active)
    }

    private fun sourceToRepoLane(sourceRaw: String): String {
        return when (normalizeSource(sourceRaw)) {
            SOURCE_CANDIDATE -> LANE_CANDIDATES
            SOURCE_BURN -> SOURCE_BURN
            else -> LANE_STABLE
        }
    }

    private fun downloadAndValidateJson(rawBaseUrl: String, entry: MapEntry): String {
        val base = normalizeRawBaseUrl(rawBaseUrl)
        val mapUrl = if (entry.mapPath.startsWith("http://") || entry.mapPath.startsWith("https://")) {
            entry.mapPath
        } else {
            "$base/${entry.mapPath.trimStart('/')}"
        }
        val bytes = httpGetBytes(mapUrl)
        val actualSha = sha256Hex(bytes)
        if (entry.sha256.isNotEmpty() && !actualSha.equals(entry.sha256, ignoreCase = true)) {
            throw IllegalStateException("sha256 mismatch: expect=${entry.sha256}, actual=$actualSha")
        }
        val jsonText = decodeMapToJsonText(bytes)
        runCatching { JSONObject(jsonText) }.getOrElse {
            throw IllegalStateException("map json invalid: ${it.message}")
        }
        return jsonText
    }

    private fun writeCacheEntry(laneRaw: String, packageName: String, mapId: String, jsonText: String) {
        val lane = normalizeRepoLane(laneRaw)
        val dir = cacheEntryDir(lane, packageName, mapId)
        if (!dir.exists()) dir.mkdirs()
        writeUtf8(File(dir, "nav_map.json"), jsonText)
        val gzFile = File(dir, "nav_map.json.gz")
        GZIPOutputStream(gzFile.outputStream()).use { gos ->
            gos.write(jsonText.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun cacheEntryDir(lane: String, packageName: String, mapId: String): File {
        val cacheRoot = File(baseDir(), CACHE_DIR)
        return File(cacheRoot, "${lane.trim().lowercase(Locale.US)}/${safePackage(packageName)}/$mapId")
    }

    private fun writeLaneMap(sourceRaw: String, packageName: String, jsonText: String) {
        val laneFile = laneMapFile(sourceRaw, packageName)
        val backupFile = laneBackupMapFile(sourceRaw, packageName)
        if (laneFile.exists()) {
            runCatching {
                if (backupFile.exists()) backupFile.delete()
                laneFile.copyTo(backupFile, overwrite = true)
            }
        }
        writeUtf8(laneFile, jsonText)
    }

    private fun laneMapFile(sourceRaw: String, packageName: String): File {
        val source = normalizeSource(sourceRaw)
        val pkgDir = File(File(File(AppStatePaths.getMapDir(app), LANE_ROOT_DIR), source), safePackage(packageName))
        return File(pkgDir, "nav_map.json")
    }

    private fun laneBackupMapFile(sourceRaw: String, packageName: String): File {
        val source = normalizeSource(sourceRaw)
        val pkgDir = File(File(File(AppStatePaths.getMapDir(app), LANE_ROOT_DIR), source), safePackage(packageName))
        return File(pkgDir, "nav_map.bak.json")
    }

    private fun decodeMapToJsonText(bytes: ByteArray): String {
        val isGz = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        return if (isGz) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                InputStreamReader(gis, StandardCharsets.UTF_8).readText()
            }
        } else {
            bytes.toString(StandardCharsets.UTF_8)
        }
    }

    private fun guessMapIdFromMapPath(path: String): String {
        val parts = path.replace("\\", "/").split("/")
        if (parts.size < 2) return ""
        return parts[parts.size - 2]
    }

    private fun httpGetBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("http ${resp.code} for $url")
            }
            return resp.body?.bytes() ?: throw IllegalStateException("empty body for $url")
        }
    }

    private fun safePackage(packageName: String): String {
        return packageName.trim().replace("/", "_").replace("\\", "_")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun baseDir(): File {
        return AppStatePaths.getStateBaseDir(app)
    }

    private fun registryFile(): File = File(baseDir(), REGISTRY_FILE)

    private fun activeFile(): File = File(baseDir(), ACTIVE_FILE)

    private fun loadRegistry(): JSONObject {
        val f = registryFile()
        if (!f.exists()) {
            return JSONObject()
                .put("schema_version", REGISTRY_SCHEMA)
                .put("updated_at", "")
                .put("lanes", JSONObject())
        }
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrElse {
            JSONObject()
                .put("schema_version", REGISTRY_SCHEMA)
                .put("updated_at", "")
                .put("lanes", JSONObject())
        }
    }

    private fun loadActive(): JSONObject {
        val f = activeFile()
        if (!f.exists()) {
            return JSONObject()
                .put("schema_version", ACTIVE_SCHEMA)
                .put("updated_at", "")
                .put("packages", JSONObject())
        }
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrElse {
            JSONObject()
                .put("schema_version", ACTIVE_SCHEMA)
                .put("updated_at", "")
                .put("packages", JSONObject())
        }
    }

    private fun saveJson(file: File, json: JSONObject) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        OutputStreamWriter(file.outputStream(), StandardCharsets.UTF_8).use { w ->
            w.write(json.toString(2))
        }
    }

    private fun writeUtf8(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        OutputStreamWriter(file.outputStream(), StandardCharsets.UTF_8).use { w ->
            w.write(content)
        }
    }
}
