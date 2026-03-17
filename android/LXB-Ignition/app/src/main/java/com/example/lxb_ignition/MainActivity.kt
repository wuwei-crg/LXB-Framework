package com.example.lxb_ignition

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.NumberPicker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lxb_ignition.shizuku.ShizukuManager
import com.example.lxb_ignition.ui.theme.LXBIgnitionTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LXBIgnitionTheme {
                LXBIgnitionApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LXBIgnitionApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Control", "Tasks", "Config", "Logs")

    Scaffold(
        topBar = { TopAppBar(title = { Text("LXB Ignition") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(label.take(1), fontSize = 18.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ControlTab(viewModel, Modifier.padding(innerPadding))
            1 -> TasksTab(viewModel, Modifier.padding(innerPadding))
            2 -> ConfigTab(viewModel, Modifier.padding(innerPadding))
            3 -> LogsTab(viewModel, Modifier.padding(innerPadding))
        }
    }
}

// Tab 1: Control

@Composable
fun ControlTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val requirement by viewModel.requirement.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Shizuku / lxb-core status
        ShizukuStatusCard(state = state, message = statusMessage)

        // Start / stop lxb-core via Shizuku
        ServerControlRow(
            state = state,
            onRequestPermission = { viewModel.requestShizukuPermission() },
            onStart = { viewModel.startServer() },
            onStop = { viewModel.stopServer() }
        )

        // Chat-style task session
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Task Session", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(chatMessages) { msg ->
                        ChatBubble(message = msg)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = requirement,
                        onValueChange = { viewModel.requirement.value = it },
                        label = { Text("Describe what you want to do") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = { viewModel.runRequirementOnDevice() },
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("Run")
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancelCurrentTaskOnDevice() },
                            modifier = Modifier.height(32.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 4.dp
                            )
                        ) {
                            Text("Stop", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// Tab 2: Tasks

@Composable
fun TasksTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by viewModel.taskList.collectAsState()
    val schedules by viewModel.scheduleList.collectAsState()
    val scheduleName by viewModel.scheduleName.collectAsState()
    val scheduleTask by viewModel.scheduleTask.collectAsState()
    val scheduleStartAtMs by viewModel.scheduleStartAtMs.collectAsState()
    val scheduleRepeatMode by viewModel.scheduleRepeatMode.collectAsState()
    val scheduleRepeatWeekdays by viewModel.scheduleRepeatWeekdays.collectAsState()
    val schedulePackage by viewModel.schedulePackage.collectAsState()
    val scheduleStartPage by viewModel.scheduleStartPage.collectAsState()
    val schedulePlaybook by viewModel.schedulePlaybook.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editingScheduleId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshScheduleListOnDevice()
        viewModel.refreshTaskListOnDevice()
    }

    when (page) {
        0 -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Task Manager", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshScheduleListOnDevice()
                            viewModel.refreshTaskListOnDevice()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        ),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh All", fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { page = 1 }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Schedules", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Manage scheduled tasks and create new ones. (${schedules.size})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { page = 2 }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Recent Runs", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "View recent execution records. (${tasks.size})",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        1 -> {
            val scheduleListState = rememberLazyListState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { page = 0 },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Back", fontSize = 12.sp)
                    }
                    Text(
                        text = "Schedules",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            editingScheduleId = ""
                            viewModel.resetScheduleForm()
                            page = 3
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("New", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshScheduleListOnDevice() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (schedules.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "No schedules yet.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = scheduleListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(schedules, key = { it.scheduleId }) { schedule ->
                                ScheduleRow(
                                    schedule = schedule,
                                    onEdit = {
                                        editingScheduleId = schedule.scheduleId
                                        viewModel.loadScheduleForm(schedule)
                                        page = 3
                                    },
                                    onDelete = { viewModel.removeScheduleOnDevice(schedule.scheduleId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        2 -> {
            val taskListState = rememberLazyListState()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { page = 0 },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Back", fontSize = 12.sp)
                    }
                    Text(
                        text = "Recent Runs",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                    OutlinedButton(
                        onClick = { viewModel.refreshTaskListOnDevice() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (tasks.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "No runs yet. Submit a task from Control tab or wait for schedules.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        LazyColumn(
                            state = taskListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(tasks, key = { it.taskId }) { task ->
                                TaskRow(task = task, onClick = { viewModel.showTaskSummaryInChat(task) })
                            }
                        }
                    }
                }
            }
        }

        else -> {
            val context = LocalContext.current
            val selectedRunAt = scheduleStartAtMs.toLongOrNull()?.takeIf { it > 0L }
                ?: (System.currentTimeMillis() + 5 * 60_000L)
            var showTimeWheel by rememberSaveable { mutableStateOf(false) }
            val isEditing = editingScheduleId.isNotEmpty()
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            page = 1
                            editingScheduleId = ""
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Back", fontSize = 12.sp)
                    }
                    Text(
                        text = if (isEditing) "Edit Schedule" else "Create Schedule",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 6.dp)
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = scheduleTask,
                            onValueChange = { viewModel.scheduleTask.value = it },
                            label = { Text("Task description (required)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        Text(
                            text = "Run time: ${formatTsFull(selectedRunAt)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cal = Calendar.getInstance().apply { timeInMillis = selectedRunAt }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val next = Calendar.getInstance().apply {
                                                timeInMillis = selectedRunAt
                                                set(Calendar.YEAR, y)
                                                set(Calendar.MONTH, m)
                                                set(Calendar.DAY_OF_MONTH, d)
                                            }
                                            viewModel.scheduleStartAtMs.value = next.timeInMillis.toString()
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pick Date")
                            }
                            OutlinedButton(
                                onClick = {
                                    showTimeWheel = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pick Time")
                            }
                        }
                        Text(
                            text = "Repeat",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RepeatModeButton(
                                text = "Once",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_ONCE,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_ONCE },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = "Daily",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_DAILY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_DAILY },
                                modifier = Modifier.weight(1f)
                            )
                            RepeatModeButton(
                                text = "Weekly",
                                selected = scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY,
                                onClick = { viewModel.scheduleRepeatMode.value = MainViewModel.REPEAT_WEEKLY },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (scheduleRepeatMode == MainViewModel.REPEAT_WEEKLY) {
                            val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until 4) {
                                    val selected = ((scheduleRepeatWeekdays shr i) and 1) == 1
                                    WeekdayButton(
                                        text = labels[i],
                                        selected = selected,
                                        onClick = {
                                            viewModel.scheduleRepeatWeekdays.value = scheduleRepeatWeekdays xor (1 shl i)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 4..6) {
                                    val selected = ((scheduleRepeatWeekdays shr i) and 1) == 1
                                    WeekdayButton(
                                        text = labels[i],
                                        selected = selected,
                                        onClick = {
                                            viewModel.scheduleRepeatWeekdays.value = scheduleRepeatWeekdays xor (1 shl i)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            Text(
                                text = "Selected days: ${formatWeekdayMask(scheduleRepeatWeekdays)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        OutlinedTextField(
                            value = scheduleName,
                            onValueChange = { viewModel.scheduleName.value = it },
                            label = { Text("Name (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = schedulePackage,
                            onValueChange = { viewModel.schedulePackage.value = it },
                            label = { Text("Package (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = scheduleStartPage,
                            onValueChange = { viewModel.scheduleStartPage.value = it },
                            label = { Text("Start page (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = schedulePlaybook,
                            onValueChange = { viewModel.schedulePlaybook.value = it },
                            label = { Text("User playbook (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isEditing) {
                                        viewModel.updateScheduleOnDevice(editingScheduleId)
                                    } else {
                                        viewModel.addScheduleOnDevice()
                                    }
                                    page = 1
                                    editingScheduleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isEditing) "Save" else "Submit")
                            }
                            OutlinedButton(
                                onClick = {
                                    page = 1
                                    editingScheduleId = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
            if (showTimeWheel) {
                val cal = Calendar.getInstance().apply { timeInMillis = selectedRunAt }
                WheelTimePickerDialog(
                    initialHour = cal.get(Calendar.HOUR_OF_DAY),
                    initialMinute = cal.get(Calendar.MINUTE),
                    onDismiss = { showTimeWheel = false },
                    onConfirm = { hour, minute ->
                        val next = Calendar.getInstance().apply {
                            timeInMillis = selectedRunAt
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        viewModel.scheduleStartAtMs.value = next.timeInMillis.toString()
                        showTimeWheel = false
                    }
                )
            }
        }
    }
}

@Composable
fun ScheduleRow(
    schedule: MainViewModel.ScheduleSummary,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (schedule.name.isNotEmpty()) schedule.name else schedule.userTask,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "run_at=${formatTsFull(schedule.runAtMs)}, repeat=${formatRepeat(schedule.repeatMode, schedule.repeatWeekdays)}, next=${formatTsFull(schedule.nextRunAt)}, triggered=${schedule.triggerCount}",
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (schedule.packageName.isNotEmpty()) {
                Text(
                    text = "package=${schedule.packageName}",
                    fontSize = 11.sp,
                    color = scheme.onSurface.copy(alpha = 0.75f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "id=${schedule.scheduleId.take(8)}...",
                    fontSize = 10.sp,
                    color = scheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Delete", fontSize = 11.sp, color = scheme.error)
                }
            }
        }
    }
}

@Composable
fun TaskRow(task: MainViewModel.TaskSummary, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bgColor = when (task.state) {
        "COMPLETED" -> scheme.surfaceVariant
        "CANCELLED" -> scheme.surfaceVariant
        "FAILED" -> scheme.errorContainer
        "RUNNING" -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val primaryTextColor = if (task.state == "FAILED") scheme.onErrorContainer else scheme.onSurface
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = if (task.userTask.isNotEmpty()) task.userTask else "(no task description)",
                style = MaterialTheme.typography.bodyMedium,
                color = primaryTextColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            val stateLabel = buildString {
                append(task.state)
                if (task.finalState.isNotEmpty()) {
                    append(" / ").append(task.finalState)
                }
                if (task.packageName.isNotEmpty()) {
                    append(" / ").append(task.packageName)
                }
                if (task.source.isNotEmpty()) {
                    append(" / ").append(task.source)
                }
                if (task.memoryApplied) {
                    append(" / memory")
                }
            }
            Text(
                text = stateLabel,
                fontSize = 11.sp,
                color = scheme.onSurface.copy(alpha = 0.75f)
            )
            if (task.reason.isNotEmpty()) {
                Text(
                    text = "reason=${task.reason}",
                    fontSize = 10.sp,
                    color = scheme.error
                )
            }
        }
    }
}

@Composable
private fun WheelTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var hour by rememberSaveable { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by rememberSaveable { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Hour", fontSize = 12.sp)
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 23
                                wrapSelectorWheel = true
                                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
                                styleWheelNumberPicker(this)
                            }
                        },
                        update = { picker ->
                            styleWheelNumberPicker(picker)
                            if (picker.value != hour) {
                                picker.value = hour
                            }
                            picker.setOnValueChangedListener { _, _, newVal ->
                                hour = newVal
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Minute", fontSize = 12.sp)
                    AndroidView(
                        factory = { ctx ->
                            NumberPicker(ctx).apply {
                                minValue = 0
                                maxValue = 59
                                wrapSelectorWheel = true
                                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
                                styleWheelNumberPicker(this)
                            }
                        },
                        update = { picker ->
                            styleWheelNumberPicker(picker)
                            if (picker.value != minute) {
                                picker.value = minute
                            }
                            picker.setOnValueChangedListener { _, _, newVal ->
                                minute = newVal
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hour, minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun styleWheelNumberPicker(picker: NumberPicker) {
    runCatching {
        picker.setBackgroundColor(android.graphics.Color.WHITE)
        for (i in 0 until picker.childCount) {
            val child = picker.getChildAt(i)
            if (child is EditText) {
                child.setTextColor(android.graphics.Color.BLACK)
                child.textSize = 20f
            }
        }
    }
}

@Composable
private fun RepeatModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    }
}

@Composable
private fun WeekdayButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text, fontSize = 11.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text, fontSize = 11.sp)
        }
    }
}

private fun formatTsFull(ms: Long): String {
    if (ms <= 0L) return "-"
    return runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(ms))
    }.getOrElse { "-" }
}

private fun formatWeekdayMask(mask: Int): String {
    if ((mask and 0x7F) == 0) return "-"
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selected = mutableListOf<String>()
    for (i in 0..6) {
        if (((mask shr i) and 1) == 1) {
            selected.add(labels[i])
        }
    }
    return if (selected.isEmpty()) "-" else selected.joinToString(", ")
}

private fun formatRepeat(modeRaw: String, weekdays: Int): String {
    return when (modeRaw.lowercase(Locale.getDefault())) {
        MainViewModel.REPEAT_DAILY -> "daily"
        MainViewModel.REPEAT_WEEKLY -> "weekly(${formatWeekdayMask(weekdays)})"
        else -> "once"
    }
}

@Composable
fun ChatBubble(message: MainViewModel.ChatMessage) {
    val isUser = message.role == MainViewModel.ChatRole.USER
    val bgColor: Color
    val textColor: Color
    if (isUser) {
        bgColor = Color(0xFF1976D2)
        textColor = Color.White
    } else {
        val t = message.text
        bgColor = when {
            t.startsWith("Task submitted") || t.contains("finished successfully") -> Color(0xFFE8F5E9)
            t.startsWith("Task id:") -> Color(0xFFE3F2FD)
            t.contains("failed") || t.contains("error", ignoreCase = true) -> Color(0xFFFFEBEE)
            t.startsWith("Cancel requested") || t.contains("cancelled") -> Color(0xFFFFF3E0)
            else -> Color(0xFFE0E0E0)
        }
        textColor = if (bgColor == Color(0xFFE0E0E0)) Color.Black else Color(0xFF212121)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = bgColor
            )
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun ShizukuStatusCard(state: ShizukuManager.State, message: String) {
    val (bgColor, label) = when (state) {
        ShizukuManager.State.UNAVAILABLE -> Color(0xFF9E9E9E) to "Shizuku unavailable"
        ShizukuManager.State.PERMISSION_DENIED -> Color(0xFFFF9800) to "Permission required"
        ShizukuManager.State.READY -> Color(0xFF2196F3) to "Ready"
        ShizukuManager.State.STARTING -> Color(0xFF9C27B0) to "Starting..."
        ShizukuManager.State.RUNNING -> Color(0xFF4CAF50) to "Running"
        ShizukuManager.State.ERROR -> Color(0xFFF44336) to "Error"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(message, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ServerControlRow(
    state: ShizukuManager.State,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state == ShizukuManager.State.PERMISSION_DENIED) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("Grant Shizuku")
            }
        }
        OutlinedButton(
            onClick = onStart,
            enabled = state == ShizukuManager.State.READY || state == ShizukuManager.State.ERROR,
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 4.dp
            )
        ) {
            Text("Start")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = state == ShizukuManager.State.RUNNING,
            modifier = Modifier
                .weight(1f)
                .height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 4.dp
            )
        ) {
            Text("Stop")
        }
    }
}

// Tab 3: Config

@Composable
fun ConfigTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    // simple in-tab navigation: 0 = overview, 1 = device core server, 2 = PC web_console, 3 = LLM
    var page by rememberSaveable { mutableIntStateOf(0) }

    when (page) {
        0 -> ConfigOverviewPage(
            modifier = modifier,
            onOpenDeviceCore = { page = 1 },
            onOpenPcConsole = { page = 2 },
            onOpenLlm = { page = 3 }
        )
        1 -> SingleConfigPage(
            title = "Device core server",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LxbCoreConfigCard(viewModel)
        }
        2 -> SingleConfigPage(
            title = "PC web_console",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            PcConsoleConfigCard(viewModel)
        }
        3 -> SingleConfigPage(
            title = "LLM config (device-side)",
            modifier = modifier,
            onBack = { page = 0 }
        ) {
            LlmConfigCard(viewModel)
        }
    }
}

// Tab 3: Logs

@Composable
fun LogsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val logLines by viewModel.logLines.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LogPanel(logLines = logLines, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun LogPanel(logLines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize()
        ) {
            Text("Logs", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.05f))
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.startsWith("[ERR]") -> Color(0xFFF44336)
                            line.startsWith("[LXB]") -> Color(0xFF2196F3)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// Config overview + sub-pages

@Composable
fun ConfigOverviewPage(
    modifier: Modifier = Modifier,
    onOpenDeviceCore: () -> Unit,
    onOpenPcConsole: () -> Unit,
    onOpenLlm: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Config center",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Choose a category to configure. Each section opens a dedicated settings page.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        ConfigEntryCard(
            title = "Device core server",
            description = "UDP port and related options for lxb-core running on this device.",
            onClick = onOpenDeviceCore
        )
        ConfigEntryCard(
            title = "PC web_console",
            description = "IP/port for optional PC-side web_console debugging.",
            onClick = onOpenPcConsole
        )
        ConfigEntryCard(
            title = "LLM config (device-side)",
            description = "Base URL, API key and model for device-side LLM/VLM calls.",
            onClick = onOpenLlm
        )
    }
}

@Composable
fun ConfigEntryCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
fun SingleConfigPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 4.dp
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Back", fontSize = 12.sp)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        content()
    }
}

@Composable
fun LxbCoreConfigCard(viewModel: MainViewModel) {
    val lxbPort by viewModel.lxbPort.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("lxb-core server", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = lxbPort,
                onValueChange = { viewModel.lxbPort.value = it },
                label = { Text("UDP port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        "UDP port listened by lxb-core on device (default 12345)",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
fun PcConsoleConfigCard(viewModel: MainViewModel) {
    val serverIp by viewModel.serverIp.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("PC web_console", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = serverIp,
                onValueChange = { viewModel.serverIp.value = it },
                label = { Text("Server IP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        "IP address of the PC running web_console",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
            OutlinedTextField(
                value = serverPort,
                onValueChange = { viewModel.serverPort.value = it },
                label = { Text("Server port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        "Flask port of web_console (default 5000)",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
fun LlmConfigCard(viewModel: MainViewModel) {
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmTestResult by viewModel.llmTestResult.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("LLM config (device-side)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = llmBaseUrl,
                onValueChange = { viewModel.llmBaseUrl.value = it },
                label = { Text("API Base URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        "e.g. https://api.openai.com/v1/chat/completions",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            )
            OutlinedTextField(
                value = llmApiKey,
                onValueChange = { viewModel.llmApiKey.value = it },
                label = { Text("API Key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = llmModel,
                onValueChange = { viewModel.llmModel.value = it },
                label = { Text("Model") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("e.g. gpt-4o-mini, qwen-plus") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.testLlmAndSyncConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test LLM & sync to device")
                }
                OutlinedButton(
                    onClick = { viewModel.saveConfig() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save only")
                }
            }
            if (llmTestResult.isNotEmpty()) {
                Text(
                    text = llmTestResult,
                    fontSize = 12.sp,
                    color = if (llmTestResult.startsWith("LLM ")) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFFF9800)
                    }
                )
            }
        }
    }
}
