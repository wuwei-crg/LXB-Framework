package com.example.lxb_ignition.core

import org.json.JSONObject

data class AppUpdateCheckResult(
    val message: String,
    val targetUrl: String
)

object AppUpdateChecker {
    fun evaluateLatestRelease(
        httpCode: Int,
        bodyText: String,
        currentVersionName: String,
        defaultWebLatest: String
    ): AppUpdateCheckResult {
        if (httpCode !in 200..299) {
            return AppUpdateCheckResult(
                message = "Update check failed: HTTP $httpCode ${bodyText.take(160)}",
                targetUrl = ""
            )
        }

        val obj = runCatching { JSONObject(bodyText) }.getOrNull()
            ?: return AppUpdateCheckResult("Update check failed: invalid GitHub response.", "")

        val tag = obj.optString("tag_name", "").trim()
        val latestVersion = normalizeVersion(tag)
        val currentVersion = normalizeVersion(currentVersionName)
        val htmlUrl = obj.optString("html_url", defaultWebLatest).trim()
        val apkUrl = findReleaseApkUrl(obj)
        val cmp = compareVersion(latestVersion, currentVersion)

        if (latestVersion.isBlank()) {
            return AppUpdateCheckResult("Update check failed: latest tag is empty.", "")
        }

        if (cmp <= 0) {
            return AppUpdateCheckResult(
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
        return AppUpdateCheckResult(msg, target)
    }

    private fun findReleaseApkUrl(obj: JSONObject): String {
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
}
