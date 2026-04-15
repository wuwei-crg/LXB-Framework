package com.example.lxb_ignition.model

data class TaskSummary(
    val taskId: String,
    val userTask: String,
    val state: String,
    val finalState: String,
    val reason: String,
    val taskSummary: String,
    val packageName: String,
    val targetPage: String,
    val source: String,
    val scheduleId: String,
    val taskKeyHash: String,
    val taskMapMode: String,
    val hasTaskMap: Boolean,
    val memoryApplied: Boolean,
    val recordEnabled: Boolean,
    val recordFile: String,
    val createdAt: Long,
    val finishedAt: Long
)

data class TaskMapDetail(
    val taskKeyHash: String,
    val mode: String,
    val source: String,
    val sourceId: String,
    val userTask: String,
    val packageName: String,
    val hasMap: Boolean,
    val hasLatestSuccessRecord: Boolean,
    val hasLatestAttemptRecord: Boolean,
    val taskMap: TaskMapSnapshot?,
    val latestSuccessRecord: TaskRouteRecordSnapshot?,
    val latestAttemptRecord: TaskRouteRecordSnapshot?
)

data class TaskMapSnapshot(
    val schema: String,
    val mode: String,
    val packageName: String,
    val packageLabel: String,
    val createdFromTaskId: String,
    val createdAtMs: Long,
    val lastReplayStatus: String,
    val finishAfterReplay: Boolean,
    val segments: List<TaskMapSegmentSnapshot>
)

data class TaskMapSegmentSnapshot(
    val segmentId: String,
    val subTaskId: String,
    val subTaskIndex: Int,
    val subTaskDescription: String,
    val successCriteria: String,
    val packageName: String,
    val packageLabel: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val steps: List<TaskMapStepSnapshot>
)

data class TaskMapStepSnapshot(
    val stepId: String,
    val sourceActionId: String,
    val op: String,
    val args: List<String>,
    val fallbackPoint: String,
    val semanticNote: String,
    val expected: String,
    val locatorFields: List<TraceMetaItem>
)

data class TaskRouteRecordSnapshot(
    val schema: String,
    val taskId: String,
    val rootTask: String,
    val packageName: String,
    val packageLabel: String,
    val createdAtMs: Long,
    val status: String,
    val finalState: String,
    val reason: String,
    val actions: List<TaskRouteActionSnapshot>
)

data class TaskRouteActionSnapshot(
    val actionId: String,
    val subTaskId: String,
    val turn: Int,
    val op: String,
    val args: List<String>,
    val rawCommand: String,
    val execResult: String,
    val execError: String,
    val createdPageSemantics: String,
    val locatorFields: List<TraceMetaItem>,
    val visionFields: List<TraceMetaItem>
)

data class ScheduleSummary(
    val scheduleId: String,
    val name: String,
    val userTask: String,
    val packageName: String,
    val startPage: String,
    val recordEnabled: Boolean,
    val taskMapMode: String,
    val runAtMs: Long,
    val repeatMode: String,
    val repeatWeekdays: Int,
    val nextRunAt: Long,
    val lastTriggeredAt: Long,
    val triggerCount: Long,
    val enabled: Boolean,
    val createdAt: Long,
    val userPlaybook: String
)

data class NotificationTriggerRuleSummary(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val packageMode: String,
    val packageList: List<String>,
    val textMode: String,
    val titlePattern: String,
    val bodyPattern: String,
    val llmConditionEnabled: Boolean,
    val llmCondition: String,
    val llmYesToken: String,
    val llmNoToken: String,
    val llmTimeoutMs: Long,
    val taskRewriteEnabled: Boolean,
    val taskRewriteInstruction: String,
    val taskRewriteTimeoutMs: Long,
    val taskRewriteFailPolicy: String,
    val cooldownMs: Long,
    val activeTimeStart: String,
    val activeTimeEnd: String,
    val stopAfterMatched: Boolean,
    val actionType: String,
    val actionUserTask: String,
    val actionPackage: String,
    val actionUserPlaybook: String,
    val actionRecordEnabled: Boolean,
    val actionTaskMapMode: String,
    val actionUseMap: Boolean?
)

data class AppPackageOption(
    val packageName: String,
    val label: String
)

data class TraceMetaItem(
    val label: String,
    val value: String
)

data class TracePage(
    val entries: List<TraceEntry>,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val oldestSeq: Long,
    val newestSeq: Long
)

data class TraceEntry(
    val seq: Long,
    val rawLine: String,
    val timestamp: String,
    val event: String,
    val taskId: String,
    val summary: String,
    val detail: String,
    val isError: Boolean,
    val meta: List<TraceMetaItem>,
    val fields: List<TraceMetaItem>
)
