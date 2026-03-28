package com.example.lxb_ignition.model

data class TaskRuntimeUiStatus(
    val running: Boolean = false,
    val taskId: String = "",
    val phase: String = "IDLE",
    val detail: String = "Idle"
)

data class CoreRuntimeStatus(
    val ready: Boolean = false,
    val detail: String = "Not connected"
)

data class WirelessBootstrapStatus(
    val running: Boolean = false,
    val state: String = "IDLE",
    val message: String = "Idle"
)
