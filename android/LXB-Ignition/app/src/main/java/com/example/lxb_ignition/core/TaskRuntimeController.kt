package com.example.lxb_ignition.core

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.lxb_ignition.model.TaskRuntimeUiStatus
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.service.TaskRuntimeService
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TaskRuntimeController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val tracePort: Int,
    private val appendLog: (String) -> Unit,
    private val appendSystemMessage: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val _status = MutableStateFlow(TaskRuntimeUiStatus())
    val status: StateFlow<TaskRuntimeUiStatus> = _status.asStateFlow()

    private var activeTraceJob: Job? = null
    private var activeRuntimeTaskId: String = ""

    fun resetForSubmission() {
        activeRuntimeTaskId = ""
        updateIndicator("SUBMITTING", "Submitting task to device...")
    }

    fun startIndicator(taskId: String, phase: String, detail: String) {
        activeRuntimeTaskId = taskId
        _status.value = TaskRuntimeUiStatus(
            running = true,
            taskId = taskId,
            phase = phase.ifEmpty { "RUNNING" },
            detail = detail.ifEmpty { "Task is running." }
        )
        sendServiceAction(
            action = TaskRuntimeService.ACTION_START,
            taskId = taskId,
            phase = phase,
            detail = detail
        )
    }

    fun updateIndicator(phase: String, detail: String = "", taskId: String = "") {
        val tid = if (taskId.isNotEmpty()) taskId else activeRuntimeTaskId
        val current = _status.value
        val normalizedPhase = phase.ifEmpty { current.phase.ifEmpty { "RUNNING" } }
        val running = when (normalizedPhase.uppercase()) {
            "IDLE", "STOP", "DONE", "FAILED", "CANCELLED" -> false
            else -> true
        }
        _status.value = TaskRuntimeUiStatus(
            running = running,
            taskId = if (tid.isNotEmpty()) tid else current.taskId,
            phase = normalizedPhase,
            detail = if (detail.isNotEmpty()) detail else current.detail
        )
        sendServiceAction(
            action = TaskRuntimeService.ACTION_UPDATE,
            taskId = tid,
            phase = phase,
            detail = detail
        )
    }

    fun stopIndicator() {
        sendServiceAction(
            action = TaskRuntimeService.ACTION_STOP,
            taskId = activeRuntimeTaskId,
            phase = "STOP",
            detail = ""
        )
        activeRuntimeTaskId = ""
        _status.value = TaskRuntimeUiStatus()
    }

    fun markCancelling() {
        updateIndicator("CANCELLING", "Cancel request sent.")
    }

    fun syncFromTaskList(items: List<TaskSummary>) {
        val running = items.firstOrNull { s ->
            s.state.equals("RUNNING", ignoreCase = true) &&
                !s.finalState.equals("FAIL", ignoreCase = true) &&
                !s.finalState.equals("FINISH", ignoreCase = true) &&
                !s.finalState.equals("DONE", ignoreCase = true)
        }
        if (running != null) {
            activeRuntimeTaskId = running.taskId
            val detail = when {
                running.taskSummary.isNotBlank() -> running.taskSummary
                running.userTask.isNotBlank() -> running.userTask
                else -> "Task is running."
            }
            _status.value = TaskRuntimeUiStatus(
                running = true,
                taskId = running.taskId,
                phase = running.state.ifEmpty { "RUNNING" },
                detail = detail
            )
            return
        }
        if (_status.value.running) {
            activeRuntimeTaskId = ""
            _status.value = TaskRuntimeUiStatus()
        }
    }

    fun ensureTraceListener(): Job {
        val existing = activeTraceJob
        if (existing != null && existing.isActive) {
            return existing
        }
        val job = scope.launch(ioDispatcher) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(tracePort).apply {
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
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    withContext(mainDispatcher) {
                        onTraceEvent(obj)
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

    fun clear() {
        activeTraceJob?.cancel()
        stopIndicator()
    }

    private fun onTraceEvent(obj: JSONObject) {
        val mapped = TraceEventMapper.map(obj) ?: return
        applyTraceRuntimeUpdate(mapped.taskId, mapped.runtimeUpdate)
        mapped.messages.forEach { appendSystemMessage(it) }
    }

    private fun sendServiceAction(action: String, taskId: String, phase: String, detail: String) {
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

    private fun applyTraceRuntimeUpdate(taskId: String, update: RuntimeUpdate?) {
        if (update == null) {
            return
        }
        if (!shouldTrackRuntimeTask(taskId)) {
            return
        }
        updateIndicator(update.phase, update.detail, taskId)
        if (update.stopAfter) {
            stopIndicator()
        }
    }
}
