package com.example.lxb_ignition.shizuku

import com.example.lxb_ignition.IShizukuService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Runs inside Shizuku user service process (shell uid).
 * No Android Context is available here.
 */
class ShizukuServiceImpl : IShizukuService.Stub() {

    companion object {
        private const val LOG_FILE = "/data/local/tmp/lxb-core.log"
    }

    override fun deployJar(jarBytes: ByteArray, destPath: String): Boolean {
        return try {
            FileOutputStream(destPath).use { it.write(jarBytes) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun startServer(jarPath: String, serverClass: String, port: Int): String {
        return startServerWithJvmOpts(jarPath, serverClass, port, "")
    }

    override fun startServerWithJvmOpts(
        jarPath: String,
        serverClass: String,
        port: Int,
        jvmOpts: String
    ): String {
        return try {
            // Stop old process first to avoid EADDRINUSE.
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f $serverClass")).waitFor()
            Thread.sleep(800)

            val extraJvmOpts = jvmOpts.trim().let { if (it.isEmpty()) "" else "$it " }
            val cmd = "nohup app_process " +
                    "-Djava.class.path=$jarPath " +
                    extraJvmOpts +
                    "/system/bin $serverClass $port " +
                    "> $LOG_FILE 2>&1 &"
            val sh = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            sh.waitFor()

            Thread.sleep(2500)

            if (checkRunning(serverClass)) {
                "OK\nServer started, log: $LOG_FILE"
            } else {
                val log = readTail(LOG_FILE, 1024)
                "ERROR\nProcess not found, log:\n$log"
            }
        } catch (e: Exception) {
            "ERROR\n${e.message}"
        }
    }

    override fun stopServer(serverClass: String) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f $serverClass")).waitFor()
        }
    }

    override fun isRunning(serverClass: String): Boolean = checkRunning(serverClass)

    override fun readLogPart(fromByte: Long, maxBytes: Int): String {
        return try {
            val file = File(LOG_FILE)
            if (!file.exists() || fromByte >= file.length()) return ""
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fromByte)
                val toRead = minOf(maxBytes.toLong(), file.length() - fromByte).toInt()
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) { "" }
    }

    override fun destroy() {}

    private fun checkRunning(serverClass: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", serverClass))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun readTail(path: String, maxBytes: Int): String {
        return try {
            val file = File(path)
            if (!file.exists()) return "(log file not found)"
            val len = file.length()
            val from = maxOf(0L, len - maxBytes)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) { "(failed to read log)" }
    }
}
